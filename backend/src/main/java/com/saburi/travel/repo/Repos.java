package com.saburi.travel.repo;

import com.saburi.travel.model.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class Repos {
    private Repos() {}

    public interface UserRepo extends MongoRepository<AppUser, Long> {
        Optional<AppUser> findByPhone(String phone);
        Optional<AppUser> findByEmailIgnoreCase(String email);
        List<AppUser> findAllByOrderByIdDesc();
    }

    public interface BusRepo extends MongoRepository<Bus, Long> {
        Optional<Bus> findByBusNumberIgnoreCase(String busNumber);
    }

    public interface RouteRepo extends MongoRepository<Route, Long> {
        Optional<Route> findByRouteCodeIgnoreCase(String routeCode);
    }

    public interface StopRepo extends MongoRepository<RouteStop, Long> {
        List<RouteStop> findByRouteIdOrderByStopOrder(Long routeId);
        List<RouteStop> findByRouteId(Long routeId);
        @Transactional void deleteByRouteId(Long routeId);
    }

    public interface RouteFareRepo extends MongoRepository<RouteFare, Long> {
        List<RouteFare> findByRouteId(Long routeId);
        Optional<RouteFare> findByRouteIdAndFromKeyAndToKey(Long routeId, String fromKey, String toKey);
        @Transactional void deleteByRouteId(Long routeId);
    }

    public interface ScheduleRepo extends MongoRepository<Schedule, Long> {
        List<Schedule> findByTravelDate(LocalDate date);
        List<Schedule> findByRouteId(Long routeId);
        List<Schedule> findByTravelDateGreaterThanEqual(LocalDate date);
        List<Schedule> findByBusIdAndTravelDate(Long busId, LocalDate date);
        boolean existsByBusId(Long busId);
        boolean existsByRouteId(Long routeId);
    }

    public interface BookingRepo extends MongoRepository<Booking, Long> {
        List<Booking> findByUserIdOrderByIdDesc(Long userId);
        List<Booking> findByCustomerPhoneOrderByIdDesc(String customerPhone);
        List<Booking> findAllByOrderByIdDesc();
        boolean existsByScheduleId(Long scheduleId);
        List<Booking> findByScheduleId(Long scheduleId);
        long countByUserId(Long userId);
    }

    public interface SeatRepo extends MongoRepository<BookingSeat, Long> {
        List<BookingSeat> findByScheduleIdAndLockKeyIsNotNull(Long scheduleId);
        List<BookingSeat> findByBookingIdOrderBySeatNo(Long bookingId);
        List<BookingSeat> findByBookingIdIn(Collection<Long> bookingIds);

        default List<Object[]> activeCounts() {
            java.util.Map<Long, Long> counts = new java.util.HashMap<>();
            for (BookingSeat seat : findAll()) if (seat.lockKey != null) counts.merge(seat.scheduleId, 1L, Long::sum);
            return counts.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toList();
        }
    }

    public interface HoldRepo extends MongoRepository<SeatHold, Long> {
        List<SeatHold> findByScheduleIdAndExpiresAtAfter(Long scheduleId, LocalDateTime t);
        List<SeatHold> findByScheduleIdAndBookingIdIsNotNull(Long scheduleId);
        List<SeatHold> findByScheduleId(Long scheduleId);
        List<SeatHold> findByToken(String token);
        List<SeatHold> findByBookingId(Long bookingId);
        long countByClientIpAndExpiresAtAfter(String clientIp, LocalDateTime t);
        @Transactional void deleteByToken(String token);
        @Transactional void deleteByBookingId(Long bookingId);
        @Transactional void deleteByExpiresAtBefore(LocalDateTime t);

        default List<Object[]> activeCounts(LocalDateTime now) {
            java.util.Map<Long, Long> counts = new java.util.HashMap<>();
            for (SeatHold hold : findAll()) if (hold.expiresAt != null && hold.expiresAt.isAfter(now)) counts.merge(hold.scheduleId, 1L, Long::sum);
            return counts.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toList();
        }
    }

    public interface SegmentLockRepo extends MongoRepository<SeatSegmentLock, Long> {
        List<SeatSegmentLock> findByScheduleId(Long scheduleId);
        List<SeatSegmentLock> findByToken(String token);
        List<SeatSegmentLock> findByBookingId(Long bookingId);
        List<SeatSegmentLock> findByClientIpAndExpiresAtAfterAndBookingIdIsNull(String clientIp, LocalDateTime now);
        @Transactional void deleteByToken(String token);
        @Transactional void deleteByBookingId(Long bookingId);
        @Transactional void deleteByBookingIdAndScheduleId(Long bookingId, Long scheduleId);
        @Transactional void deleteByExpiresAtBeforeAndBookingIdIsNull(LocalDateTime now);
    }

    public interface PaymentRepo extends MongoRepository<Payment, Long> {
        List<Payment> findAllByOrderByIdDesc();
        List<Payment> findByBookingIdIn(Collection<Long> bookingIds);
    }

    public interface RefundRepo extends MongoRepository<Refund, Long> {
        List<Refund> findAllByOrderByIdDesc();
        List<Refund> findByBookingIdIn(Collection<Long> bookingIds);
    }

    public interface SessionRepo extends MongoRepository<AuthSession, String> {
        @Transactional void deleteByExpiresAtBefore(LocalDateTime t);
    }

    public interface TicketRepo extends MongoRepository<SupportTicket, Long> {}
    public interface SettingRepo extends MongoRepository<Setting, String> {}
    public interface TrackingRepo extends MongoRepository<TrackingState, Long> {}
}
