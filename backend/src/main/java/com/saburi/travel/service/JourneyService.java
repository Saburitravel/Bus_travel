package com.saburi.travel.service;

import com.saburi.travel.model.Booking;
import com.saburi.travel.model.BookingSeat;
import com.saburi.travel.model.Route;
import com.saburi.travel.model.RouteStop;
import com.saburi.travel.model.Schedule;
import com.saburi.travel.model.SeatHold;
import com.saburi.travel.model.SeatSegmentLock;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.util.ApiException;
import com.saburi.travel.util.Time;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared server-side journey, fare, and segment seat-availability rules. */
@Service
public class JourneyService {
    private final Repos.RouteRepo routes;
    private final Repos.StopRepo stops;
    private final Repos.RouteFareRepo routeFares;
    private final Repos.BookingRepo bookings;
    private final Repos.SeatRepo seats;
    private final Repos.HoldRepo legacyHolds;
    private final Repos.SegmentLockRepo segmentLocks;
    private final Time time;

    public JourneyService(Repos.RouteRepo routes, Repos.StopRepo stops, Repos.RouteFareRepo routeFares,
                          Repos.BookingRepo bookings, Repos.SeatRepo seats, Repos.HoldRepo legacyHolds,
                          Repos.SegmentLockRepo segmentLocks, Time time) {
        this.routes = routes;
        this.stops = stops;
        this.routeFares = routeFares;
        this.bookings = bookings;
        this.seats = seats;
        this.legacyHolds = legacyHolds;
        this.segmentLocks = segmentLocks;
        this.time = time;
    }

    public RouteJourney.Journey resolve(Schedule schedule, String from, String to) {
        Route route = routes.findById(schedule.routeId).orElse(null);
        if (route == null) return null;
        List<RouteStop> routeStops = stops.findByRouteIdOrderByStopOrder(route.id);
        return RouteJourney.resolve(route, routeStops, schedule, from, to,
                routeFares == null ? List.of() : routeFares.findByRouteId(route.id));
    }

    public RouteJourney.Journey require(Schedule schedule, String from, String to) {
        RouteJourney.Journey journey = resolve(schedule, from, to);
        if (journey == null) throw new ApiException(400, "Choose valid boarding and destination stops in the correct route order.");
        if (journey.fare == null)
            throw new ApiException(409, "A fare has not been configured for this boarding and destination pair. Ask the admin to add it in Stop Management.");
        return journey;
    }

    public List<RouteStop> stopsForRoute(Long routeId) {
        return stops.findByRouteIdOrderByStopOrder(routeId);
    }

    public long activeHeldSeatCount(String clientIp, LocalDateTime now) {
        return segmentLocks.findByClientIpAndExpiresAtAfterAndBookingIdIsNull(clientIp, now).stream()
                .map(lock -> lock.scheduleId + ":" + lock.seatNo + ":" + lock.token)
                .distinct().count();
    }

    public long heldSeatCountForToken(String token) {
        if (token == null || token.isBlank()) return 0;
        return segmentLocks.findByToken(token).stream()
                .filter(lock -> lock.bookingId == null && lock.expiresAt != null && lock.expiresAt.isAfter(time.now()))
                .map(lock -> lock.scheduleId + ":" + lock.seatNo)
                .distinct().count();
    }

    public void purgeExpiredSegmentHolds(LocalDateTime now) {
        segmentLocks.deleteByExpiresAtBeforeAndBookingIdIsNull(now);
    }

