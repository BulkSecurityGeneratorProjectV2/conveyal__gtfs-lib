package com.conveyal.gtfs.util.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Helper methods for writing REST API routines
 * @author mattwigway
 *
 */
public class JsonManager<T> {
    private ObjectWriter writer;
    private ObjectMapper mapper;
    SimpleFilterProvider filters;

    /**
     * Create a new JsonManager
     * @param theClass The class to create a json manager for (yes, also in the diamonds).
     */
    public JsonManager (Class<T> theClass) {
        this.theClass = theClass;
        this.mapper = new ObjectMapper();
        mapper.addMixInAnnotations(Rectangle2D.class, Rectangle2DMixIn.class);
        // FIXME: This codepath is deprecated in versions greater than 4.x, but there may be a need to replace this
        //  Jackson module to handle GeoJSON. If there is an issue with de-/serialization, might be good to take a look
        //  at https://github.com/opentripplanner/OpenTripPlanner/blob/08e034faace255e092211290220af3f60e553fa7/pom.xml#L515-L532
        //  (dependency used in OTP).
//        mapper.registerModule(new GeoJsonModule());
        SimpleModule deser = new SimpleModule();

        deser.addDeserializer(LocalDate.class, new JacksonSerializers.LocalDateStringDeserializer());
        deser.addSerializer(LocalDate.class, new JacksonSerializers.LocalDateStringSerializer());

        deser.addDeserializer(Rectangle2D.class, new Rectangle2DDeserializer());
        mapper.registerModule(deser);
        mapper.getSerializerProvider().setNullKeySerializer(new JacksonSerializers.MyDtoNullKeySerializer());
        filters = new SimpleFilterProvider();
        filters.addFilter("bbox", SimpleBeanPropertyFilter.filterOutAllExcept("west", "east", "south", "north"));
        this.writer = mapper.writer(filters);
    }

    private Class<T> theClass;

    /**
     * Add an additional mixin for serialization with this object mapper.
     */
    public void addMixin(Class target, Class mixin) {
        mapper.addMixInAnnotations(target, mixin);
    }

    public String write(Object o) throws JsonProcessingException {
        if (o instanceof String) {
            return (String) o;
        }
        return writer.writeValueAsString(o);
    }

    public String writePretty(Object o) throws JsonProcessingException {
        if (o instanceof String) {
            return (String) o;
        }
        return mapper.writerWithDefaultPrettyPrinter().with(filters).writeValueAsString(o);
    }

    /**
     * Convert a collection of objects to their JSON representation.
     * @param c the collection
     * @return A JsonNode representing the collection
     * @throws JsonProcessingException
     */
    public String write (Collection<T> c) throws JsonProcessingException {
        return writer.writeValueAsString(c);
    }

    public String write (Map<String, T> map) throws JsonProcessingException {
        return writer.writeValueAsString(map);
    }

    public T read (String s) throws IOException {
        return mapper.readValue(s, theClass);
    }

    public T read (JsonParser p) throws IOException {
        return mapper.readValue(p, theClass);
    }

    public T read(JsonNode asJson) {
        return mapper.convertValue(asJson, theClass);
    }

    public static <T> List<T> read(ObjectMapper mapper, ArrayNode subEntities, Class<T> target)
        throws JsonProcessingException {

        List<T> list = new ArrayList<>();
        for (JsonNode node : subEntities) {
            ObjectNode objectNode = (ObjectNode) node;
            if (!objectNode.get("id").isNumber()) {
                // Set ID to zero. ID is ignored entirely here.
                objectNode.put("id", 0);
            }
            // Accumulate new objects from JSON.
            list.add(mapper.readValue(objectNode.toString(), target));
        }
        return list;
    }
}
