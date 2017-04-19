package com.conveyal.gtfs.validator;

import com.conveyal.gtfs.error.NewGTFSError;
import com.conveyal.gtfs.loader.Feed;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;

import java.util.List;

/**
 * Created by abyrd on 2017-04-18
 */
public class ReferencesTripValidator extends TripValidator {

    @Override
    public void validateTrip(Feed feed, Trip trip, List<StopTime> stopTimes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<NewGTFSError> complete() {

        return errors;
    }

}
