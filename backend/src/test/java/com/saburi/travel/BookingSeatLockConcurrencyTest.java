package com.saburi.travel;

import com.saburi.travel.model.Bus;
import com.saburi.travel.model.Route;
import com.saburi.travel.model.Schedule;
import com.saburi.travel.model.SeatHold;
import com.saburi.travel.model.SeatSegmentLock;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.service.BookingService;
import com.saburi.travel.service.JourneyService;
import com.saburi.travel.service.RouteJourney;
import com.saburi.travel.service.SettingService;
import com.saburi.travel.util.ApiException;
import com.saburi.travel.util.Body;
import com.saburi.travel.util.Time;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingSeatLockConcurrencyTest {
    @Test
    void concurrentHoldsForSameScheduleSeatAndSegmentAllowOnlyOneWinner() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 10, 26, 12, 0);
        Schedule schedule = new Schedule();
        schedule.id = 10L;
        schedule.busId = 20L;
        schedule.routeId = 30L;
        schedule.travelDate = LocalDate.now().plusDays(1);
        schedule.departureTime = "10:00";
        schedule.totalSeats = 40;
        schedule.fare = 300;
        schedule.status = "Open";
        Bus bus = new Bus();
        bus.id = 20L;
        bus.status = "Active";
        Route route = new Route();
        route.id = 30L;
        route.origin = "Origin";
        route.destination = "Destination";
        RouteJourney.Journey journey = RouteJourney.resolve(route, List.of(), schedule, "Origin", "Destination");

        Repos.ScheduleRepo schedules = mock(Repos.ScheduleRepo.class);
        Repos.BusRepo buses = mock(Repos.BusRepo.class);
        Repos.RouteRepo routes = mock(Repos.RouteRepo.class);
        Repos.BookingRepo bookings = mock(Repos.BookingRepo.class);
        Repos.SeatRepo seats = mock(Repos.SeatRepo.class);
        Repos.HoldRepo holds = mock(Repos.HoldRepo.class);
        Repos.SegmentLockRepo segmentLocks = mock(Repos.SegmentLockRepo.class);
        JourneyService journeys = mock(JourneyService.class);
        Repos.PaymentRepo payments = mock(Repos.PaymentRepo.class);
        Repos.RefundRepo refunds = mock(Repos.RefundRepo.class);
        Time time = mock(Time.class);
        SettingService settings = mock(SettingService.class);
        when(schedules.findById(10L)).thenReturn(Optional.of(schedule));
        when(buses.findById(20L)).thenReturn(Optional.of(bus));
        when(time.now()).thenReturn(now);
        when(time.departure(schedule)).thenReturn(now.plusHours(20));
        when(settings.flag("maintenanceMode")).thenReturn(false);
        when(journeys.require(eq(schedule), eq("Origin"), eq("Destination"))).thenReturn(journey);
        when(journeys.activeHeldSeatCount(anyString(), any(LocalDateTime.class))).thenReturn(0L);
        when(journeys.heldSeatCountForToken(anyString())).thenReturn(0L);
        when(segmentLocks.findByClientIpAndExpiresAtAfterAndBookingIdIsNull(anyString(), any(LocalDateTime.class))).thenReturn(List.of());
        when(segmentLocks.findByToken(anyString())).thenReturn(List.of());
        when(holds.findByToken(anyString())).thenReturn(List.of());
        when(holds.countByClientIpAndExpiresAtAfter(anyString(), any(LocalDateTime.class))).thenReturn(0L);

        List<SeatSegmentLock> storedLocks = new ArrayList<>();
        Object lockMonitor = new Object();
        AtomicLong ids = new AtomicLong();
        CyclicBarrier bothReadFree = new CyclicBarrier(2);
        doAnswer(invocation -> { bothReadFree.await(5, TimeUnit.SECONDS); return null; })
                .when(journeys).assertSeatsAvailable(eq(schedule), eq(journey), eq(List.of(1)), anyString());
        when(segmentLocks.save(any(SeatSegmentLock.class))).thenAnswer(invocation -> {
            SeatSegmentLock candidate = invocation.getArgument(0);
            synchronized (lockMonitor) {
                if (storedLocks.stream().anyMatch(row -> row.scheduleId.equals(candidate.scheduleId)
                        && row.seatNo == candidate.seatNo && row.segmentIndex == candidate.segmentIndex))
                    throw new DataIntegrityViolationException("duplicate schedule seat segment lock");
                candidate.id = ids.incrementAndGet();
                storedLocks.add(candidate);
                return candidate;
            }
        });
        BookingService service = new BookingService(schedules, buses, routes, bookings, seats, holds, segmentLocks, journeys,
                payments, refunds, time, settings, 120, 24, 6, "");
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> attemptHold(service, start, "192.0.2.1"));
            Future<Boolean> second = executor.submit(() -> attemptHold(service, start, "192.0.2.2"));
            start.countDown();

            boolean firstWon = first.get(10, TimeUnit.SECONDS);
            boolean secondWon = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon);
            assertEquals(1, storedLocks.size());
            assertEquals(10L, storedLocks.get(0).scheduleId);
            assertEquals(1, storedLocks.get(0).seatNo);
            assertEquals(0, storedLocks.get(0).segmentIndex);
        }
    }

    private static boolean attemptHold(BookingService service, CountDownLatch start, String clientIp) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            service.hold(new Body(Map.of("scheduleId", 10L, "from", "Origin", "to", "Destination", "seats", List.of("1"))), clientIp);
            return true;
        } catch (ApiException e) {
            assertEquals(409, e.status);
            return false;
        }
    }
}
