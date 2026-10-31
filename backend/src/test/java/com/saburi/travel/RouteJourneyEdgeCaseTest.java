package com.saburi.travel;

import com.saburi.travel.model.Route;
import com.saburi.travel.model.RouteStop;
import com.saburi.travel.model.Schedule;
import com.saburi.travel.service.RouteJourney;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Negative and boundary tests for route resolution and segment-fare calculation. */
class RouteJourneyEdgeCaseTest {
    private Route route() {
        Route route = new Route();
        route.id = 4L;
        route.origin = "Yeola";
        route.destination = "Lasalgaon";
        return route;
    }

    private Schedule schedule(double fare) {
        Schedule schedule = new Schedule();
        schedule.id = 20L;
        schedule.routeId = 4L;
        schedule.fare = fare;
        return schedule;
    }

    private RouteStop stop(int order, String name, Double fare) {
        RouteStop stop = new RouteStop();
        stop.routeId = 4L;
        stop.stopOrder = order;
        stop.name = name;
        stop.city = name;
        stop.segmentFare = fare;
        return stop;
    }

    @Test
    void rejectsNullRouteAndInvalidFullRouteFares() {
        List<RouteStop> stops = List.of(stop(1, "Vinchur", 120.0));
        assertNull(RouteJourney.resolve(null, stops, schedule(300), "Yeola", "Vinchur"));
        assertNull(RouteJourney.resolve(route(), stops, null, "Yeola", "Vinchur"));
        for (double invalidFare : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertNull(RouteJourney.resolve(route(), stops, schedule(invalidFare), "Yeola", "Vinchur"),
                    "Expected invalid full fare to be rejected: " + invalidFare);
        }
    }

    @Test
    void rejectsZeroNegativeAndNonFiniteSegmentFares() {
        for (Double invalidFare : new Double[]{0.0, -0.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null}) {
            assertNull(RouteJourney.edgeFares(List.of(stop(1, "Vinchur", invalidFare)), 300.0),
                    "Expected invalid segment fare to be rejected: " + invalidFare);
        }
    }

    @Test
    void roundsSegmentFaresToCentsAndKeepsTheTotalConsistent() {
        List<Double> fares = RouteJourney.edgeFares(List.of(stop(1, "Vinchur", 123.456)), 200.0);
        assertNotNull(fares);
        assertEquals(123.46, fares.get(0));
        assertEquals(76.54, fares.get(1));
        assertEquals(200.0, fares.stream().mapToDouble(Double::doubleValue).sum());
    }

    @Test
    void resolvesNamesCaseInsensitivelyAndTrimsWhitespace() {
        Route r = route();
        RouteStop vinchur = stop(1, " Vinchur ", 120.0);
        List<RouteStop> stops = List.of(vinchur);
        RouteJourney.Journey journey = RouteJourney.resolve(r, stops, schedule(300), " yeola ", "VINCHUR ");
        assertNotNull(journey);
        assertEquals(120.0, journey.fare);
    }

    @Test
    void rejectsAnIntermediateStopThatDuplicatesTheRouteDestination() {
        List<RouteStop> invalidStops = List.of(
                stop(1, "Vinchur", 120.0),
                stop(2, "Lasalgaon", 80.0));
        assertNull(RouteJourney.resolve(route(), invalidStops, schedule(300), "Yeola", "Lasalgaon"));
    }

    @Test
    void permitsTheFullRouteWhenNoIntermediateStopsExist() {
        RouteJourney.Journey journey = RouteJourney.resolve(route(), List.of(), schedule(300), "Yeola", "Lasalgaon");
        assertNotNull(journey);
        assertEquals(300.0, journey.fare);
        assertEquals(true, journey.isFullRoute());
    }

    @Test
    void rejectsConfiguredFaresThatExceedTheScheduleFare() {
        List<RouteStop> invalidStops = List.of(
                stop(1, "Vinchur", 180.0),
                stop(2, "Nampur", 140.01));
        assertNull(RouteJourney.edgeFares(invalidStops, 300.0));
    }
}
