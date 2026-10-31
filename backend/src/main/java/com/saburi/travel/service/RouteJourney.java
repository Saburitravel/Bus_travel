package com.saburi.travel.service;

import com.saburi.travel.model.Route;
import com.saburi.travel.model.RouteStop;
import com.saburi.travel.model.RouteFare;
import com.saburi.travel.model.Schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Resolves ordered journey endpoints. Production booking uses an exact RouteFare pair;
 * the older overload remains only for compatibility with historical unit tests/data tooling. */
public final class RouteJourney {
    private RouteJourney() {}

    public static final class Journey {
        public final Route route;
        public final List<RouteStop> routeStops;
        public final List<String> points;
        public final int fromIndex;
        public final int toIndex;
        public final String from;
        public final String to;
        public final List<Double> edgeFares;
        public final Double fare;

        private Journey(Route route, List<RouteStop> routeStops, List<String> points,
                        int fromIndex, int toIndex, List<Double> edgeFares, Double fare) {
            this.route = route;
            this.routeStops = List.copyOf(routeStops);
            this.points = List.copyOf(points);
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
            this.from = points.get(fromIndex);
            this.to = points.get(toIndex);
            this.edgeFares = edgeFares == null ? List.of() : List.copyOf(edgeFares);
            this.fare = fare;
        }

        public boolean isFullRoute() { return fromIndex == 0 && toIndex == routeStops.size() + 1; }
        public List<String> selectedPoints() { return points.subList(fromIndex, toIndex + 1); }
        public List<Integer> edgeIndices() {
            List<Integer> edges = new ArrayList<>();
            for (int i = fromIndex; i < toIndex; i++) edges.add(i);
            return edges;
        }
        public boolean covers(int from, int to) { return fromIndex < to && from < toIndex; }
    }

    @Deprecated(forRemoval = false)
    public static Journey resolve(Route route, List<RouteStop> rawStops, Schedule schedule,
                                  String requestedFrom, String requestedTo) {
        if (route == null || schedule == null || !Double.isFinite(schedule.fare) || schedule.fare <= 0) return null;
        List<RouteStop> routeStops = new ArrayList<>(rawStops == null ? List.of() : rawStops);
        routeStops.sort(Comparator.comparingInt(s -> s.stopOrder));
        List<String> points = new ArrayList<>();
        points.add(display(route.origin));
        for (RouteStop stop : routeStops) points.add(displayStop(stop));
        points.add(display(route.destination));

        String from = requestedFrom == null || requestedFrom.isBlank() ? route.origin : requestedFrom;
        String to = requestedTo == null || requestedTo.isBlank() ? route.destination : requestedTo;
        int fromIndex = locate(from, route, routeStops);
        int toIndex = locate(to, route, routeStops);
        if (fromIndex < 0 || toIndex < 0 || toIndex <= fromIndex) return null;

        List<Double> edgeFares = edgeFares(routeStops, schedule.fare);
        Double fare = null;
        if (fromIndex == 0 && toIndex == routeStops.size() + 1) {
            fare = schedule.fare;
        } else if (edgeFares != null) {
            double total = 0;
            for (int i = fromIndex; i < toIndex; i++) total += edgeFares.get(i);
            fare = roundMoney(total);
        }
        return new Journey(route, routeStops, points, fromIndex, toIndex, edgeFares, fare);
    }

