package com.saburi.travel;

import com.saburi.travel.model.Route;
import com.saburi.travel.model.RouteFare;
import com.saburi.travel.model.RouteStop;
import com.saburi.travel.model.Schedule;
import com.saburi.travel.service.RouteJourney;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteJourneyExactFareTest {
    private Route route() {
        Route r = new Route(); r.id = 1L; r.origin = "Yeola"; r.destination = "Lasalgaon"; return r;
    }
    private Schedule schedule() { Schedule s = new Schedule(); s.id = 10L; s.routeId = 1L; s.fare = 999.0; return s; }
    private RouteStop vinchur() { RouteStop s = new RouteStop(); s.id = 7L; s.routeId = 1L; s.stopOrder = 1; s.name = "Vinchur"; s.city = "Vinchur"; return s; }
    private RouteFare fare(String from, String to, double amount) {
        RouteFare f = new RouteFare(); f.routeId = 1L; f.fromKey = from; f.toKey = to; f.fare = amount; return f;
    }

    @Test
    void exactConfiguredFareOverridesTheSumOfConsecutiveSegments() {
        List<RouteStop> stops = List.of(vinchur());
        List<RouteFare> fares = List.of(
            fare("ORIGIN", "STOP:7", 20),
            fare("STOP:7", "DESTINATION", 30),
            fare("ORIGIN", "DESTINATION", 50)
        );
        assertEquals(20.0, RouteJourney.resolve(route(), stops, schedule(), "Yeola", "Vinchur", fares).fare);
        assertEquals(30.0, RouteJourney.resolve(route(), stops, schedule(), "Vinchur", "Lasalgaon", fares).fare);
        assertEquals(50.0, RouteJourney.resolve(route(), stops, schedule(), "Yeola", "Lasalgaon", fares).fare);
    }

    @Test
    void missingExactFareIsCalculatedFromConsecutiveSegments() {
        List<RouteStop> stops = List.of(vinchur());
        List<RouteFare> fares = List.of(fare("ORIGIN", "STOP:7", 20), fare("STOP:7", "DESTINATION", 30));
        assertEquals(20.0, RouteJourney.resolve(route(), stops, schedule(), "Yeola", "Vinchur", fares).fare);
        assertEquals(50.0, RouteJourney.resolve(route(), stops, schedule(), "Yeola", "Lasalgaon", fares).fare);
    }

    @Test
    void explicitDirectFareOverridesTheSegmentSum() {
        List<RouteStop> stops = List.of(vinchur());
        List<RouteFare> fares = List.of(
            fare("ORIGIN", "STOP:7", 20),
            fare("STOP:7", "DESTINATION", 30),
            fare("ORIGIN", "DESTINATION", 45)
        );
        assertEquals(45.0, RouteJourney.resolve(route(), stops, schedule(), "Yeola", "Lasalgaon", fares).fare);
    }
}
