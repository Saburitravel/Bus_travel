package com.saburi.travel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

import com.saburi.travel.model.Route;
import com.saburi.travel.model.RouteStop;
import com.saburi.travel.model.Schedule;
import com.saburi.travel.service.RouteJourney;

class RouteJourneyTest {
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

    private List<RouteStop> stops() {
        RouteStop vinchur = new RouteStop();
        vinchur.routeId = 4L; vinchur.stopOrder = 1; vinchur.name = "Vinchur"; vinchur.city = "Vinchur"; vinchur.segmentFare = 120.0;
        RouteStop nampur = new RouteStop();
        nampur.routeId = 4L; nampur.stopOrder = 2; nampur.name = "Nampur"; nampur.city = "Nampur"; nampur.segmentFare = 80.0;
        return List.of(nampur, vinchur); // input order is intentionally unsorted
    }

    @Test
    void calculatesEachValidSegmentUsingTheRemainingFullFare() {
        Route route = route();
        Schedule schedule = schedule(300);
        List<RouteStop> stops = stops();

        assertEquals(120.0, RouteJourney.resolve(route, stops, schedule, "Yeola", "Vinchur").fare);
        assertEquals(180.0, RouteJourney.resolve(route, stops, schedule, "Vinchur", "Lasalgaon").fare);
        assertEquals(80.0, RouteJourney.resolve(route, stops, schedule, "Vinchur", "Nampur").fare);
        assertEquals(100.0, RouteJourney.resolve(route, stops, schedule, "Nampur", "Lasalgaon").fare);
        assertEquals(300.0, RouteJourney.resolve(route, stops, schedule, "Yeola", "Lasalgaon").fare);
    }

    @Test
    void rejectsReverseOrUnknownStopPairs() {
        Route route = route();
        Schedule schedule = schedule(300);
        List<RouteStop> stops = stops();
        assertNull(RouteJourney.resolve(route, stops, schedule, "Lasalgaon", "Vinchur"));
        assertNull(RouteJourney.resolve(route, stops, schedule, "Vinchur", "Unknown"));
    }

    @Test
    void partialJourneysNeedCompletePositiveSegmentFaresButFullRouteStaysBookable() {
        Route route = route();
        Schedule schedule = schedule(300);
        List<RouteStop> stops = stops();
        stops.stream().filter(stop -> "Vinchur".equals(stop.name)).findFirst().orElseThrow().segmentFare = null;

        assertNull(RouteJourney.resolve(route, stops, schedule, "Yeola", "Vinchur").fare);
        assertEquals(300.0, RouteJourney.resolve(route, stops, schedule, "Yeola", "Lasalgaon").fare);
    }

    @Test
    void rejectsSegmentTotalsThatConsumeTheFullRouteFare() {
        List<RouteStop> stops = stops();
        stops.get(0).segmentFare = 150.0;
        stops.get(1).segmentFare = 150.0;
        assertNull(RouteJourney.edgeFares(stops, 300));
    }
}