    /** Resolves an exact, independently configured boarding-to-destination fare. No fare is derived from other legs. */
    public static Journey resolve(Route route, List<RouteStop> rawStops, Schedule schedule,
                                  String requestedFrom, String requestedTo, List<RouteFare> configuredFares) {
        if (route == null || schedule == null) return null;
        List<RouteStop> routeStops = new ArrayList<>(rawStops == null ? List.of() : rawStops);
        routeStops.sort(Comparator.comparingInt(s -> s.stopOrder));
        List<String> points = new ArrayList<>();
        points.add(display(route.origin));
        for (RouteStop stop : routeStops) points.add(displayStop(stop));
        points.add(display(route.destination));

        String from = requestedFrom == null || requestedFrom.isBlank() ? route.origin : requestedFrom;
        String to = requestedTo == null || requestedTo.isBlank() ? route.destination : requestedTo;
        int fromIndex = locate(from, route, routeStops);
        int toIndex = locate(to, route, routeStops);
        if (fromIndex < 0 || toIndex < 0 || toIndex <= fromIndex) return null;

        String fromKey = pointKey(fromIndex, routeStops);
        String toKey = pointKey(toIndex, routeStops);
        Double fare = null;
        if (configuredFares != null) {
            // An explicitly configured fare for this exact pair takes precedence.
            for (RouteFare configured : configuredFares) {
                if (configured != null && Objects.equals(configured.fromKey, fromKey)
                        && Objects.equals(configured.toKey, toKey)
                        && Double.isFinite(configured.fare) && configured.fare > 0) {
                    fare = roundMoney(configured.fare);
                    break;
                }
            }
            // Otherwise calculate the journey by summing every consecutive configured segment.
            if (fare == null) {
                double total = 0;
                boolean complete = true;
                for (int edge = fromIndex; edge < toIndex; edge++) {
                    String edgeFrom = pointKey(edge, routeStops);
                    String edgeTo = pointKey(edge + 1, routeStops);
                    Double segmentFare = null;
                    for (RouteFare configured : configuredFares) {
                        if (configured != null && Objects.equals(configured.fromKey, edgeFrom)
                                && Objects.equals(configured.toKey, edgeTo)
                                && Double.isFinite(configured.fare) && configured.fare > 0) {
                            segmentFare = configured.fare;
                            break;
                        }
                    }
                    if (segmentFare == null) { complete = false; break; }
                    total += segmentFare;
                }
                if (complete) fare = roundMoney(total);
            }
        }
        return new Journey(route, routeStops, points, fromIndex, toIndex, List.of(), fare);
    }

    public static String pointKey(int pointIndex, List<RouteStop> orderedStops) {
        if (pointIndex == 0) return "ORIGIN";
        if (orderedStops != null && pointIndex == orderedStops.size() + 1) return "DESTINATION";
        if (orderedStops == null || pointIndex < 1 || pointIndex > orderedStops.size()) return "";
        RouteStop stop = orderedStops.get(pointIndex - 1);
        return stop.id == null ? "" : "STOP:" + stop.id;
    }

    /** Builds all consecutive segment fares or returns null if configuration is incomplete/invalid. */
    public static List<Double> edgeFares(List<RouteStop> routeStops, double fullRouteFare) {
        if (routeStops == null || routeStops.isEmpty()) return List.of(roundMoney(fullRouteFare));
        if (!Double.isFinite(fullRouteFare) || fullRouteFare <= 0) return null;
        List<Double> result = new ArrayList<>();
        double sum = 0;
        for (RouteStop stop : routeStops) {
            Double segment = stop.segmentFare;
            if (segment == null || !Double.isFinite(segment) || segment <= 0) return null;
            double normalized = roundMoney(segment);
            if (normalized <= 0) return null;
            result.add(normalized);
            sum += normalized;
        }
        double remainder = roundMoney(fullRouteFare - sum);
        // Every route segment must have a positive fare; the configured legs cannot consume or exceed the full fare.
        if (remainder <= 0) return null;
        result.add(remainder);
        double total = result.stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(total - roundMoney(fullRouteFare)) > 0.01) return null;
        return result;
    }

    private static int locate(String requested, Route route, List<RouteStop> stops) {
        String key = norm(requested);
        if (key.isEmpty()) return -1;
        List<Integer> matches = new ArrayList<>();
        if (norm(route.origin).equals(key)) matches.add(0);
        for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            if (norm(stop.name).equals(key) || norm(stop.city).equals(key)) matches.add(i + 1);
        }
        if (norm(route.destination).equals(key)) matches.add(stops.size() + 1);
        Set<Integer> distinct = new HashSet<>(matches);
        return distinct.size() == 1 ? distinct.iterator().next() : -1;
    }

    public static String displayStop(RouteStop stop) {
        if (stop == null) return "";
        return display(stop.city == null || stop.city.isBlank() ? stop.name : stop.city);
    }

    private static String display(String value) { return value == null ? "" : value.trim(); }
    private static String norm(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static double roundMoney(double value) { return Math.round(value * 100.0) / 100.0; }
}
