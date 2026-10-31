package com.saburi.travel.service;

import com.saburi.travel.model.*;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.util.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Demo tracking: an administrator advances a bus along its route in 10% steps. */
@Service
public class TrackingService {
    private final Repos.TrackingRepo tracking;
    private final Repos.ScheduleRepo schedules;
    private final Repos.BusRepo buses;
    private final Repos.RouteRepo routes;
    private final Repos.StopRepo stops;
    private final Time time;

    public TrackingService(Repos.TrackingRepo tracking, Repos.ScheduleRepo schedules, Repos.BusRepo buses,
                           Repos.RouteRepo routes, Repos.StopRepo stops, Time time) {
        this.tracking = tracking; this.schedules = schedules; this.buses = buses; this.routes = routes; this.stops = stops; this.time = time;
    }

    private Schedule schedule(Long id) { return schedules.findById(id).orElseThrow(() -> new ApiException(404, "Schedule not found.")); }

    public Map<String, Object> get(Long scheduleId) {
        Schedule s = schedule(scheduleId);
        int p = tracking.findById(scheduleId).map(t -> t.progress).orElse(0);
        return dto(s, p);
    }

    public Map<String, Object> advance(Long scheduleId) {
        Schedule s = schedule(scheduleId);
        TrackingState t = tracking.findById(scheduleId).orElseGet(() -> { TrackingState n = new TrackingState(); n.scheduleId = scheduleId; return n; });
        t.progress = Math.min(100, t.progress + 10);
        t.updatedAt = time.now();
        tracking.save(t);
        return dto(s, t.progress);
    }

    private Map<String, Object> dto(Schedule s, int progress) {
        Bus bus = buses.findById(s.busId).orElseThrow();
        Route r = routes.findById(s.routeId).orElseThrow();
        List<String> names = new ArrayList<>();
        for (RouteStop st : stops.findByRouteIdOrderByStopOrder(r.id)) names.add(st.name);
        if (names.size() < 2) { names.clear(); names.add(r.origin); names.add(r.destination); }
        String location;
        if (progress <= 0) location = "Waiting at " + names.get(0);
        else if (progress >= 100) location = "Arrived at " + names.get(names.size() - 1);
        else {
            double pos = progress / 100.0 * (names.size() - 1);
            int i = (int) Math.floor(pos);
            location = "Between " + names.get(i) + " and " + names.get(i + 1);
        }
        return J.map("scheduleId", s.id, "busId", bus.busNumber, "route", r.origin + " → " + r.destination,
                "status", progress <= 0 ? "Scheduled" : progress >= 100 ? "Arrived" : "On route",
                "progress", progress, "currentLocation", location);
    }
}
