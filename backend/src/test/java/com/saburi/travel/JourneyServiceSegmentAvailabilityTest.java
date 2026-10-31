package com.saburi.travel;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saburi.travel.model.Route;
import com.saburi.travel.model.RouteStop;
import com.saburi.travel.model.Schedule;
import com.saburi.travel.model.SeatHold;
import com.saburi.travel.model.SeatSegmentLock;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.service.JourneyService;
import com.saburi.travel.service.RouteJourney;
import com.saburi.travel.util.Time;

class JourneyServiceSegmentAvailabilityTest {
    private record Fixture(JourneyService service, Schedule schedule, RouteJourney.Journey yeolaToVinchur,
                           RouteJourney.Journey vinchurToLasalgaon) {}

    private Fixture fixture(SeatSegmentLock lock) {
        Route route = new Route();
        route.id = 4L; route.origin = "Yeola"; route.destination = "Lasalgaon";
        RouteStop vinchur = new RouteStop();
        vinchur.id = 2L; vinchur.routeId = 4L; vinchur.stopOrder = 1;
        vinchur.name = "Vinchur"; vinchur.city = "Vinchur"; vinchur.segmentFare = 120.0;
        Schedule schedule = new Schedule();
        schedule.id = 10L; schedule.routeId = 4L; schedule.fare = 300.0; schedule.totalSeats = 40;

        Repos.RouteRepo routes = mock(Repos.RouteRepo.class);
        Repos.StopRepo stops = mock(Repos.StopRepo.class);
        Repos.RouteFareRepo routeFares = mock(Repos.RouteFareRepo.class);
        Repos.BookingRepo bookings = mock(Repos.BookingRepo.class);
        Repos.SeatRepo seats = mock(Repos.SeatRepo.class);
        Repos.HoldRepo legacyHolds = mock(Repos.HoldRepo.class);
        Repos.SegmentLockRepo segmentLocks = mock(Repos.SegmentLockRepo.class);
        Time time = mock(Time.class);
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        when(time.now()).thenReturn(now);
        when(routes.findById(4L)).thenReturn(java.util.Optional.of(route));
        when(stops.findByRouteIdOrderByStopOrder(4L)).thenReturn(List.of(vinchur));
        when(segmentLocks.findByScheduleId(10L)).thenReturn(lock == null ? List.of() : List.of(lock));
        when(legacyHolds.findByScheduleIdAndBookingIdIsNotNull(10L)).thenReturn(List.<SeatHold>of());
        when(legacyHolds.findByScheduleIdAndExpiresAtAfter(eq(10L), any(LocalDateTime.class))).thenReturn(List.<SeatHold>of());
        when(seats.findByScheduleIdAndLockKeyIsNotNull(10L)).thenReturn(List.of());
        JourneyService service = new JourneyService(routes, stops, routeFares, bookings, seats, legacyHolds, segmentLocks, time);
        RouteJourney.Journey first = service.resolve(schedule, "Yeola", "Vinchur");
        RouteJourney.Journey second = service.resolve(schedule, "Vinchur", "Lasalgaon");
        return new Fixture(service, schedule, first, second);
    }

    @Test
    void sameSeatIsAvailableOnTheNextNonOverlappingSegment() {
        SeatSegmentLock booked = new SeatSegmentLock();
        booked.scheduleId = 10L; booked.seatNo = 12; booked.segmentIndex = 0; booked.bookingId = 77L;
        Fixture f = fixture(booked);

        assertEquals("BOOKED", f.service().seatStatuses(f.schedule(), f.yeolaToVinchur(), null).get(12));
        assertEquals("AVAILABLE", f.service().seatStatuses(f.schedule(), f.vinchurToLasalgaon(), null).get(12));
        assertEquals("BOOKED", f.service().seatStatuses(f.schedule(),
                f.service().resolve(f.schedule(), "Yeola", "Lasalgaon"), null).get(12));
    }

    @Test
    void temporaryHoldBlocksOnlyJourneysThatOverlapItsSegment() {
        SeatSegmentLock hold = new SeatSegmentLock();
        hold.scheduleId = 10L; hold.seatNo = 12; hold.segmentIndex = 0; hold.token = "hold-token";
        hold.expiresAt = LocalDateTime.of(2026, 10, 27, 12, 2); hold.clientIp = "192.0.2.1";
        Fixture f = fixture(hold);

        assertEquals("HELD", f.service().seatStatuses(f.schedule(), f.yeolaToVinchur(), null).get(12));
        assertEquals("AVAILABLE", f.service().seatStatuses(f.schedule(), f.vinchurToLasalgaon(), null).get(12));
        assertEquals("AVAILABLE", f.service().seatStatuses(f.schedule(), f.yeolaToVinchur(), "hold-token").get(12));
    }
}
