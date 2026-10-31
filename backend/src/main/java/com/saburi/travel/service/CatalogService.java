package com.saburi.travel.service;

import com.saburi.travel.model.*;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CatalogService {
    private static final List<String> BUS_STATUS = List.of("Active", "Maintenance", "Inactive");
    private static final List<String> ROUTE_STATUS = List.of("Active", "Inactive");
    private static final List<String> SCHEDULE_STATUS = List.of("Open", "Closed", "Cancelled");
    private static final Pattern TIME_RE = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$");

    private final Repos.BusRepo buses;
    private final Repos.RouteRepo routes;
    private final Repos.StopRepo stops;
    private final Repos.RouteFareRepo routeFares;
    private final Repos.ScheduleRepo schedules;
    private final Repos.SeatRepo seats;
    private final Repos.HoldRepo holds;
    private final Repos.SegmentLockRepo segmentLocks;
    private final Repos.BookingRepo bookings;
    private final Repos.TrackingRepo tracking;
    private final JourneyService journeys;
    private final Time time;

    public CatalogService(Repos.BusRepo buses, Repos.RouteRepo routes, Repos.StopRepo stops, Repos.RouteFareRepo routeFares, Repos.ScheduleRepo schedules,
                          Repos.SeatRepo seats, Repos.HoldRepo holds, Repos.SegmentLockRepo segmentLocks,
                          Repos.BookingRepo bookings, Repos.TrackingRepo tracking, JourneyService journeys, Time time) {
        this.buses = buses; this.routes = routes; this.stops = stops; this.routeFares = routeFares; this.schedules = schedules;
        this.seats = seats; this.holds = holds; this.segmentLocks = segmentLocks; this.bookings = bookings;
        this.tracking = tracking; this.journeys = journeys; this.time = time;
    }

    // ---------- helpers ----------
    public Map<Long, Bus> busMap() { Map<Long, Bus> m = new HashMap<>(); for (Bus b : buses.findAll()) m.put(b.id, b); return m; }
    public Map<Long, Route> routeMap() { Map<Long, Route> m = new HashMap<>(); for (Route r : routes.findAll()) m.put(r.id, r); return m; }

    static String norm(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    private static String canon(String v, List<String> allowed, String what) {
        for (String a : allowed) if (a.equalsIgnoreCase(v.trim())) return a;
        throw new ApiException(400, "Invalid " + what + ": " + v);
    }

    private static String required(Body b, String k, int max, String label) {
        String v = b.str(k);
        if (v.isEmpty()) throw new ApiException(400, label + " is required.");
        if (v.length() > max) throw new ApiException(400, label + " is too long.");
        return v;
    }

    private static String optional(Body b, String k, int max, String label) {
        String v = b.str(k);
        if (v.length() > max) throw new ApiException(400, label + " is too long.");
        return v;
    }

    private static String parseTime(String v, String label) {
        if (!TIME_RE.matcher(v).matches()) throw new ApiException(400, label + " must be a valid HH:mm time.");
        return v.substring(0, 5);
    }

    private static LocalDate parseDate(String v) {
        try { return LocalDate.parse(v); } catch (Exception e) { throw new ApiException(400, "Date must be in YYYY-MM-DD format."); }
    }

    // ---------- DTOs ----------
    public Map<String, Object> busDto(Bus b) {
        return J.map("id", b.id, "busNumber", b.busNumber, "operatorName", b.operatorName, "operator", b.operatorName,
                "type", b.type, "totalSeats", b.totalSeats, "registrationNumber", b.registrationNumber == null ? "" : b.registrationNumber,
                "status", b.status, "route", "");
    }

    public Map<String, Object> routeDto(Route r) {
        return J.map("id", r.id, "routeCode", r.routeCode, "origin", r.origin, "destination", r.destination,
                "distance", r.distance == null ? "" : r.distance, "stops", r.stops,
                "duration", r.duration == null ? "" : r.duration, "status", r.status);
    }

    public Map<String, Object> scheduleDto(Schedule s, Bus bus, Route r, int available) {
        RouteJourney.Journey fullJourney = journeys.resolve(s, r.origin, r.destination);
        Double fullRouteFare = fullJourney == null ? findFareValue(r.id, "ORIGIN", "DESTINATION") : fullJourney.fare;
        Map<String, Object> dto = J.map("id", s.id, "scheduleId", s.id, "busId", s.busId, "routeId", s.routeId,
                "bus", bus.busNumber, "busNumber", bus.busNumber, "operator", bus.operatorName, "type", bus.type,
                "from", r.origin, "to", r.destination, "route", r.origin + " → " + r.destination,
                "depart", s.departureTime, "arrive", s.arrivalTime, "fare", fullRouteFare, "fullRouteFare", fullRouteFare, "seats", s.totalSeats,
                "availableSeats", Math.max(0, available), "travelDate", s.travelDate.toString(), "status", s.status);
        if (fullJourney != null) dto.put("journeyStops", fullJourney.selectedPoints());
        return dto;
    }

    private Map<String, Object> stopDto(RouteStop s) {
        return J.map("id", s.id, "routeId", s.routeId, "stopOrder", s.stopOrder, "name", s.name,
                "city", s.city == null ? "" : s.city, "latitude", s.latitude, "longitude", s.longitude);
    }

    // ---------- catalog / search ----------
    public Map<String, Object> catalog(boolean admin) {
        Map<Long, Bus> bm = busMap();
        Map<Long, Route> rm = routeMap();
        List<Schedule> list = new ArrayList<>(admin ? schedules.findAll() : schedules.findByTravelDateGreaterThanEqual(time.today()));
        list.sort(Comparator.comparing((Schedule s) -> s.travelDate).thenComparing(s -> s.departureTime));
        List<Map<String, Object>> busOut = new ArrayList<>(), routeOut = new ArrayList<>(), schedOut = new ArrayList<>();
        for (Bus b : bm.values()) if (admin || "Active".equals(b.status)) busOut.add(busDto(b));
        for (Route r : rm.values()) if (admin || "Active".equals(r.status)) routeOut.add(routeDto(r));
        for (Schedule s : list) {
            Bus b = bm.get(s.busId);
            Route r = rm.get(s.routeId);
            if (b == null || r == null) continue;
            if (!admin) {
                if (!"Active".equals(b.status) || !"Active".equals(r.status) || !"Open".equals(s.status)) continue;
                // Keep every open schedule for today visible, including departed services.
                // Past dates are already excluded by the public catalog query. Booking endpoints enforce departure cutoffs.
            }
            RouteJourney.Journey fullJourney = journeys.resolve(s, r.origin, r.destination);
            int available = fullJourney == null || fullJourney.fare == null ? 0 : journeys.availableSeatCount(s, fullJourney, null);
            schedOut.add(scheduleDto(s, b, r, available));
        }
        busOut.sort(Comparator.comparing(m -> String.valueOf(m.get("busNumber"))));
        routeOut.sort(Comparator.comparing(m -> String.valueOf(m.get("routeCode"))));
        return J.map("buses", busOut, "routes", routeOut, "schedules", schedOut);
    }

    public List<Map<String, Object>> search(String from, String to, String date) {
        LocalDate d = parseDate(date);
        return findJourneyServices(from, to, new ArrayList<>(schedules.findByTravelDate(d)));
    }

    /** Popular-route results: all active upcoming schedules, without requiring a travel date. */
    public List<Map<String, Object>> upcomingRoute(String from, String to) {
        String requestedFrom = norm(from), requestedTo = norm(to);
        if (requestedFrom.isEmpty() || requestedTo.isEmpty())
            throw new ApiException(400, "Choose a valid popular route.");
        if (requestedFrom.equals(requestedTo))
            throw new ApiException(400, "Route origin and destination must be different.");

        Map<Long, Bus> bm = busMap();
        Map<Long, Route> rm = routeMap();
        List<Schedule> list = new ArrayList<>(schedules.findByTravelDateGreaterThanEqual(time.today()));
        list.sort(Comparator.comparing((Schedule s) -> s.travelDate).thenComparing(s -> s.departureTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Schedule s : list) {
            Bus b = bm.get(s.busId);
            Route r = rm.get(s.routeId);
            if (b == null || r == null) continue;
            if (!"Active".equals(b.status) || !"Active".equals(r.status) || !"Open".equals(s.status)) continue;
            if (!norm(r.origin).equals(requestedFrom) || !norm(r.destination).equals(requestedTo)) continue;
            // Popular Routes must list matching buses before the customer chooses a fare-bearing stop pair.
            // Do not require a configured full-route fare here; the selected pair is validated by /schedules/search.
            if (!time.departure(s).isAfter(time.now())) continue;
            RouteJourney.Journey fullJourney = journeys.resolve(s, r.origin, r.destination);
            if (fullJourney == null) continue;
            int available = journeys.availableSeatCount(s, fullJourney, null);
            Map<String,Object> dto = scheduleDto(s, b, r, available);
            dto.put("from", r.origin);
            dto.put("to", r.destination);
            dto.put("journeyStops", fullJourney.selectedPoints());
            dto.put("fromIndex", fullJourney.fromIndex);
            dto.put("toIndex", fullJourney.toIndex);
            dto.put("fare", fullJourney.fare);
            dto.put("fullRouteFare", fullJourney.fare);
            out.add(dto);
        }
        return out;
    }

    private List<Map<String, Object>> findJourneyServices(String from, String to, List<Schedule> list) {
        String f = norm(from), t = norm(to);
        if (f.isEmpty() || t.isEmpty()) throw new ApiException(400, "Enter From and To cities.");
        if (f.equals(t)) throw new ApiException(400, "From and To stops must be different.");
        Map<Long, Bus> bm = busMap();
        Map<Long, Route> rm = routeMap();
        list.sort(Comparator.comparing((Schedule s) -> s.travelDate).thenComparing(s -> s.departureTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Schedule s : list) {
            Bus b = bm.get(s.busId);
            Route r = rm.get(s.routeId);
            if (b == null || r == null) continue;
            if (!"Active".equals(b.status) || !"Active".equals(r.status) || !"Open".equals(s.status)) continue;
            // Both date-search and popular-route results hide already departed services.
            if (!time.departure(s).isAfter(time.now())) continue;
            RouteJourney.Journey journey = journeys.resolve(s, from, to);
            if (journey == null || journey.fare == null) continue;
            int available = journeys.availableSeatCount(s, journey, null);
            Map<String,Object> dto = scheduleDto(s, b, r, available);
            dto.put("from", journey.from);
            dto.put("to", journey.to);
            dto.put("journeyStops", journey.selectedPoints());
            dto.put("fromIndex", journey.fromIndex);
            dto.put("toIndex", journey.toIndex);
            dto.put("fare", journey.fare);
            dto.put("fullRouteFare", findFareValue(r.id, "ORIGIN", "DESTINATION"));
            out.add(dto);
        }
        return out;
    }

    public List<Map<String, Object>> seatMap(Long scheduleId, String holdToken) {
        return seatMap(scheduleId, null, null, holdToken);
    }

    public List<Map<String, Object>> seatMap(Long scheduleId, String from, String to, String holdToken) {
        Schedule s = schedules.findById(scheduleId).orElseThrow(() -> new ApiException(404, "Schedule not found."));
        RouteJourney.Journey journey = journeys.require(s, from, to);
        Map<Integer, String> statuses = journeys.seatStatuses(s, journey, holdToken);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= s.totalSeats; i++)
            out.add(J.map("seat", String.valueOf(i), "status", statuses.getOrDefault(i, "UNAVAILABLE")));
        return out;
    }

    // ---------- buses ----------
    public List<Map<String, Object>> listBuses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Bus b : buses.findAll()) out.add(busDto(b));
        return out;
    }

    private void applyBus(Bus bus, Body b) {
        bus.busNumber = required(b, "busNumber", 30, "Bus number");
        bus.operatorName = required(b, "operatorName", 60, "Operator");
        bus.type = required(b, "type", 30, "Bus type");
        int seatsCount = b.integer("totalSeats");
        if (seatsCount < 1 || seatsCount > 100) throw new ApiException(400, "Total seats must be between 1 and 100.");
        bus.totalSeats = seatsCount;
        bus.registrationNumber = optional(b, "registrationNumber", 30, "Registration number");
        bus.status = b.has("status") ? canon(b.str("status"), BUS_STATUS, "bus status") : "Active";
    }

    public Map<String, Object> createBus(Body b) {
        Bus bus = new Bus();
        applyBus(bus, b);
        if (buses.findByBusNumberIgnoreCase(bus.busNumber).isPresent()) throw new ApiException(409, "A bus with this number already exists.");
        return busDto(buses.save(bus));
    }

    /** Note: changing totalSeats affects only schedules created afterwards. */
    public Map<String, Object> updateBus(Long id, Body b) {
        Bus bus = buses.findById(id).orElseThrow(() -> new ApiException(404, "Bus not found."));
        applyBus(bus, b);
        Optional<Bus> dup = buses.findByBusNumberIgnoreCase(bus.busNumber);
        if (dup.isPresent() && !dup.get().id.equals(id)) throw new ApiException(409, "A bus with this number already exists.");
        return busDto(buses.save(bus));
    }

    public void deleteBus(Long id) {
        if (!buses.existsById(id)) throw new ApiException(404, "Bus not found.");
        if (schedules.existsByBusId(id)) throw new ApiException(409, "This bus has schedules. Delete them first, or set the bus to Inactive.");
        buses.deleteById(id);
    }

    // ---------- routes ----------
    public List<Map<String, Object>> listRoutes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Route r : routes.findAll()) out.add(routeDto(r));
        return out;
    }

    private void applyRoute(Route r, Body b) {
        r.routeCode = required(b, "routeCode", 30, "Route ID");
        r.origin = required(b, "origin", 80, "Origin");
        r.destination = required(b, "destination", 80, "Destination");
        if (norm(r.origin).equals(norm(r.destination))) throw new ApiException(400, "Origin and destination must be different.");
        r.distance = optional(b, "distance", 30, "Distance");
        r.duration = optional(b, "duration", 30, "Duration");
        int n = b.has("stops") ? b.integer("stops") : 0;
        if (n < 0 || n > 200) throw new ApiException(400, "Stops must be between 0 and 200.");
        r.stops = n;
        r.status = b.has("status") ? canon(b.str("status"), ROUTE_STATUS, "route status") : "Active";
    }

    public Map<String, Object> createRoute(Body b) {
        Route r = new Route();
        applyRoute(r, b);
        if (routes.findByRouteCodeIgnoreCase(r.routeCode).isPresent()) throw new ApiException(409, "A route with this ID already exists.");
        return routeDto(routes.save(r));
    }

    public Map<String, Object> updateRoute(Long id, Body b) {
        Route r = routes.findById(id).orElseThrow(() -> new ApiException(404, "Route not found."));
        String oldOrigin = r.origin, oldDest = r.destination;
        applyRoute(r, b);
        Optional<Route> dup = routes.findByRouteCodeIgnoreCase(r.routeCode);
        if (dup.isPresent() && !dup.get().id.equals(id)) throw new ApiException(409, "A route with this ID already exists.");
        boolean cityChanged = !norm(oldOrigin).equals(norm(r.origin)) || !norm(oldDest).equals(norm(r.destination));
        if (cityChanged) {
            for (RouteStop stop : stops.findByRouteIdOrderByStopOrder(id)) {
                String name = norm(stop.name), city = norm(stop.city);
                if (name.equals(norm(r.origin)) || name.equals(norm(r.destination))
                        || (!city.isEmpty() && (city.equals(norm(r.origin)) || city.equals(norm(r.destination)))))
                    throw new ApiException(409, "The new route origin or destination matches an existing intermediate stop. Rename or remove that stop first.");
            }
        }
        if (cityChanged && schedules.findByRouteId(id).stream().anyMatch(s -> bookings.existsByScheduleId(s.id)))
            throw new ApiException(409, "This route already has bookings; create a new route instead of changing its cities.");
        if (cityChanged && hasActiveSeatHoldsForRoute(id))
            throw new ApiException(409, "Customers currently have seats on hold for this route. Wait for those holds to expire before changing its cities.");
        return routeDto(routes.save(r));
    }

    @Transactional
    public void deleteRoute(Long id) {
        if (!routes.existsById(id)) throw new ApiException(404, "Route not found.");
        if (schedules.existsByRouteId(id)) throw new ApiException(409, "This route has schedules. Delete them first, or set the route to Inactive.");
        stops.deleteByRouteId(id);
        routeFares.deleteByRouteId(id);
        routes.deleteById(id);
    }

    // ---------- stops ----------
    public List<Map<String, Object>> listStops(Long routeId) {
        if (!routes.existsById(routeId)) throw new ApiException(404, "Route not found.");
        List<Map<String, Object>> out = new ArrayList<>();
        for (RouteStop s : stops.findByRouteIdOrderByStopOrder(routeId)) out.add(stopDto(s));
        return out;
    }

    private void applyStop(RouteStop s, Body b) {
        String name = required(b, "name", 80, "Stop name");
        String city = optional(b, "city", 80, "City");
        int order = b.integer("stopOrder");
        if (order < 1 || order > 200) throw new ApiException(400, "Invalid stop order.");
        s.name = name;
        s.city = city;
        s.stopOrder = order;
        s.latitude = b.dblOrNull("latitude");
        s.longitude = b.dblOrNull("longitude");
        if (s.latitude != null && (s.latitude < -90 || s.latitude > 90)) throw new ApiException(400, "Latitude must be between -90 and 90.");
        if (s.longitude != null && (s.longitude < -180 || s.longitude > 180)) throw new ApiException(400, "Longitude must be between -180 and 180.");
        // Fare configuration is stored independently in route_fares, never on the stop record.
    }

    private void validateStopIsIntermediate(Route route, RouteStop stop, Long exceptStopId) {
        Set<String> candidateAliases = new HashSet<>();
        if (!norm(stop.name).isEmpty()) candidateAliases.add(norm(stop.name));
        if (!norm(stop.city).isEmpty()) candidateAliases.add(norm(stop.city));
        Set<String> endpoints = Set.of(norm(route.origin), norm(route.destination));
        if (candidateAliases.stream().anyMatch(endpoints::contains))
            throw new ApiException(400, "Do not add the route origin or destination as an intermediate stop. Add only stops between them.");
        for (RouteStop existing : stops.findByRouteIdOrderByStopOrder(route.id)) {
            if (exceptStopId != null && exceptStopId.equals(existing.id)) continue;
            Set<String> existingAliases = new HashSet<>();
            if (!norm(existing.name).isEmpty()) existingAliases.add(norm(existing.name));
            if (!norm(existing.city).isEmpty()) existingAliases.add(norm(existing.city));
            if (candidateAliases.stream().anyMatch(existingAliases::contains))
                throw new ApiException(409, "This stop name or city matches another stop on the route. Use a unique boarding/drop point so the customer can select it unambiguously.");
        }
    }

    private boolean hasActiveSeatHoldsForSchedule(Long scheduleId) {
        LocalDateTime now = time.now();
        boolean legacy = holds.findByScheduleIdAndExpiresAtAfter(scheduleId, now).stream()
                .anyMatch(hold -> hold.bookingId == null);
        if (legacy) return true;
        return segmentLocks.findByScheduleId(scheduleId).stream()
                .anyMatch(lock -> lock.bookingId == null && lock.expiresAt != null && lock.expiresAt.isAfter(now));
    }

    private boolean hasActiveSeatHoldsForRoute(Long routeId) {
        return schedules.findByRouteId(routeId).stream().anyMatch(schedule -> hasActiveSeatHoldsForSchedule(schedule.id));
    }

    private void validateStopOrderAvailable(Long routeId, int stopOrder, Long exceptStopId) {
        for (RouteStop existing : stops.findByRouteIdOrderByStopOrder(routeId)) {
            if (existing.stopOrder == stopOrder && (exceptStopId == null || !exceptStopId.equals(existing.id)))
                throw new ApiException(409, "That stop order is already used on this route. Choose a different order.");
        }
    }

    @Transactional
    public Map<String, Object> addStop(Long routeId, Body b) {
        Route route = routes.findById(routeId).orElseThrow(() -> new ApiException(404, "Route not found."));
        if (hasFutureActiveBookings(routeId))
            throw new ApiException(409, "This route has upcoming bookings. Create a new route to change its stops without affecting existing journeys.");
        if (hasActiveSeatHoldsForRoute(routeId))
            throw new ApiException(409, "Customers currently have seats on hold for this route. Wait for those holds to expire before changing its stops.");
        RouteStop s = new RouteStop();
        s.routeId = routeId;
        applyStop(s, b);
        validateStopIsIntermediate(route, s, null);
        validateStopOrderAvailable(routeId, s.stopOrder, null);
        RouteStop saved = stops.save(s);
        route.stops = stops.findByRouteIdOrderByStopOrder(routeId).size();
        routes.save(route);
        return stopDto(saved);
    }

    @Transactional
    public Map<String, Object> updateStop(Long id, Body b) {
        RouteStop s = stops.findById(id).orElseThrow(() -> new ApiException(404, "Stop not found."));
        String nextName = required(b, "name", 80, "Stop name");
        String nextCity = optional(b, "city", 80, "City");
        int nextOrder = b.integer("stopOrder");
        boolean structureChanged = nextOrder != s.stopOrder
                || !norm(nextName).equals(norm(s.name)) || !norm(nextCity).equals(norm(s.city));
        boolean fareChanged = false;
        if (structureChanged && hasFutureActiveBookings(s.routeId))
            throw new ApiException(409, "This route has upcoming bookings. Stop names/order cannot change while those journeys are active; create a new route for structural changes.");
        if ((structureChanged || fareChanged) && hasActiveSeatHoldsForRoute(s.routeId))
            throw new ApiException(409, "Customers currently have seats on hold for this route. Wait for those holds to expire before changing stops or fares.");
        applyStop(s, b);
        Route route = routes.findById(s.routeId).orElseThrow(() -> new ApiException(404, "Route not found."));
        validateStopIsIntermediate(route, s, s.id);
        validateStopOrderAvailable(s.routeId, s.stopOrder, s.id);
        List<RouteStop> prospectiveStops = new ArrayList<>(stops.findByRouteIdOrderByStopOrder(s.routeId));
        for (int i = 0; i < prospectiveStops.size(); i++) if (s.id.equals(prospectiveStops.get(i).id)) prospectiveStops.set(i, s);
        prospectiveStops.sort(Comparator.comparingInt(stop -> stop.stopOrder));
        validateFareOrder(route, prospectiveStops);
        RouteStop updated = stops.save(s);
        return stopDto(updated);
    }

    @Transactional
    public void deleteStop(Long id) {
        RouteStop stop = stops.findById(id).orElseThrow(() -> new ApiException(404, "Stop not found."));
        if (hasFutureActiveBookings(stop.routeId))
            throw new ApiException(409, "This route has upcoming bookings. Create a new route to change its stops without affecting existing journeys.");
        if (hasActiveSeatHoldsForRoute(stop.routeId))
            throw new ApiException(409, "Customers currently have seats on hold for this route. Wait for those holds to expire before changing its stops.");
        Long routeId = stop.routeId;
        // Remove only fares that reference this stop. Never merge or recalculate another fare.
        String stopKey = "STOP:" + id;
        for (RouteFare fare : routeFares.findByRouteId(routeId)) {
            if (stopKey.equals(fare.fromKey) || stopKey.equals(fare.toKey)) routeFares.deleteById(fare.id);
        }
        stops.deleteById(id);
        routes.findById(routeId).ifPresent(route -> {
            route.stops = stops.findByRouteIdOrderByStopOrder(routeId).size();
            routes.save(route);
        });
    }

    // ---------- independently configured route fares ----------
    private LinkedHashMap<String, String> routePointLabels(Route route, List<RouteStop> routeStops) {
        LinkedHashMap<String, String> points = new LinkedHashMap<>();
        points.put("ORIGIN", route.origin);
        for (RouteStop stop : routeStops) points.put("STOP:" + stop.id, stop.city == null || stop.city.isBlank() ? stop.name : stop.city);
        points.put("DESTINATION", route.destination);
        return points;
    }

    private Double findFareValue(Long routeId, String fromKey, String toKey) {
        if (routeFares == null || routeId == null) return null;
        return routeFares.findByRouteIdAndFromKeyAndToKey(routeId, fromKey, toKey)
                .filter(f -> Double.isFinite(f.fare) && f.fare > 0).map(f -> Math.round(f.fare * 100.0) / 100.0).orElse(null);
    }

    public List<Map<String, Object>> listRouteFares(Long routeId) {
        Route route = routes.findById(routeId).orElseThrow(() -> new ApiException(404, "Route not found."));
        List<RouteStop> routeStops = stops.findByRouteIdOrderByStopOrder(routeId);
        LinkedHashMap<String, String> points = routePointLabels(route, routeStops);
        Map<String, Integer> order = new HashMap<>(); int i = 0;
        for (String key : points.keySet()) order.put(key, i++);
        List<RouteFare> fares = new ArrayList<>(routeFares.findByRouteId(routeId));
        fares.sort(Comparator.comparingInt((RouteFare f) -> order.getOrDefault(f.fromKey, Integer.MAX_VALUE))
                .thenComparingInt(f -> order.getOrDefault(f.toKey, Integer.MAX_VALUE)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (RouteFare fare : fares) out.add(routeFareDto(fare, points));
        return out;
    }

    private Map<String, Object> routeFareDto(RouteFare fare, Map<String, String> points) {
        return J.map("id", fare.id, "routeId", fare.routeId, "fromKey", fare.fromKey, "toKey", fare.toKey,
                "fromLabel", points.getOrDefault(fare.fromKey, fare.fromLabel == null ? "Unknown stop" : fare.fromLabel),
                "toLabel", points.getOrDefault(fare.toKey, fare.toLabel == null ? "Unknown stop" : fare.toLabel), "fare", fare.fare);
    }

    private int pointIndex(String key, LinkedHashMap<String, String> points) {
        int index = 0;
        for (String candidate : points.keySet()) { if (candidate.equals(key)) return index; index++; }
        return -1;
    }

    private void validateFareOrder(Route route, List<RouteStop> orderedStops) {
        LinkedHashMap<String, String> points = routePointLabels(route, orderedStops);
        Map<String, Integer> positions = new HashMap<>(); int i = 0;
        for (String key : points.keySet()) positions.put(key, i++);
        for (RouteFare fare : routeFares.findByRouteId(route.id)) {
            Integer from = positions.get(fare.fromKey), to = positions.get(fare.toKey);
            if (from == null || to == null || to <= from)
                throw new ApiException(409, "This stop order change would invalidate the configured fare " + fare.fromLabel + " → " + fare.toLabel + ". Update or delete that fare first; no fares were changed.");
        }
    }

    private RouteFare applyRouteFare(Route route, RouteFare fare, Body body, Long exceptId) {
        List<RouteStop> routeStops = stops.findByRouteIdOrderByStopOrder(route.id);
        LinkedHashMap<String, String> points = routePointLabels(route, routeStops);
        String fromKey = required(body, "fromKey", 100, "Boarding stop");
        String toKey = required(body, "toKey", 100, "Destination stop");
        int fromIndex = pointIndex(fromKey, points), toIndex = pointIndex(toKey, points);
        if (fromIndex < 0 || toIndex < 0) throw new ApiException(400, "Choose stops that belong to the selected route.");
        if (toIndex <= fromIndex) throw new ApiException(400, "Destination must appear after the boarding stop on this route.");
        double value = body.dbl("fare");
        if (!Double.isFinite(value) || value <= 0 || value > 100000)
            throw new ApiException(400, "Fare must be greater than ₹0 and at most ₹100,000.");
        if (routeFares.findByRouteIdAndFromKeyAndToKey(route.id, fromKey, toKey)
                .filter(existing -> exceptId == null || !exceptId.equals(existing.id)).isPresent())
            throw new ApiException(409, "A fare already exists for this boarding and destination pair. Edit that fare instead.");
        if (hasActiveSeatHoldsForRoute(route.id))
            throw new ApiException(409, "Customers currently have seats on hold for this route. Wait for those holds to expire before changing fares.");
        double roundedFare = Math.round(value * 100.0) / 100.0;
        if (roundedFare <= 0) throw new ApiException(400, "Fare must be at least ₹0.01.");
        fare.routeId = route.id; fare.fromKey = fromKey; fare.toKey = toKey;
        fare.fromLabel = points.get(fromKey); fare.toLabel = points.get(toKey); fare.fare = roundedFare;
        return fare;
    }

    @Transactional
    public Map<String, Object> addRouteFare(Long routeId, Body body) {
        Route route = routes.findById(routeId).orElseThrow(() -> new ApiException(404, "Route not found."));
        RouteFare fare = applyRouteFare(route, new RouteFare(), body, null);
        RouteFare saved = routeFares.save(fare);
        return routeFareDto(saved, routePointLabels(route, stops.findByRouteIdOrderByStopOrder(routeId)));
    }

    @Transactional
    public Map<String, Object> updateRouteFare(Long id, Body body) {
        RouteFare fare = routeFares.findById(id).orElseThrow(() -> new ApiException(404, "Fare not found."));
        Route route = routes.findById(fare.routeId).orElseThrow(() -> new ApiException(404, "Route not found."));
        RouteFare updated = applyRouteFare(route, fare, body, id);
        RouteFare saved = routeFares.save(updated);
        return routeFareDto(saved, routePointLabels(route, stops.findByRouteIdOrderByStopOrder(route.id)));
    }

    @Transactional
    public void deleteRouteFare(Long id) {
        RouteFare fare = routeFares.findById(id).orElseThrow(() -> new ApiException(404, "Fare not found."));
        if (hasActiveSeatHoldsForRoute(fare.routeId))
            throw new ApiException(409, "Customers currently have seats on hold for this route. Wait for those holds to expire before deleting fares.");
        routeFares.deleteById(id);
    }

    // ---------- schedules ----------
    public List<Map<String, Object>> listSchedules() {
        Map<Long, Bus> bm = busMap();
        Map<Long, Route> rm = routeMap();
        List<Schedule> list = new ArrayList<>(schedules.findAll());
        list.sort(Comparator.comparing((Schedule s) -> s.travelDate).thenComparing(s -> s.departureTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Schedule s : list) {
            Bus b = bm.get(s.busId);
            Route r = rm.get(s.routeId);
            if (b != null && r != null) {
                RouteJourney.Journey fullJourney = journeys.resolve(s, r.origin, r.destination);
                int available = fullJourney == null || fullJourney.fare == null ? 0 : journeys.availableSeatCount(s, fullJourney, null);
                out.add(scheduleDto(s, b, r, available));
            }
        }
        return out;
    }

    private Map<String, Object> dto(Schedule s) {
        Bus b = buses.findById(s.busId).orElseThrow();
        Route r = routes.findById(s.routeId).orElseThrow();
        return scheduleDto(s, b, r, s.totalSeats);
    }

    private void checkDuplicate(Schedule s) {
        for (Schedule o : schedules.findByBusIdAndTravelDate(s.busId, s.travelDate))
            if (!o.id.equals(s.id) && o.departureTime.equals(s.departureTime))
                throw new ApiException(409, "This bus already has a schedule on that date at that departure time.");
    }

    private boolean hasFutureActiveBookings(Long routeId) {
        for (Schedule schedule : schedules.findByRouteId(routeId)) {
            if (!time.departure(schedule).isAfter(time.now())) continue;
            if (bookings.findByScheduleId(schedule.id).stream()
                    .anyMatch(booking -> "Pending".equalsIgnoreCase(booking.status) || "Confirmed".equalsIgnoreCase(booking.status))) return true;
        }
        return false;
    }

    public Map<String, Object> createSchedule(Body b) {
        Bus bus = buses.findById(b.lng("busId")).orElseThrow(() -> new ApiException(400, "Select a valid bus."));
        routes.findById(b.lng("routeId")).orElseThrow(() -> new ApiException(400, "Select a valid route."));
        if (!"Active".equals(bus.status)) throw new ApiException(400, "Only active buses can be scheduled.");
        Schedule s = new Schedule();
        s.busId = bus.id;
        s.routeId = b.lng("routeId");
        s.travelDate = parseDate(b.str("travelDate"));
        if (s.travelDate.isBefore(time.today())) throw new ApiException(400, "Travel date cannot be in the past.");
        s.departureTime = parseTime(b.str("departureTime"), "Departure");
        s.arrivalTime = parseTime(b.str("arrivalTime"), "Arrival");
        // Legacy field retained for older records only. New schedules do not own fare data.
        s.fare = Optional.ofNullable(findFareValue(s.routeId, "ORIGIN", "DESTINATION")).orElse(0.0);
        s.totalSeats = bus.totalSeats;
        s.status = b.has("status") ? canon(b.str("status"), SCHEDULE_STATUS, "schedule status") : "Open";
        checkDuplicate(s);
        return dto(schedules.save(s));
    }

    public Map<String, Object> updateSchedule(Long id, Body b) {
        Schedule s = schedules.findById(id).orElseThrow(() -> new ApiException(404, "Schedule not found."));
        boolean hasBookings = bookings.existsByScheduleId(id);
        Long newBus = b.lng("busId"), newRoute = b.lng("routeId");
        if (!buses.existsById(newBus) || !routes.existsById(newRoute)) throw new ApiException(400, "Schedule references an invalid bus or route.");
        if (hasBookings && (!newBus.equals(s.busId) || !newRoute.equals(s.routeId)))
            throw new ApiException(409, "This schedule has bookings, so its bus and route cannot be changed.");
        LocalDate newDate = parseDate(b.str("travelDate"));
        if (hasBookings && !newDate.equals(s.travelDate))
            throw new ApiException(409, "This schedule has bookings, so its date cannot be changed. Ask customers to reschedule instead.");
        String newDeparture = parseTime(b.str("departureTime"), "Departure");
        String newArrival = parseTime(b.str("arrivalTime"), "Arrival");
        String newStatus = canon(b.str("status"), SCHEDULE_STATUS, "schedule status");
        boolean changesHeldJourney = !newBus.equals(s.busId) || !newRoute.equals(s.routeId)
                || !newDate.equals(s.travelDate) || !newDeparture.equals(s.departureTime)
                || !newArrival.equals(s.arrivalTime) || !newStatus.equals(s.status);
        if (changesHeldJourney && hasActiveSeatHoldsForSchedule(id))
            throw new ApiException(409, "Customers currently have seats on hold for this schedule. Wait for those holds to expire before changing its route, time, fare, bus, or status.");

        if (!newBus.equals(s.busId)) s.totalSeats = buses.findById(newBus).orElseThrow().totalSeats;
        s.busId = newBus;
        s.routeId = newRoute;
        s.travelDate = newDate;
        s.departureTime = newDeparture;
        s.arrivalTime = newArrival;
        // Fare is read from Stop Management. Ignore any legacy fare value sent by older clients.
        s.fare = Optional.ofNullable(findFareValue(s.routeId, "ORIGIN", "DESTINATION")).orElse(0.0);
        s.status = newStatus;
        checkDuplicate(s);
        return dto(schedules.save(s));
    }

    @Transactional
    public void deleteSchedule(Long id) {
        if (!schedules.existsById(id)) throw new ApiException(404, "Schedule not found.");
        if (bookings.existsByScheduleId(id))
            throw new ApiException(409, "This schedule has bookings. Set its status to Closed instead of deleting it.");
        if (hasActiveSeatHoldsForSchedule(id))
            throw new ApiException(409, "Customers currently have seats on hold for this schedule. Wait for those holds to expire before deleting it.");
        tracking.deleteById(id);
        schedules.deleteById(id);
    }
}
