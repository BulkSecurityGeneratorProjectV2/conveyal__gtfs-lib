package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.model.Entity;
import com.conveyal.gtfs.model.Location;
import com.conveyal.gtfs.model.LocationShape;
import com.conveyal.gtfs.model.PatternHalt;
import com.conveyal.gtfs.model.PatternLocation;
import com.conveyal.gtfs.model.PatternLocationGroup;
import com.conveyal.gtfs.model.PatternStop;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.storage.StorageException;
import com.conveyal.gtfs.util.InvalidNamespaceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Multimap;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.dbutils.DbUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.conveyal.gtfs.loader.JdbcGtfsLoader.INSERT_BATCH_SIZE;
import static com.conveyal.gtfs.util.Util.ensureValidNamespace;

/**
 * This wraps a single database table and provides methods to modify GTFS entities.
 */
public class JdbcTableWriter implements TableWriter {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcTableWriter.class);
    private final DataSource dataSource;
    private final Table specTable;
    private final String tablePrefix;
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Connection connection;

    public JdbcTableWriter(Table table, DataSource datasource, String namespace) throws InvalidNamespaceException {
        this(table, datasource, namespace, null);
    }

    /**
     * Enum containing available methods for updating in SQL.
     */
    private enum SqlMethod {
        DELETE, UPDATE, CREATE
    }

    public JdbcTableWriter(
        Table specTable,
        DataSource dataSource,
        String tablePrefix,
        Connection optionalConnection
    ) throws InvalidNamespaceException {
        // verify tablePrefix (namespace) is ok to use for constructing dynamic sql statements
        ensureValidNamespace(tablePrefix);

        this.tablePrefix = tablePrefix;
        this.dataSource = dataSource;

        // TODO: verify specTable.name is ok to use for constructing dynamic sql statements
        this.specTable = specTable;
        // Below is a bit messy because the connection field on this class is set to final and we cannot set this to
        // the optionally passed-in connection with the ternary statement unless connection1 already exists.
        Connection connection1;
        try {
            connection1 = this.dataSource.getConnection();
        } catch (SQLException e) {
            e.printStackTrace();
            connection1 = null;
        }
        if (optionalConnection != null) {
            DbUtils.closeQuietly(connection1);
        }
        this.connection = optionalConnection == null ? connection1 : optionalConnection;
    }

    /**
     * Wrapper method to call Jackson to deserialize a JSON string into JsonNode.
     */
    private static JsonNode getJsonNode(String json) throws IOException {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            LOG.error("Bad JSON syntax", e);
            throw e;
        }
    }

    /**
     * Create a new entity in the database from the provided JSON string. Note, any call to create or update must provide
     * a JSON string with the full set of fields matching the definition of the GTFS table in the Table class.
     */
    @Override
    public String create(String json, boolean autoCommit) throws SQLException, IOException {
        return update(null, json, autoCommit);
    }

    /**
     * Update entity for a given ID with the provided JSON string. This update and any potential cascading updates to
     * referencing tables all happens in a single transaction. Note, any call to create or update must provide
     * a JSON string with the full set of fields matching the definition of the GTFS table in the Table class.
     */
    @Override
    public String update(Integer id, String json, boolean autoCommit) throws SQLException, IOException {
        final boolean isCreating = id == null;
        JsonNode jsonNode = getJsonNode(json);
        try {
            if (specTable.name.equals("locations")) {
                jsonNode = LocationShape.validate(jsonNode);
            }
            if (jsonNode.isArray()) {
                // If an array of objects is passed in as the JSON input, update them all in a single transaction, only
                // committing once all entities have been updated.
                List<String> updatedObjects = new ArrayList<>();
                for (JsonNode node : jsonNode) {
                    JsonNode idNode = node.get("id");
                    Integer nodeId = idNode == null || isCreating ? null : idNode.asInt();
                    String updatedObject = update(nodeId, node.toString(), false);
                    updatedObjects.add(updatedObject);
                }
                if (autoCommit) connection.commit();
                return mapper.writeValueAsString(updatedObjects);
            }
            // Cast JsonNode to ObjectNode to allow mutations (e.g., updating the ID field).
            ObjectNode jsonObject = (ObjectNode) jsonNode;
            // Ensure that the key field is unique and that referencing tables are updated if the value is updated.
            ensureReferentialIntegrity(jsonObject, tablePrefix, specTable, id);
            // Parse the fields/values into a Field -> String map (drops ALL fields not explicitly listed in spec table's
            // fields)
            // Note, this must follow referential integrity check because some tables will modify the jsonObject (e.g.,
            // adding trip ID if it is null).
//            LOG.info("JSON to {} entity: {}", isCreating ? "create" : "update", jsonObject.toString());
            PreparedStatement preparedStatement = createPreparedUpdate(id, isCreating, jsonObject, specTable, connection, false);
            // ID from create/update result
            long newId = handleStatementExecution(preparedStatement, isCreating);
            // At this point, the transaction was successful (but not yet committed). Now we should handle any update
            // logic that applies to child tables. For example, after saving a trip, we need to store its stop times.
            Set<Table> referencingTables = getReferencingTables(specTable);
            // FIXME: hacky hack hack to add shapes table if we're updating a pattern.
            if (specTable.name.equals("patterns")) {
                referencingTables.add(Table.SHAPES);
            }
            PatternReconciliation reconciliation = new PatternReconciliation(connection, tablePrefix);
            boolean referencedPatternUsesFrequencies = referencedPatternUsesFrequencies(jsonObject);
            // Iterate over referencing (child) tables and update those rows that reference the parent entity with the
            // JSON array for the key that matches the child table's name (e.g., trip.stop_times array will trigger
            // update of stop_times with matching trip_id).
            for (Table referencingTable : referencingTables) {
                Table parentTable = referencingTable.getParentTable();
                if (parentTable != null && parentTable.name.equals(specTable.name) || referencingTable.name.equals("shapes")) {
                    // If a referencing table has the current table as its parent, update child elements.
                    JsonNode childEntities = jsonObject.get(referencingTable.name);
                    if ((referencingTable.name.equals(Table.PATTERN_LOCATION.name) ||
                        referencingTable.name.equals(Table.PATTERN_LOCATION_GROUP.name)) &&
                        (hasNoChildEntities(childEntities))
                    ) {
                        // This is a backwards hack to prevent the addition of pattern location breaking existing
                        // pattern functionality. If pattern location or pattern location group is not provided set to
                        // an empty array to avoid the following exception.
                        childEntities = mapper.createArrayNode();
                    }
                    if (hasNoChildEntities(childEntities)) {
                        throw new SQLException(String.format("Child entities %s must be an array and not null", referencingTable.name));
                    }
                    int entityId = isCreating ? (int) newId : id;
                    // Cast child entities to array node to iterate over.
                    ArrayNode childEntitiesArray = (ArrayNode) childEntities;
                    String keyValue = updateChildTable(
                        childEntitiesArray,
                        entityId,
                        referencedPatternUsesFrequencies,
                        isCreating,
                        referencingTable,
                        connection,
                        reconciliation
                    );
                    // Ensure JSON return object is updated with referencing table's (potentially) new key value.
                    // Currently, the only case where an update occurs is when a referenced shape is referenced by other
                    // patterns.
                    jsonObject.put(referencingTable.getKeyFieldName(), keyValue);
                }
            }

            // Pattern stops and pattern locations are processed in series (as part of updateChildTable). The pattern
            // reconciliation requires both in order to correctly update stop times.
            reconciliation.reconcile();
            if (referencedPatternUsesFrequencies) {
                updatePatternFrequencies(reconciliation);
            }

            // Iterate over table's fields and apply linked values to any tables. This is to account for "exemplar"
            // fields that exist in one place in our tables, but are duplicated in GTFS. For example, we have a
            // Route#wheelchair_accessible field, which is used to set the Trip#wheelchair_accessible values for all
            // trips on a route.
            // NOTE: pattern_stops linked fields are updated in the updateChildTable method.
            switch (specTable.name) {
                case "routes":
                    updateLinkedFields(
                        specTable,
                        jsonObject,
                        "trips",
                        "route_id",
                        "wheelchair_accessible"
                    );
                    break;
                case "patterns":
                    updateLinkedFields(
                        specTable,
                        jsonObject,
                        "trips",
                        "pattern_id",
                        "direction_id", "shape_id"
                    );
                    break;
                default:
                    LOG.debug("No linked fields to update.");
                    // Do nothing.
                    break;
            }
            if (autoCommit) {
                // If nothing failed up to this point, it is safe to assume there were no problems updating/creating the
                // main entity and any of its children, so we commit the transaction.
                LOG.info("Committing transaction.");
                connection.commit();
            }
            // Add new ID to JSON object.
            jsonObject.put("id", newId);
            // FIXME: Should this return the entity freshly queried from the database rather than just updating the ID?
            return jsonObject.toString();
        } catch (Exception e) {
            LOG.error("Error {} {} entity", isCreating ? "creating" : "updating", specTable.name);
            e.printStackTrace();
            throw e;
        } finally {
            if (autoCommit) {
                // Always rollback and close in finally in case of early returns or exceptions.
                connection.rollback();
                connection.close();
            }
        }
    }

    /**
     *  Check if a parent table has no child entities.
     */
    private boolean hasNoChildEntities(JsonNode childEntities) {
        return childEntities == null || childEntities.isNull() || !childEntities.isArray();
    }

    /**
     * If an entity references a pattern (e.g., pattern stop or trip), determine whether the pattern uses
     * frequencies because this impacts update behaviors, for example whether stop times are kept in
     * sync with default travel times or whether frequencies are allowed to be nested with a JSON trip.
     */
    private boolean referencedPatternUsesFrequencies(ObjectNode jsonObject) throws SQLException {
        if (jsonObject.has("pattern_id") && !jsonObject.get("pattern_id").isNull()) {
            try (
                PreparedStatement statement = connection.prepareStatement(
                    String.format(
                        "select use_frequency from %s.%s where pattern_id = ?",
                        tablePrefix,
                        Table.PATTERNS.name
                    )
                )
            ) {
                statement.setString(1, jsonObject.get("pattern_id").asText());
                LOG.info(statement.toString());
                ResultSet selectResults = statement.executeQuery();
                if (selectResults.next()) {
                    return selectResults.getBoolean(1);
                }
            }
        }
        return false;
    }

    /**
     * For a given pattern id and starting stop sequence (inclusive), normalize all stop times to match the pattern
     * stops' travel times.
     *
     * @return number of stop times updated
     */
    public int normalizeStopTimesForPattern(int id, int beginWithSequence) throws SQLException {
        try {
            JDBCTableReader<PatternStop> patternStops = new JDBCTableReader(
                Table.PATTERN_STOP,
                dataSource,
                tablePrefix + ".",
                EntityPopulator.PATTERN_STOP
            );
            JDBCTableReader<PatternLocation> patternLocations = new JDBCTableReader(
                Table.PATTERN_LOCATION,
                dataSource,
                tablePrefix + ".",
                EntityPopulator.PATTERN_LOCATION
            );
            JDBCTableReader<PatternLocationGroup> patternLocationGroups = new JDBCTableReader(
                Table.PATTERN_LOCATION_GROUP,
                dataSource,
                tablePrefix + ".",
                EntityPopulator.PATTERN_LOCATION_GROUP
            );
            String patternId = getValueForId(id, "pattern_id", tablePrefix, Table.PATTERNS, connection);
            List<PatternHalt> patternHaltsToNormalize = new ArrayList<>();
            Iterator<PatternHalt> patternHalts = Iterators.concat(
                patternStops.getOrdered(patternId).iterator(),
                patternLocations.getOrdered(patternId).iterator(),
                patternLocationGroups.getOrdered(patternId).iterator()
            );
            while (patternHalts.hasNext()) {
                PatternHalt patternHalt = patternHalts.next();
                if (patternHalt.stop_sequence >= beginWithSequence) {
                    patternHaltsToNormalize.add(patternHalt);
                }
            }
            // Use PatternHalt superclass to extract shared fields to be able to compare stops and locations
            patternHaltsToNormalize = patternHaltsToNormalize.stream().sorted(Comparator.comparingInt(o -> (o).stop_sequence)).collect(Collectors.toList());
            PatternHalt firstPatternHalt = patternHaltsToNormalize.iterator().next();
            int firstStopSequence = firstPatternHalt.stop_sequence;
            // Prepare SQL query to determine the time that should form the basis for adding the travel time values.
            int previousStopSequence = firstStopSequence > 0 ? firstStopSequence - 1 : 0;
            String timeField = firstStopSequence > 0 ? "departure_time" : "arrival_time";
            String getPrevTravelTimeSql = String.format(
                "select t.trip_id, %s from %s.stop_times st, %s.trips t where stop_sequence = ? " +
                    "and t.pattern_id = ? " +
                    "and t.trip_id = st.trip_id",
                timeField,
                tablePrefix,
                tablePrefix
            );
            PreparedStatement statement = connection.prepareStatement(getPrevTravelTimeSql);
            statement.setInt(1, previousStopSequence);
            statement.setString(2, firstPatternHalt.pattern_id);
            LOG.info(statement.toString());
            ResultSet resultSet = statement.executeQuery();
            Map<String, Integer> timesForTripIds = new HashMap<>();
            while (resultSet.next()) {
                timesForTripIds.put(resultSet.getString(1), resultSet.getInt(2));
            }

            int stopTimesUpdated = 0;
            for (Map.Entry<String, Integer> timesForTripId : timesForTripIds.entrySet()) {
                // Initialize travel time with previous stop time value.
                int cumulativeTravelTime = timesForTripId.getValue();
                for (PatternHalt patternHalt : patternHaltsToNormalize) {
                    if (patternHalt instanceof PatternStop) {
                        cumulativeTravelTime += updateStopTimesForPatternStop(
                            (PatternStop) patternHalt,
                            cumulativeTravelTime,
                            timesForTripId.getKey()
                        );
                    } else if (patternHalt instanceof PatternLocation) {
                        cumulativeTravelTime += updateStopTimes(
                            (PatternLocation) patternHalt,
                            cumulativeTravelTime,
                            timesForTripId.getKey()
                        );
                    } else if (patternHalt instanceof PatternLocationGroup) {
                        cumulativeTravelTime += updateStopTimes(
                            (PatternLocationGroup) patternHalt,
                            cumulativeTravelTime,
                            timesForTripId.getKey()
                        );
                    } else {
                        LOG.warn("Pattern with ID {} contained a halt that wasn't a stop or a location!", patternId);
                        continue;
                    }
                    stopTimesUpdated++;
                }
            }
            connection.commit();
            return stopTimesUpdated;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            DbUtils.closeQuietly(connection);
        }
    }

    /**
     * Updates linked fields with values from entity being updated. This is used to update identical fields in related
     * tables (for now just fields in trips and stop_times) where the reference table's value should take precedence over
     * the related table (e.g., pattern_stop#timepoint should update all of its related stop_times).
     */
    private void updateLinkedFields(
        Table referenceTable,
        ObjectNode exemplarEntity,
        String linkedTableName,
        String keyField,
        String... linkedFieldsToUpdate
    ) throws SQLException {
        boolean updatingStopTimes = "stop_times".equals(linkedTableName);
        // Collect fields, the JSON values for these fields, and the strings to add to the prepared statement into Lists.
        List<Field> fields = new ArrayList<>();
        List<JsonNode> values = new ArrayList<>();
        List<String> fieldStrings = new ArrayList<>();
        for (String field : linkedFieldsToUpdate) {
            fields.add(referenceTable.getFieldForName(field));
            values.add(exemplarEntity.get(field));
            fieldStrings.add(String.format("%s = ?", field));
        }
        String setFields = String.join(", ", fieldStrings);
        // If updating stop_times, use a more complex query that joins trips to stop_times in order to match on pattern_id
        Field orderField = updatingStopTimes ? referenceTable.getFieldForName(referenceTable.getOrderFieldName()) : null;
        String sql = updatingStopTimes
            ? String.format(
            "update %s.stop_times st set %s from %s.trips t " +
                "where st.trip_id = t.trip_id AND t.%s = ? AND st.%s = ?",
            tablePrefix,
            setFields,
            tablePrefix,
            keyField,
            orderField.name
        )
            : String.format("update %s.%s set %s where %s = ?", tablePrefix, linkedTableName, setFields, keyField);
        // Prepare the statement and set statement parameters
        PreparedStatement statement = connection.prepareStatement(sql);
        int oneBasedIndex = 1;
        // Iterate over list of fields that need to be updated and set params.
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            String newValue = values.get(i).isNull() ? null : values.get(i).asText();
            if (newValue == null) field.setNull(statement, oneBasedIndex++);
            else field.setParameter(statement, oneBasedIndex++, newValue);
        }
        // Set "where clause" with value for key field (e.g., set values where pattern_id = '3')
        statement.setString(oneBasedIndex++, exemplarEntity.get(keyField).asText());
        if (updatingStopTimes) {
            // If updating stop times set the order field parameter (stop_sequence)
            String orderValue = exemplarEntity.get(orderField.name).asText();
            orderField.setParameter(statement, oneBasedIndex++, orderValue);
        }
        // Log query, execute statement, and log result.
        LOG.debug(statement.toString());
        int entitiesUpdated = statement.executeUpdate();
        LOG.debug("{} {} linked fields updated", entitiesUpdated, linkedTableName);
    }

    /**
     * Creates a prepared statement for an entity create or update operation. If not performing a batch operation, the
     * method will set parameters for the prepared statement with values found in the provided JSON ObjectNode. The Table
     * object here is provided as a positional argument (rather than provided via the JdbcTableWriter instance field)
     * because this method is used to update both the specTable for the primary entity and any relevant child entities.
     */
    private PreparedStatement createPreparedUpdate(
        Integer id,
        boolean isCreating,
        ObjectNode jsonObject,
        Table table,
        Connection connection,
        boolean batch
    ) throws SQLException {
        String statementString;
        if (isCreating) {
            statementString = table.generateInsertSql(tablePrefix, true);
        } else {
            statementString = table.generateUpdateSql(tablePrefix, id);
        }
        // Set the RETURN_GENERATED_KEYS flag on the PreparedStatement because it may be creating new rows, in which
        // case we need to know the auto-generated IDs of those new rows.
        PreparedStatement preparedStatement = connection.prepareStatement(
            statementString,
            Statement.RETURN_GENERATED_KEYS);
        if (!batch) {
            setStatementParameters(jsonObject, table, preparedStatement, connection);
        }
        return preparedStatement;
    }

    /**
     * Given a prepared statement (for update or create), set the parameters of the statement based on string values
     * taken from JSON. Note, string values are used here in order to take advantage of setParameter method on
     * individual fields, which handles parsing string and non-string values into the appropriate SQL field types.
     */
    private void setStatementParameters(
        ObjectNode jsonObject,
        Table table,
        PreparedStatement preparedStatement,
        Connection connection
    ) throws SQLException {
        // JDBC SQL statements use a one-based index for setting fields/parameters
        List<String> missingFieldNames = new ArrayList<>();
        // One-based index for prepared statement.
        int index = 1;
        for (Field field : table.editorFields()) {
            if (!jsonObject.has(field.name)) {
                // If there is a field missing from the JSON string and it is required to write to an editor table,
                // throw an exception (handled after the fields iteration. In an effort to keep the database integrity
                // intact, every update/create operation should have all fields defined by the spec table.
                // FIXME: What if someone wants to make updates to non-editor feeds? In this case, the table may not
                // have all of the required fields, yet this would prohibit such an update. Further, an update on such
                // a table that DID have all of the spec table fields would fail because they might be missing from
                // the actual database table.
                missingFieldNames.add(field.name);
                continue;
            }
            JsonNode value = jsonObject.get(field.name);
            LOG.debug("{}={}", field.name, value);
            try {
                if (value == null || value.isNull()) {
                    // If there is a required field missing from the JSON object, an exception will be thrown below.
                    if (field.isRequired() && !field.isEmptyValuePermitted()) {
                        // Only register the field as missing if the value is null, the field is required, and empty
                        // values are not permitted. For example, a null value for fare_attributes#transfers should not
                        // trigger a missing field exception.
                        missingFieldNames.add(field.name);
                        continue;
                    }
                    // Set value to null if empty value is OK and update JSON.
                    setFieldToNullAndUpdateJson(preparedStatement, jsonObject, field, index);
                } else {
                    // For fields that are not missing, handle setting different field types.
                    if (value.isArray()) {
                        // Array field type expects comma-delimited values.
                        List<String> values = new ArrayList<>();
                        for (JsonNode node : value) {
                            values.add(node.asText());
                        }
                        field.setParameter(preparedStatement, index, String.join(",", values));
                    } else {
                        String text = value.asText();
                        // If the string is empty, set value to null (for StringField, ShortField, etc.). Otherwise, set
                        // parameter with string value.
                        if (text.isEmpty()) {
                            // Set field to null and update JSON.
                            setFieldToNullAndUpdateJson(preparedStatement, jsonObject, field, index);
                        } else {
                            field.setParameter(preparedStatement, index, text);
                        }
                    }
                }
            } catch (StorageException e) {
                LOG.warn("Could not set field {} to value {}. Attempting to parse integer seconds.", field.name, value);
                if (field.name.contains("_time") ||
                    field.name.contains("start_pickup_dropoff_window") ||
                    field.name.contains("end_pickup_dropoff_window")
                ) {
                    // FIXME: This is a hack to get time related fields into the right format. Because the UI
                    //  currently returns them as seconds since midnight rather than the Field-defined format HH:MM:SS
                    //  and where optional GTFS Flex fields are not defined i.e. the default value of zero is used.
                    try {
                        if (value == null || value.isNull()) {
                            if (field.isRequired()) {
                                missingFieldNames.add(field.name);
                                continue;
                            }
                            field.setNull(preparedStatement, index);
                        } else {
                            // Try to parse integer seconds value
                            preparedStatement.setInt(index, Integer.parseInt(value.asText()));
                            LOG.info("Parsing value {} for field {} successful!", value, field.name);
                        }
                    } catch (NumberFormatException ex) {
                        // Attempt to set arrival or departure time via integer seconds failed. Rollback.
                        connection.rollback();
                        LOG.error("Bad column: {}={}", field.name, value);
                        ex.printStackTrace();
                        throw ex;
                    }
                } else {
                    // Rollback transaction and throw exception
                    connection.rollback();
                    throw e;
                }
            }
            // Increment index for next field.
            index += 1;
        }
        if (missingFieldNames.size() > 0) {
            throw new SQLException(
                String.format(
                    "The following field(s) are missing from JSON %s object: %s",
                    table.name,
                    missingFieldNames.toString()
                )
            );
        }
    }

    /**
     * Set field to null in prepared statement and update JSON object that is ultimately returned (see return value of
     * {@link #update}.) in response to reflect actual database value that will be persisted. This method should be
     * used in cases where the jsonObject value is missing or detected to be an empty string.
     */
    private static void setFieldToNullAndUpdateJson(
        PreparedStatement preparedStatement,
        ObjectNode jsonObject,
        Field field,
        int oneBasedIndex
    ) throws SQLException {
        // Update the jsonObject so that the JSON that gets returned correctly reflects persisted value.
        jsonObject.set(field.name, null);
        // Set field to null in prepared statement.
        field.setNull(preparedStatement, oneBasedIndex);
    }

    /**
     * This updates those tables that depend on the table currently being updated. For example, if updating/creating a
     * pattern, this method handles deleting any pattern stops and shape points. For trips, this would handle updating
     * the trips' stop times.
     *
     * This method should only be used on tables that have a single foreign key reference to another table, i.e., they
     * have a hierarchical relationship.
     * FIXME develop a better way to update tables with foreign keys to the table being updated.
     */
    private String updateChildTable(
        ArrayNode subEntities,
        int id,
        boolean referencedPatternUsesFrequencies,
        boolean isCreatingNewEntity,
        Table subTable,
        Connection connection,
        PatternReconciliation reconciliation
    ) throws SQLException, IOException {
        // Get parent table's key field. Primary key fields are always referenced by foreign key fields with the same
        // name.
        Field keyField = specTable.getFieldForName(subTable.getKeyFieldName());
        // Get parent entity's key value
        String keyValue = getValueForId(id, keyField.name, tablePrefix, specTable, connection);
        String childTableName = String.join(".", tablePrefix, subTable.name);
        if (!referencedPatternUsesFrequencies && subTable.name.equals(Table.FREQUENCIES.name) && subEntities.size() > 0) {
            // Do not permit the illegal state where frequency entries are being added/modified for a timetable pattern.
            throw new IllegalStateException("Cannot create or update frequency entries for a timetable-based pattern.");
        }
        boolean isPatternTable =
                Table.PATTERN_STOP.name.equals(subTable.name) ||
                Table.PATTERN_LOCATION.name.equals(subTable.name) ||
                Table.PATTERN_LOCATION_GROUP.name.equals(subTable.name);
        if (isPatternTable) {
            reconciliation.stage(mapper, subTable, subEntities, keyValue);
        }
        if (!isCreatingNewEntity) {
            // If not creating a new entity, we will delete the child entities (e.g., shape points or pattern stops) and
            // regenerate them anew to avoid any messiness that we may encounter with update statements.
            if (Table.SHAPES.name.equals(subTable.name)) {
                // Check how many patterns are referencing the same shape_id to determine if we should copy on update.
                String patternsForShapeIdSql = String.format("select id from %s.patterns where shape_id = ?", tablePrefix);
                PreparedStatement statement = connection.prepareStatement(patternsForShapeIdSql);
                statement.setString(1, keyValue);
                LOG.info(statement.toString());
                ResultSet resultSet = statement.executeQuery();
                int patternsForShapeId = 0;
                while (resultSet.next()) {
                    patternsForShapeId++;
                }
                if (patternsForShapeId > 1) {
                    // Use copy on update for pattern shape if a single shape is being used for multiple patterns because
                    // we do not want edits for a given pattern (for example, a short run) to impact the shape for another
                    // pattern (perhaps a long run that extends farther). Note: this behavior will have the side effect of
                    // creating potentially redundant shape information, but this is better than accidentally butchering the
                    // shapes for other patterns.
                    LOG.info("More than one pattern references shape_id: {}", keyValue);
                    keyValue = UUID.randomUUID().toString();
                    LOG.info("Creating new shape_id ({}) for pattern id={}.", keyValue, id);
                    // Update pattern#shape_id with new value. Note: shape_point#shape_id values are coerced to new
                    // value further down in this function.
                    String updatePatternShapeIdSql = String.format("update %s.patterns set shape_id = ? where id = ?", tablePrefix);
                    PreparedStatement updateStatement = connection.prepareStatement(updatePatternShapeIdSql);
                    updateStatement.setString(1, keyValue);
                    updateStatement.setInt(2, id);
                    LOG.info(updateStatement.toString());
                    updateStatement.executeUpdate();
                } else {
                    // If only one pattern references this shape, delete the existing shape points to start anew.
                    deleteChildEntities(subTable, keyField, keyValue);
                }
            } else {
                // If not handling shape points, delete the child entities and create them anew.
                deleteChildEntities(subTable, keyField, keyValue);
            }
        }
        int entityCount = 0;
        PreparedStatement insertStatement = null;
        // Iterate over the entities found in the array and add to batch for inserting into table.
        String orderFieldName = subTable.getOrderFieldName();
        boolean hasOrderField = orderFieldName != null;
        int previousOrder = -1;
        TIntSet orderValues = new TIntHashSet();
        Multimap<Table, Multimap<Table, String>> foreignReferencesPerTable = HashMultimap.create();
        Multimap<Table, String> referencesPerTable = HashMultimap.create();
        int cumulativeTravelTime = 0;
        for (JsonNode entityNode : subEntities) {
            // Cast entity node to ObjectNode to allow mutations (JsonNode is immutable).
            ObjectNode subEntity = (ObjectNode) entityNode;
            // Always override the key field (shape_id for shapes, pattern_id for patterns) regardless of the entity's
            // actual value.
            subEntity.put(keyField.name, keyValue);

            checkTableReferences(foreignReferencesPerTable, referencesPerTable, specTable, subTable, subEntity);

            // Insert new sub-entity.
            if (entityCount == 0) {
                // If handling first iteration, create the prepared statement (later iterations will add to batch).
                insertStatement = createPreparedUpdate(id, true, subEntity, subTable, connection, true);
            }
            if (isPatternTable) {
                // Update linked stop times fields for each updated pattern stop (e.g., timepoint, pickup/drop off type).
                // These fields should be updated for all patterns (e.g., timepoint, pickup/drop off type).
                if (Table.PATTERN_STOP.name.equals(subTable.name)) {
                    updateLinkedFields(
                        subTable,
                        subEntity,
                        "stop_times",
                        "pattern_id",
                        "timepoint",
                        "drop_off_type",
                        "pickup_type",
                        "continuous_pickup",
                        "continuous_drop_off",
                        "shape_dist_traveled",
                        "pickup_booking_rule_id",
                        "drop_off_booking_rule_id"
                    );
                }
                if (Table.PATTERN_LOCATION.name.equals(subTable.name) ||
                    Table.PATTERN_LOCATION_GROUP.name.equals(subTable.name)
                ) {
                    updateLinkedFields(
                        subTable,
                        subEntity,
                        "stop_times",
                        "pattern_id",
                        "timepoint",
                        "drop_off_type",
                        "pickup_type",
                        "continuous_pickup",
                        "continuous_drop_off",
                        "pickup_booking_rule_id",
                        "drop_off_booking_rule_id"
                    );
                }
            }
            setStatementParameters(subEntity, subTable, insertStatement, connection);
            if (hasOrderField) {
                // If the table has an order field, check that it is zero-based and incrementing for all sub entities.
                // NOTE: Rather than coercing the order values to conform to the sequence in which they are found, we
                // check the values here as a sanity check.
                int orderValue = subEntity.get(orderFieldName).asInt();
                boolean orderIsUnique = orderValues.add(orderValue);
                boolean valuesAreIncrementing = ++previousOrder == orderValue;
                boolean valuesAreIncreasing = previousOrder <= orderValue;

                // Patterns must only increase, not increment.
                boolean valuesAreAscending = !isPatternTable ? valuesAreIncrementing : valuesAreIncreasing;
                if (!orderIsUnique || !valuesAreAscending) {
                    throw new SQLException(
                        String.format(
                            "%s %s values must be zero-based, unique, and incrementing. PatternHalt values must be increasing and unique only. Entity at index %d had %s illegal value of %d",
                            subTable.name,
                            orderFieldName,
                            entityCount,
                            previousOrder == 0 ? "non-zero" : !valuesAreAscending ? "non-incrementing/non-increasing" : "duplicate",
                            orderValue
                        )
                    );
                }
            }
            // Log statement on first iteration so that it is not logged for each item in the batch.
            if (entityCount == 0) LOG.info(insertStatement.toString());
            insertStatement.addBatch();
            // Prefix increment count and check whether to execute batched update.
            if (++entityCount % INSERT_BATCH_SIZE == 0) {
                LOG.info("Executing batch insert ({}/{}) for {}", entityCount, subEntities.size(), childTableName);
                int[] newIds = insertStatement.executeBatch();
                LOG.info("Updated {}", newIds.length);
            }
        }
        // Check that accumulated references all exist in reference tables.
        verifyReferencesExist(subTable.name, referencesPerTable);
        verifyForeignReferencesExist(foreignReferencesPerTable);
        // execute any remaining prepared statement calls
        LOG.info("Executing batch insert ({}/{}) for {}", entityCount, subEntities.size(), childTableName);
        if (insertStatement != null) {
            // If insert statement is null, an empty array was passed for the child table, so the child elements have
            // been wiped.
            int[] newIds = insertStatement.executeBatch();
            LOG.info("Updated {} {} child entities", newIds.length, subTable.name);
        } else {
            LOG.info("No inserts to execute. Empty array found in JSON for child table {}", childTableName);
        }
        // Return key value in the case that it was updated (the only case for this would be if the shape was referenced
        // by multiple patterns).
        return keyValue;
    }

    /**
     * Check any references the sub entity might have. For example, this checks that stop_id values on
     * pattern_stops refer to entities that actually exist in the stops table. NOTE: This skips the "specTable",
     * i.e., for pattern stops it will not check pattern_id references. This is enforced above with the put key
     * field statement above.
     */
    private void checkTableReferences(
        Multimap<Table, Multimap<Table, String>> foreignReferencesPerTable,
        Multimap<Table, String> referencesPerTable,
        Table specTable,
        Table subTable,
        ObjectNode subEntity
    ) {
        for (Field field : subTable.specFields()) {
            if (field.referenceTables.isEmpty()) continue;
            Multimap<Table, String> foreignReferences = HashMultimap.create();
            for (Table referenceTable : field.referenceTables) {
                if (!referenceTable.name.equals(specTable.name)) {
                    JsonNode refValueNode = subEntity.get(field.name);
                    // Skip over references that are null but not required (e.g., route_id in fare_rules).
                    if (refValueNode.isNull() && !field.isRequired()) continue;
                    String refValue = refValueNode.asText();
                    if (field.referenceTables.size() == 1) {
                        referencesPerTable.put(referenceTable, refValue);
                    } else {
                        foreignReferences.put(referenceTable, refValue);
                    }
                }
            }
            if (!foreignReferences.isEmpty()) {
                foreignReferencesPerTable.put(subTable, foreignReferences);
            }
        }
    }

    /**
     * This MUST be called _after_ pattern reconciliation has happened. The pattern stops and pattern locations must be
     * processed based on stop sequence so the correct cumulative travel time is calculated.
     */
    private void updatePatternFrequencies(PatternReconciliation reconciliation) throws SQLException {
        // Convert to generic stops to order pattern stops/locations by stop sequence.
        List<PatternReconciliation.GenericStop> genericStops = reconciliation.getGenericStops();
        int cumulativeTravelTime = 0;
        for (PatternReconciliation.GenericStop genericStop : genericStops) {
            // Update stop times linked to pattern stop/location and accumulate time.
            // Default travel and dwell time behave as "linked fields" for associated stop times. In other
            // words, frequency trips in the editor must match the pattern stop travel times.
            if (genericStop.patternType == PatternReconciliation.PatternType.STOP) {
                cumulativeTravelTime +=
                    updateStopTimesForPatternStop(
                        reconciliation.getPatternStop(genericStop.referenceId),
                        cumulativeTravelTime,
                        null
                    );
            } else if (genericStop.patternType == PatternReconciliation.PatternType.LOCATION) {
                cumulativeTravelTime +=
                    updateStopTimes(
                        reconciliation.getPatternLocation(genericStop.referenceId),
                        cumulativeTravelTime,
                        null
                    );
            } else {
                // Pattern type is location group
                cumulativeTravelTime +=
                    updateStopTimes(
                        reconciliation.getPatternLocationGroup(genericStop.referenceId),
                        cumulativeTravelTime,
                        null
                    );
            }
        }
    }

    /**
     * Delete existing sub-entities for given key value for when an update to the parent entity is made (i.e., the parent
     * entity is not being newly created). Examples of sub-entities include stop times for trips, pattern stops for a
     * pattern, or shape points (for a pattern in our model).
     */
    private void deleteChildEntities(Table childTable, Field keyField, String keyValue) throws SQLException {
        String childTableName = String.join(".", tablePrefix, childTable.name);
        PreparedStatement deleteStatement = getUpdateReferencesStatement(SqlMethod.DELETE, childTableName, keyField, keyValue, null);
        LOG.info(deleteStatement.toString());
        int result = deleteStatement.executeUpdate();
        LOG.info("Deleted {} {}", result, childTable.name);
        // FIXME: are there cases when an update should not return zero?
        //   if (result == 0) throw new SQLException("No stop times found for trip ID");
    }

    /**
     * Updates the stop times that reference the specified pattern stop.
     *
     * @param patternStop        the pattern stop for which to update stop times
     * @param previousTravelTime the travel time accumulated up to the previous stop_time's departure time (or the
     *                           previous pattern stop's dwell time)
     * @return the travel and dwell time added by this pattern stop
     * @throws SQLException
     */
    private int updateStopTimesForPatternStop(PatternStop patternStop, int previousTravelTime, String tripId)
        throws SQLException {

        int travelTime = patternStop.default_travel_time == Entity.INT_MISSING ? 0 : patternStop.default_travel_time;
        int dwellTime = patternStop.default_dwell_time == Entity.INT_MISSING ? 0 : patternStop.default_dwell_time;
        // Set trip id either from params or all
        tripId = (tripId != null) ? String.format("'%s'", tripId) : "t.trip_id";

        String sql = String.format(
            "update %s.stop_times st set arrival_time = ?, departure_time = ? from %s.trips t " +
                "where st.trip_id = %s AND t.pattern_id = ? AND st.stop_sequence = ?",
            tablePrefix,
            tablePrefix,
            tripId
        );
        int entitiesUpdated = updateStopTimes(
            sql,
            previousTravelTime,
            travelTime,
            dwellTime,
            patternStop.pattern_id,
            patternStop.stop_sequence
        );
        LOG.info("{} stop_time arrivals/departures updated", entitiesUpdated);
        return travelTime + dwellTime;
    }

    /**
     * Updates the stop times that reference the specified pattern location.
     *
     * @return the travel and dwell time added by this pattern.
     * @throws SQLException
     */
    private int updateStopTimes(
        PatternLocation patternLocation,
        int previousTravelTime,
        String tripId
    ) throws SQLException {
        return updateStopTimesForPatternLocationOrPatternLocationGroup(
            patternLocation.flex_default_travel_time,
            patternLocation.flex_default_zone_time,
            patternLocation.pattern_id,
            patternLocation.stop_sequence,
            previousTravelTime,
            tripId);
    }

    /**
     * Updates the stop times that reference the specified pattern location group.
     *
     * @return the travel and dwell time added by this pattern.
     * @throws SQLException
     */
    private int updateStopTimes(
        PatternLocationGroup patternLocationGroup,
        int previousTravelTime,
        String tripId
    ) throws SQLException {
        return updateStopTimesForPatternLocationOrPatternLocationGroup(
            patternLocationGroup.flex_default_travel_time,
            patternLocationGroup.flex_default_zone_time,
            patternLocationGroup.pattern_id,
            patternLocationGroup.stop_sequence,
            previousTravelTime,
            tripId);
    }

    /**
     * Updates the stop times that reference the specified pattern location or pattern location group.
     *
     * @return the travel and dwell time added by this pattern.
     * @throws SQLException
     */
    private int updateStopTimesForPatternLocationOrPatternLocationGroup(
        int flexDefaultTravelTime,
        int flexDefaultZoneTime,
        String patternId,
        int stopSequence,
        int previousTravelTime,
        String tripId
    ) throws SQLException {

        int travelTime = flexDefaultTravelTime == Entity.INT_MISSING ? 0 : flexDefaultTravelTime;
        int dwellTime = flexDefaultZoneTime == Entity.INT_MISSING ? 0 : flexDefaultZoneTime;
        // Set trip id either from params or all
        tripId = (tripId != null) ? String.format("'%s'", tripId) : "t.trip_id";

        String sql = String.format(
            "update %s.stop_times st set start_pickup_dropoff_window = ?, end_pickup_dropoff_window = ? from %s.trips t " +
                "where st.trip_id = %s AND t.pattern_id = ? AND st.stop_sequence = ?",
            tablePrefix,
            tablePrefix,
            tripId
        );
        int entitiesUpdated = updateStopTimes(
            sql,
            previousTravelTime,
            travelTime,
            dwellTime,
            patternId,
            stopSequence
        );
        LOG.info("{} stop_time flex service arrivals/departures updated", entitiesUpdated);
        return travelTime + dwellTime;
    }

    /**
     * Update stop time values depending on caller. If updating stop times for pattern stops, this will update the
     * arrival_time and departure_time. If updating stop times for pattern locations, this will update the
     * start_pickup_dropoff_window and end_pickup_dropoff_window.
     */
    private int updateStopTimes(
        String sql,
        int previousTravelTime,
        int travelTime,
        int dwellTime,
        String pattern_id,
        int stop_sequence
    ) throws SQLException {

        PreparedStatement statement = connection.prepareStatement(sql);
        int oneBasedIndex = 1;
        int arrivalTime = previousTravelTime + travelTime;
        statement.setInt(oneBasedIndex++, arrivalTime);
        statement.setInt(oneBasedIndex++, arrivalTime + dwellTime);

        // Set "where clause" with value for pattern_id and stop_sequence
        statement.setString(oneBasedIndex++, pattern_id);
        // In the editor, we can depend on stop_times#stop_sequence matching pattern_stop/pattern_locations#stop_sequence
        // because we normalize stop sequence values for stop times during snapshotting for the editor.
        statement.setInt(oneBasedIndex, stop_sequence);
        // Log query, execute statement, and log result.
        LOG.info(statement.toString());
        return statement.executeUpdate();
    }

    /**
     * Checks that a set of string references to a set of reference tables are all valid. For each set of references
     * mapped to a reference table, the method queries for all of the references. If there are any references that were
     * not returned in the query, one of the original references was invalid and an exception is thrown.
     *
     * @param referringTableName name of the table which contains references for logging/exception message only
     * @param referencesPerTable string references mapped to the tables to which they refer
     * @throws SQLException
     */
    private void verifyReferencesExist(String referringTableName, Multimap<Table, String> referencesPerTable) throws SQLException {
        for (Table referencedTable : referencesPerTable.keySet()) {
            LOG.info("Checking {} references to {}", referringTableName, referencedTable.name);
            Collection<String> referenceStrings = referencesPerTable.get(referencedTable);
            String referenceFieldName = referencedTable.getKeyFieldName();
            Set<String> foundReferences = checkTableForReferences(referenceStrings, referencedTable);
            // Determine if any references were not found.
            referenceStrings.removeAll(foundReferences);
            if (referenceStrings.size() > 0) {
                throw new SQLException(
                    String.format(
                        "%s entities must contain valid %s references. (Invalid references: %s)",
                        referringTableName,
                        referenceFieldName,
                        String.join(", ", referenceStrings)));
            } else {
                LOG.info("All {} {} {} references are valid.", foundReferences.size(), referencedTable.name, referenceFieldName);
            }
        }
    }

    /**
     * Check multiple tables for foreign references. Working through each foreign table check for expected references.
     * Update a missing reference list by adding missing references and remove all that have been found. If the missing
     * reference list is not empty after all reference tables have been checked, flag an error highlighting the missing
     * values and the foreign tables where they are expected.
     *
     * E.g. The {@link StopTime#stop_id} can be either a {@link Stop#stop_id} or a {@link Location#location_id}. If the
     * stop_id is found in the location table (but not the stop table) the required number of matches has been met. If
     * the stop_id isn't in either table there will be no match. It is not possible to know which table the
     * stop_id should be in so all foreign tables are listed with expected values.
     *
     * @param foreignReferencesPerTable A list of parent tables with a related list of foreign tables with reference
     *                                  values.
     * @throws SQLException
     */
    private void verifyForeignReferencesExist(Multimap<Table, Multimap<Table, String>> foreignReferencesPerTable)
        throws SQLException {

        for (Table parentTable : foreignReferencesPerTable.keySet()) {
            Collection<Multimap<Table, String>> multiTableReferences = foreignReferencesPerTable.get(parentTable);
            HashMap<Table, List<String>> refTables = new HashMap<>();
            // Group foreign tables and references.
            for (Multimap<Table, String> tableReference : multiTableReferences) {
                for (Table foreignTable : tableReference.keySet()) {
                    List<String> values = new ArrayList<>();
                    if (refTables.containsKey(foreignTable)) {
                        values = refTables.get(foreignTable);
                    }
                    values.addAll(tableReference.get(foreignTable));
                    refTables.put(foreignTable, values);
                }
            }

            Set<String> foreignReferencesFound = new HashSet<>();
            Set<String> foreignReferencesNotFound = new HashSet<>();
            Set<String> foreignReferencesFieldNames = new HashSet<>();
            for (Table foreignTable : refTables.keySet()) {
                LOG.info("Checking {} references in {}", parentTable.name, foreignTable.name);
                foreignReferencesFieldNames.add(foreignTable.getKeyFieldName());
                Collection<String> referenceStrings = refTables.get(foreignTable);
                Set<String> foundReferences = checkTableForReferences(referenceStrings, foreignTable);
                if (foundReferences.size() == multiTableReferences.size()) {
                    // No need to check subsequent foreign tables if all required matches have been found.
                    foreignReferencesNotFound.clear();
                    break;
                } else {
                    // Accumulate all found and expected references.
                    foreignReferencesFound.addAll(foundReferences);
                    foreignReferencesNotFound.addAll(referenceStrings);
                }
            }
            foreignReferencesNotFound.removeAll(foreignReferencesFound);
            if (foreignReferencesNotFound.size() > 0) {
                throw new SQLException(
                    String.format(
                        "%s entities must contain valid %s references. (Invalid references: %s)",
                        parentTable.name,
                        String.join("/", foreignReferencesFieldNames),
                        String.join(", ", foreignReferencesNotFound)));
            } else {
                LOG.info("All {} foreign references ({}) are valid.",
                    String.join("/", foreignReferencesFieldNames),
                    parentTable.name
                );
            }
        }
    }

    /**
     * Checks a table's key field for matching reference values and returns all matches.
     */
    private Set<String> checkTableForReferences(Collection<String> referenceStrings, Table table)
        throws SQLException {

        String referenceFieldName = table.getKeyFieldName();
        String questionMarks = String.join(", ", Collections.nCopies(referenceStrings.size(), "?"));
        String checkCountSql = String.format(
            "select %s from %s.%s where %s in (%s)",
            referenceFieldName,
            tablePrefix,
            table.name,
            referenceFieldName,
            questionMarks
        );
        PreparedStatement preparedStatement = connection.prepareStatement(checkCountSql);
        int oneBasedIndex = 1;
        for (String ref : referenceStrings) {
            preparedStatement.setString(oneBasedIndex++, ref);
        }
        LOG.info(preparedStatement.toString());
        ResultSet resultSet = preparedStatement.executeQuery();
        Set<String> foundReferences = new HashSet<>();
        while (resultSet.next()) {
            String referenceValue = resultSet.getString(1);
            foundReferences.add(referenceValue);
        }
        return foundReferences;
    }

    /**
     * For a given condition (fieldName = 'value'), delete all entities that match the condition. Because this uses the
     * {@link #delete(Integer, boolean)} method, it also will delete any "child" entities that reference any entities
     * matching the original query.
     */
    @Override
    public int deleteWhere(String fieldName, String value, boolean autoCommit) throws SQLException {
        try {
            String tableName = String.join(".", tablePrefix, specTable.name);
            // Get the IDs for entities matching the where condition
            TIntSet idsToDelete = getIdsForCondition(tableName, fieldName, value, connection);
            TIntIterator iterator = idsToDelete.iterator();
            TIntList results = new TIntArrayList();
            while (iterator.hasNext()) {
                // For all entity IDs that match query, delete from referencing tables.
                int id = iterator.next();
                // FIXME: Should this be a where in clause instead of iterating over IDs?
                // Delete each entity and its referencing (child) entities
                int result = delete(id, false);
                if (result != 1) {
                    throw new SQLException("Could not delete entity with ID " + id);
                }
                results.add(result);
            }
            if (autoCommit) connection.commit();
            LOG.info("Deleted {} {} entities", results.size(), specTable.name);
            return results.size();
        } catch (Exception e) {
            // Rollback changes on failure.
            connection.rollback();
            LOG.error("Could not delete {} entity where {}={}", specTable.name, fieldName, value);
            e.printStackTrace();
            throw e;
        } finally {
            if (autoCommit) {
                // Always close connection if auto-committing.
                connection.close();
            }
        }
    }

    /**
     * Deletes an entity for the specified ID.
     */
    @Override
    public int delete(Integer id, boolean autoCommit) throws SQLException {
        try {
            // Handle "cascading" delete or constraints on deleting entities that other entities depend on
            // (e.g., keep a calendar from being deleted if trips reference it).
            // FIXME: actually add "cascading"? Currently, it just deletes one level down.
            deleteFromReferencingTables(tablePrefix, specTable, id);
            // Next, delete the actual record specified by id.
            PreparedStatement statement = connection.prepareStatement(specTable.generateDeleteSql(tablePrefix));
            statement.setInt(1, id);
            LOG.info(statement.toString());
            // Execute query
            int result = statement.executeUpdate();
            if (result == 0) {
                LOG.error("Could not delete {} entity with id: {}", specTable.name, id);
                throw new SQLException("Could not delete entity");
            }
            if (autoCommit) connection.commit();
            // FIXME: change return message based on result value
            return result;
        } catch (Exception e) {
            LOG.error("Could not delete {} entity with id: {}", specTable.name, id);
            e.printStackTrace();
            // Rollback changes if errors encountered.
            connection.rollback();
            throw e;
        } finally {
            // Always close connection if auto-committing. Otherwise, leave open (for potential further updates).
            if (autoCommit) connection.close();
        }
    }

    @Override
    public void commit() throws SQLException {
        // FIXME: should this take a connection and commit it?
        connection.commit();
        connection.close();
    }

    /**
     * Ensure that database connection closes. This should be called once the table writer is no longer needed.
     */
    @Override
    public void close() {
        DbUtils.closeQuietly(connection);
    }

    /**
     * Delete entities from any referencing tables (if required). This method is defined for convenience and clarity, but
     * essentially just runs updateReferencingTables with a null value for newKeyValue param.
     */
    private void deleteFromReferencingTables(String namespace, Table table, int id) throws SQLException {
        updateReferencingTables(namespace, table, id, null);
    }

    /**
     * Handle executing a prepared statement and return the ID for the newly-generated or updated entity.
     */
    private static long handleStatementExecution(PreparedStatement statement, boolean isCreating) throws SQLException {
        // Log the SQL for the prepared statement
        LOG.info(statement.toString());
        int affectedRows = statement.executeUpdate();
        // Determine operation-specific action for any error messages
        String messageAction = isCreating ? "Creating" : "Updating";
        if (affectedRows == 0) {
            // No update occurred.
            // TODO: add some clarity on cause (e.g., where clause found no entity with provided ID)?
            throw new SQLException(messageAction + " entity failed, no rows affected.");
        }
        try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
            if (generatedKeys.next()) {
                // Get the auto-generated ID from the update execution
                return generatedKeys.getLong(1);
            } else {
                throw new SQLException(messageAction + " entity failed, no ID obtained.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Checks for modification of GTFS key field (e.g., stop_id, route_id) in supplied JSON object and ensures
     * both uniqueness and that referencing tables are appropriately updated.
     *
     * FIXME: add more detail/precise language on what this method actually does
     */
    private void ensureReferentialIntegrity(
        ObjectNode jsonObject,
        String namespace,
        Table table,
        Integer id
    ) throws SQLException {
        final boolean isCreating = id == null;
        String keyField = table.getKeyFieldName();
        String tableName = String.join(".", namespace, table.name);
        JsonNode val = jsonObject.get(keyField);
        if (val == null || val.isNull() || val.asText().isEmpty()) {
            // Handle different cases where the value is missing.
            if ("trip_id".equals(keyField)) {
                // Generate key field automatically for certain entities (e.g., trip ID).
                // FIXME: Maybe this should be generated for all entities if null?
                jsonObject.put(keyField, UUID.randomUUID().toString());
            } else if ("agency_id".equals(keyField)) {
                // agency_id is not required if there is only one row in the agency table. Otherwise, it is required.
                LOG.warn("agency_id field for agency id={} is null.", id);
                int rowSize = getRowCount(tableName, connection);
                if (rowSize > 1 || (isCreating && rowSize > 0)) {
                    throw new SQLException("agency_id must not be null if more than one agency exists.");
                }
            } else {
                // In all other cases where a key field is missing, throw an exception.
                throw new SQLException(String.format("Key field %s must not be null", keyField));
            }
        }
        // Re-get the string key value (in case trip_id was added to the JSON object above).
        String keyValue = jsonObject.get(keyField).asText();
        // If updating key field, check that there is no ID conflict on value (e.g., stop_id or route_id)
        TIntSet uniqueIds = getIdsForCondition(tableName, keyField, keyValue, connection);
        int size = uniqueIds.size();
        if (size == 0 || (size == 1 && id != null && uniqueIds.contains(id))) {
            // OK.
            if (size == 0 && !isCreating) {
                // FIXME: Need to update referencing tables because entity has changed ID.
                // Entity key value is being changed to an entirely new one.  If there are entities that
                // reference this value, we need to update them.
                updateReferencingTables(namespace, table, id, keyValue);
            }
        } else {
            // Conflict. The different conflict conditions are outlined below.
            if (size == 1) {
                // There was one match found.
                if (isCreating) {
                    // Under no circumstance should a new entity have a conflict with existing key field.
                    throw new SQLException(
                        String.format("New %s's %s value (%s) conflicts with an existing record in table.",
                            table.entityClass.getSimpleName(),
                            keyField,
                            keyValue)
                    );
                }
                if (!uniqueIds.contains(id)) {
                    // There are two circumstances we could encounter here.
                    // 1. The key value for this entity has been updated to match some other entity's key value (conflict).
                    // 2. The int ID provided in the request parameter does not match any rows in the table.
                    throw new SQLException("Key field must be unique and request parameter ID must exist.");
                }
            } else if (size > 1) {
                // FIXME: Handle edge case where original data set contains duplicate values for key field and this is an
                // attempt to rectify bad data.
                String message = String.format(
                    "%d %s entities shares the same key field (%s=%s)! Key field must be unique.",
                    size,
                    table.name,
                    keyField,
                    keyValue);
                LOG.error(message);
                throw new SQLException(message);
            }
        }
    }

    /**
     * Get number of rows for a table. This is currently just used to check the number of entities for the agency table.
     */
    private static int getRowCount(String tableName, Connection connection) throws SQLException {
        String rowCountSql = String.format("SELECT COUNT(*) FROM %s", tableName);
        LOG.info(rowCountSql);
        // Create statement for counting rows selected
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(rowCountSql);
        if (resultSet.next()) return resultSet.getInt(1);
        else return 0;
    }

    /**
     * For some condition (where field = string value), return the set of unique int IDs for the records that match.
     */
    private static TIntSet getIdsForCondition(
        String tableName,
        String keyField,
        String keyValue,
        Connection connection
    ) throws SQLException {
        String idCheckSql = String.format("select id from %s where %s = ?", tableName, keyField);
        // Create statement for counting rows selected
        PreparedStatement statement = connection.prepareStatement(idCheckSql);
        statement.setString(1, keyValue);
        LOG.info(statement.toString());
        ResultSet resultSet = statement.executeQuery();
        // Keep track of number of records found with key field
        TIntSet uniqueIds = new TIntHashSet();
        while (resultSet.next()) {
            int uniqueId = resultSet.getInt(1);
            uniqueIds.add(uniqueId);
            LOG.info("entity id: {}, where {}: {}", uniqueId, keyField, keyValue);
        }
        return uniqueIds;
    }

    /**
     * Finds the set of tables that reference the parent entity being updated.
     */
    private static Set<Table> getReferencingTables(Table table) {
        Set<Table> referencingTables = new HashSet<>();
        for (Table gtfsTable : Table.tablesInOrder) {
            // IMPORTANT: Skip the table for the entity we're modifying or if loop table does not have field.
            if (table.name.equals(gtfsTable.name)) continue;
            for (Field field : gtfsTable.fields) {
                if (field.isForeignReference()) {
                    for (Table refTable : field.referenceTables) {
                        if (refTable.name.equals(table.name)) {
                            referencingTables.add(gtfsTable);
                        }
                    }
                }
            }
        }
        return referencingTables;
    }

    /**
     * For a given integer ID, return the value for the specified field name for that entity.
     */
    private static String getValueForId(int id, String fieldName, String namespace, Table table, Connection connection) throws SQLException {
        String tableName = String.join(".", namespace, table.name);
        String selectIdSql = String.format("select %s from %s where id = %d", fieldName, tableName, id);
        LOG.info(selectIdSql);
        Statement selectIdStatement = connection.createStatement();
        ResultSet selectResults = selectIdStatement.executeQuery(selectIdSql);
        String value = null;
        while (selectResults.next()) {
            value = selectResults.getString(1);
        }
        return value;
    }

    /**
     * Updates any foreign references that exist should a GTFS key field (e.g., stop_id or route_id) be updated via an
     * HTTP request for a given integer ID. First, all GTFS tables are filtered to find referencing tables. Then records
     * in these tables that match the old key value are modified to match the new key value.
     *
     * The function determines whether the method is update or delete depending on the presence of the newKeyValue
     * parameter (if null, the method is DELETE). Custom logic/hooks could be added here to check if there are entities
     * referencing the entity being updated.
     *
     * FIXME: add custom logic/hooks. Right now entity table checks are hard-coded in (e.g., if Agency, skip all. OR if
     * Calendar, rollback transaction if there are referencing trips).
     *
     * FIXME: Do we need to clarify the impact of the direction of the relationship (e.g., if we delete a trip, that should
     * not necessarily delete a shape that is shared by multiple trips)? I think not because we are skipping foreign refs
     * found in the table for the entity being updated/deleted. [Leaving this comment in place for now though.]
     */
    private void updateReferencingTables(
        String namespace,
        Table table,
        int id,
        String newKeyValue
    ) throws SQLException {
        Field keyField = table.getFieldForName(table.getKeyFieldName());
        Class<? extends Entity> entityClass = table.getEntityClass();
        // Determine method (update vs. delete) depending on presence of newKeyValue field.
        SqlMethod sqlMethod = newKeyValue != null ? SqlMethod.UPDATE : SqlMethod.DELETE;
        Set<Table> referencingTables = getReferencingTables(table);
        // If there are no referencing tables, there is no need to update any values (e.g., .
        if (referencingTables.isEmpty()) return;
        String keyValue = getValueForId(id, keyField.name, namespace, table, connection);
        if (keyValue == null) {
            // FIXME: should we still check referencing tables for null value?
            LOG.warn("Entity {} to {} has null value for {}. Skipping references check.", id, sqlMethod, keyField);
            return;
        }
        if (
            sqlMethod.equals(SqlMethod.DELETE) &&
            (table.name.equals(Table.PATTERNS.name) || table.name.equals(Table.ROUTES.name))
        ) {
            // Delete descendants at the end of the relationship tree.
            deleteDescendants(table.name, keyValue);
        }
        for (Table referencingTable : referencingTables) {
            // Update/delete foreign references that have match the key value.
            String refTableName = String.join(".", namespace, referencingTable.name);
            for (Field field : referencingTable.editorFields()) {
                if (field.isForeignReference()) {
                    for (Table refTable : field.referenceTables) {
                        if (refTable.name.equals(table.name)) {
                            // Get statement to update or delete entities that reference the key value.
                            PreparedStatement updateStatement = getUpdateReferencesStatement(sqlMethod, refTableName, field, keyValue, newKeyValue);
                            LOG.info("{}", updateStatement);
                            int result = updateStatement.executeUpdate();
                            if (result > 0) {
                                // FIXME: is this where a delete hook should go? (E.g., CalendarController subclass would override
                                //  deleteEntityHook).
                                if (sqlMethod.equals(SqlMethod.DELETE) && table.isCascadeDeleteRestricted()) {
                                    // Check for restrictions on delete. The entity must not have any referencing
                                    // entities in order to delete it.
                                    connection.rollback();
                                    String message = String.format(
                                        "Cannot delete %s %s=%s. %d %s reference this %s.",
                                        entityClass.getSimpleName(),
                                        keyField.name,
                                        keyValue,
                                        result,
                                        referencingTable.name,
                                        entityClass.getSimpleName()
                                    );
                                    LOG.warn(message);
                                    throw new SQLException(message);
                                }
                                LOG.info("{} reference(s) in {} {}D!", result, refTableName, sqlMethod);
                            } else {
                                LOG.info("No references in {} found!", refTableName);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * To prevent orphaned descendants, delete them before joining references are deleted. For the relationship
     * route -> pattern -> pattern stop, delete pattern stop before deleting the joining pattern.
     */
    private void deleteDescendants(String parentTableName, String routeOrPatternId) throws SQLException {
        // Delete child references before joining trips and patterns are deleted.
        String keyColumn = (parentTableName.equals(Table.PATTERNS.name)) ? "pattern_id" : "route_id";
        deleteStopTimesAndFrequencies(routeOrPatternId, keyColumn, parentTableName);
        deleteShapes(routeOrPatternId, keyColumn, parentTableName);

        if (parentTableName.equals(Table.ROUTES.name)) {
            // Delete pattern stops, locations and location groups before joining patterns are deleted.
            deletePatternStops(routeOrPatternId);
            deletePatternLocations(routeOrPatternId);
            deletePatternLocationGroups(routeOrPatternId);
        }
    }

    /**
     * If deleting a route, cascade delete pattern stops for patterns first. This must happen before patterns are
     * deleted. Otherwise, the queries to select pattern_stops to delete would fail because there would be no pattern
     * records to join with.
     */
    private void deletePatternStops(String routeId) throws SQLException {
        // Delete pattern stops for route.
        int deletedStopTimes = executeStatement(
            String.format(
                "delete from %s ps using %s p, %s r where ps.pattern_id = p.pattern_id and p.route_id = r.route_id and r.route_id = '%s'",
                String.format("%s.pattern_stops", tablePrefix),
                String.format("%s.patterns", tablePrefix),
                String.format("%s.routes", tablePrefix),
                routeId
            )
        );
        LOG.info("Deleted {} pattern stops for pattern {}", deletedStopTimes, routeId);
    }

    /**
     * If deleting a route, cascade delete pattern locations for patterns first. This must happen before patterns are
     * deleted. Otherwise, the queries to select pattern_locations to delete would fail because there would be no pattern
     * records to join with.
     */
    private void deletePatternLocations(String routeId) throws SQLException {
        // Delete pattern locations for route.
        int deletedPatternLocations = executeStatement(
            String.format(
                "delete from %s pl using %s p, %s r where pl.pattern_id = p.pattern_id and p.route_id = r.route_id and r.route_id = '%s'",
                String.format("%s.pattern_locations", tablePrefix),
                String.format("%s.patterns", tablePrefix),
                String.format("%s.routes", tablePrefix),
                routeId
            )
        );
        LOG.info("Deleted {} pattern locations for pattern {}", deletedPatternLocations, routeId);
    }

    /**
     * If deleting a route, cascade delete pattern location groups for patterns first. This must happen before patterns are
     * deleted. Otherwise, the queries to select pattern_location_groups to delete would fail because there would be no pattern
     * records to join with.
     */
    private void deletePatternLocationGroups(String routeId) throws SQLException {
        // Delete pattern location groups for route.
        int deletedPatternLocationGroups = executeStatement(
            String.format(
                "delete from %s plg using %s p, %s r where plg.pattern_id = p.pattern_id and p.route_id = r.route_id and r.route_id = '%s'",
                String.format("%s.pattern_location_groups", tablePrefix),
                String.format("%s.patterns", tablePrefix),
                String.format("%s.routes", tablePrefix),
                routeId
            )
        );
        LOG.info("Deleted {} pattern location groups for pattern {}", deletedPatternLocationGroups, routeId);
    }

    /**
     * If deleting a route or pattern, cascade delete stop times and frequencies for trips first. This must happen
     * before trips are deleted. Otherwise, the queries to select stop_times and frequencies to delete would fail
     * because there would be no trip records to join with.
     */
    private void deleteStopTimesAndFrequencies(
        String routeOrPatternId,
        String routeOrPatternIdColumn,
        String referencingTable
    ) throws SQLException {

        String tripsTable = String.format("%s.trips", tablePrefix);

        // Delete stop times for trips.
        int deletedStopTimes = executeStatement(
            String.format(
                "delete from %s s using %s t where s.trip_id = t.trip_id and t.%s = '%s'",
                String.format("%s.stop_times", tablePrefix),
                tripsTable,
                routeOrPatternIdColumn,
                routeOrPatternId
            )
        );
        LOG.info("Deleted {} stop times for {} {}", deletedStopTimes, referencingTable , routeOrPatternId);

        // Delete frequencies for trips.
        int deletedFrequencies = executeStatement(
            String.format(
                "delete from %s f using %s t where f.trip_id = t.trip_id and t.%s = '%s'",
                String.format("%s.frequencies", tablePrefix),
                tripsTable,
                routeOrPatternIdColumn,
                routeOrPatternId
            )
        );
        LOG.info("Deleted {} frequencies for {} {}", deletedFrequencies, referencingTable , routeOrPatternId);
    }

    /**
     * If deleting a route or pattern, cascade delete shapes. This must happen before patterns are deleted. Otherwise,
     * the queries to select shapes to delete would fail because there would be no pattern records to join with.
     */
    private void deleteShapes(String routeOrPatternId, String routeOrPatternIdColumn, String referencingTable)
        throws SQLException {

        String patternsTable = String.format("%s.patterns", tablePrefix);
        String shapesTable = String.format("%s.shapes", tablePrefix);

        // Delete shapes for route/pattern.
        String sql = (routeOrPatternIdColumn.equals("pattern_id"))
            ? String.format(
                "delete from %s s using %s p where s.shape_id = p.shape_id and p.pattern_id = '%s'",
                shapesTable,
                patternsTable,
                routeOrPatternId)
            : String.format(
                "delete from %s s using %s p, %s r where s.shape_id = p.shape_id and p.route_id = r.route_id and r.route_id = '%s'",
                shapesTable,
                patternsTable,
                String.format("%s.routes", tablePrefix),
                routeOrPatternId);

        int deletedShapes = executeStatement(sql);
        LOG.info("Deleted {} shapes for {} {}", deletedShapes, referencingTable , routeOrPatternId);
    }

    /**
     * Execute the provided sql and return the number of rows effected.
     */
    private int executeStatement(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            LOG.info("{}", sql);
            return statement.executeUpdate(sql);
        }
    }

    /**
     * Constructs prepared statement to update or delete (depending on the method specified) records with foreign
     * references to the provided key value.
     */
    private PreparedStatement getUpdateReferencesStatement(
        SqlMethod sqlMethod,
        String refTableName,
        Field keyField,
        String keyValue,
        String newKeyValue
    ) throws SQLException {
        String sql;
        PreparedStatement statement;
        boolean isArrayField = keyField.getSqlType().equals(JDBCType.ARRAY);
        switch (sqlMethod) {
            case DELETE:
                if (isArrayField) {
                    sql = String.format(
                        "delete from %s where %s @> ARRAY[?]::text[]",
                        refTableName,
                        keyField.name
                    );
                } else {
                    sql = String.format("delete from %s where %s = ?", refTableName, keyField.name);
                }
                statement = connection.prepareStatement(sql);
                statement.setString(1, keyValue);
                return statement;
            case UPDATE:
                if (isArrayField) {
                    // If the field to be updated is an array field (of which there are only text[] types in the db),
                    // replace the old value with the new value using array contains clause.
                    // NOTE: We have to cast the string values to the text type because the StringListField uses arrays
                    // of text.
                    sql = String.format(
                        "update %s set %s = array_replace(%s, ?::text, ?::text) where %s @> ?::text[]",
                        refTableName,
                        keyField.name,
                        keyField.name,
                        keyField.name
                    );
                    statement = connection.prepareStatement(sql);
                    statement.setString(1, keyValue);
                    statement.setString(2, newKeyValue);
                    String[] values = new String[] {keyValue};
                    statement.setArray(3, connection.createArrayOf("text", values));
                } else {
                    sql = String.format(
                        "update %s set %s = ? where %s = ?",
                        refTableName,
                        keyField.name,
                        keyField.name
                    );
                    statement = connection.prepareStatement(sql);
                    statement.setString(1, newKeyValue);
                    statement.setString(2, keyValue);
                }
                return statement;
            default:
                throw new SQLException("SQL Method must be DELETE or UPDATE.");

        }
    }
}