    public Map<Integer, String> seatStatuses(Schedule schedule, RouteJourney.Journey journey, String ownToken) {
        LocalDateTime now = time.now();
        Set<Integer> pathEdges = new HashSet<>(journey.edgeIndices());
        Map<Integer, String> status = new HashMap<>();
        for (int n = 1; n <= schedule.totalSeats; n++) status.put(n, "AVAILABLE");

        List<SeatSegmentLock> lockRows = segmentLocks.findByScheduleId(schedule.id);
        Set<String> segmentCoveredBookingSeats = new HashSet<>();
        for (SeatSegmentLock lock : lockRows) {
            if (lock.bookingId != null) segmentCoveredBookingSeats.add(lock.bookingId + ":" + lock.seatNo);
            if (!pathEdges.contains(lock.segmentIndex)) continue;
            if (lock.bookingId != null || (lock.token == null && lock.expiresAt == null)) {
                status.put(lock.seatNo, "BOOKED");
            } else if (lock.expiresAt != null && lock.expiresAt.isAfter(now)
                    && (ownToken == null || !ownToken.equals(lock.token))) {
                if (!"BOOKED".equals(status.get(lock.seatNo))) status.put(lock.seatNo, "HELD");
            }
        }

        // Legacy permanent locks are schedule-wide because they have no segment metadata.
        for (SeatHold legacy : legacyHolds.findByScheduleIdAndBookingIdIsNotNull(schedule.id)) {
            if (!segmentCoveredBookingSeats.contains(legacy.bookingId + ":" + legacy.seatNo))
                status.put(legacy.seatNo, "BOOKED");
        }
        // Existing temporary holds are also conservatively treated as whole-schedule holds until they expire.
        for (SeatHold legacy : legacyHolds.findByScheduleIdAndExpiresAtAfter(schedule.id, now)) {
            if (ownToken == null || !ownToken.equals(legacy.token)) {
                if (!"BOOKED".equals(status.get(legacy.seatNo))) status.put(legacy.seatNo, "HELD");
            }
        }

        // Preserve safety for bookings created before segment locks existed. Use their saved journey endpoints
        // when possible; if the legacy endpoints cannot be resolved, block the entire route for that seat.
        for (BookingSeat seat : seats.findByScheduleIdAndLockKeyIsNotNull(schedule.id)) {
            String key = seat.bookingId + ":" + seat.seatNo;
            if (segmentCoveredBookingSeats.contains(key)) continue;
            Booking booking = seat.bookingId == null ? null : bookings.findById(seat.bookingId).orElse(null);
            boolean overlaps = true;
            if (booking != null) {
                int fromIndex = -1, toIndex = -1;
                int edgeCount = journey.routeStops.size() + 1;
                if (booking.journeyFromIndex != null && booking.journeyToIndex != null
                        && booking.journeyFromIndex >= 0 && booking.journeyToIndex <= edgeCount
                        && booking.journeyToIndex > booking.journeyFromIndex) {
                    fromIndex = booking.journeyFromIndex;
                    toIndex = booking.journeyToIndex;
                } else {
                    String oldFrom = booking.journeyFrom == null || booking.journeyFrom.isBlank()
                            ? journey.route.origin : booking.journeyFrom;
                    String oldTo = booking.journeyTo == null || booking.journeyTo.isBlank()
                            ? journey.route.destination : booking.journeyTo;
                    RouteJourney.Journey oldJourney = resolve(schedule, oldFrom, oldTo);
                    if (oldJourney != null) {
                        fromIndex = oldJourney.fromIndex;
                        toIndex = oldJourney.toIndex;
                    }
                }
                if (fromIndex >= 0 && toIndex > fromIndex) {
                    overlaps = fromIndex < journey.toIndex && journey.fromIndex < toIndex;
                }
            }
            if (overlaps) status.put(seat.seatNo, "BOOKED");
        }
        return status;
    }

    public void assertSeatsAvailable(Schedule schedule, RouteJourney.Journey journey,
                                     List<Integer> wantedSeats, String ownToken) {
        Map<Integer, String> statuses = seatStatuses(schedule, journey, ownToken);
        for (int seat : wantedSeats) {
            String current = statuses.getOrDefault(seat, "UNAVAILABLE");
            if ("BOOKED".equals(current))
                throw new ApiException(409, "Seat " + seat + " is already booked for an overlapping part of this journey. Please choose another seat.");
            if ("HELD".equals(current))
                throw new ApiException(409, "Seat " + seat + " is temporarily held for an overlapping part of this journey. Please choose another seat.");
            if (!"AVAILABLE".equals(current)) throw new ApiException(409, "Seat " + seat + " is not available.");
        }
    }

    public int availableSeatCount(Schedule schedule, RouteJourney.Journey journey, String ownToken) {
        return (int) seatStatuses(schedule, journey, ownToken).values().stream().filter("AVAILABLE"::equals).count();
    }

    public static String lockPairKey(Long scheduleId, int seatNo, int segmentIndex) {
        return scheduleId + ":" + seatNo + ":" + segmentIndex;
    }

    public static Set<String> expectedLockKeys(Long scheduleId, List<Integer> seats, RouteJourney.Journey journey) {
        Set<String> expected = new HashSet<>();
        for (int seat : seats) for (int edge : journey.edgeIndices()) expected.add(lockPairKey(scheduleId, seat, edge));
        return expected;
    }

    public static Set<String> tokenLockKeys(List<SeatSegmentLock> rows, Long scheduleId,
                                            List<Integer> seats, RouteJourney.Journey journey,
                                            LocalDateTime now, String token) {
        return rows.stream()
                .filter(row -> scheduleId.equals(row.scheduleId) && seats.contains(row.seatNo)
                        && row.bookingId == null && token.equals(row.token)
                        && row.expiresAt != null && row.expiresAt.isAfter(now))
                .map(row -> lockPairKey(row.scheduleId, row.seatNo, row.segmentIndex))
                .collect(Collectors.toSet());
    }

    public static String norm(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
