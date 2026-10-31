package com.saburi.travel.service;

import com.saburi.travel.model.*;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.util.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminService {
    private final Repos.UserRepo users;
    private final Repos.PaymentRepo payments;
    private final Repos.RefundRepo refunds;
    private final Repos.SeatRepo seats;
    private final Repos.ScheduleRepo schedules;
    private final BookingService bookingService;
    private final CatalogService catalog;
    private final SettingService settings;

    public AdminService(Repos.UserRepo users, Repos.PaymentRepo payments, Repos.RefundRepo refunds, Repos.SeatRepo seats,
                        Repos.ScheduleRepo schedules, BookingService bookingService, CatalogService catalog, SettingService settings) {
        this.users = users; this.payments = payments; this.refunds = refunds; this.seats = seats; this.schedules = schedules;
        this.bookingService = bookingService; this.catalog = catalog; this.settings = settings;
    }

    public List<Map<String, Object>> users() {
        return users.findAllByOrderByIdDesc().stream().map(J::user).collect(Collectors.toList());
    }

    public Map<String, Object> setUserStatus(Long id, String status) {
        AppUser u = users.findById(id).orElseThrow(() -> new ApiException(404, "Customer not found."));
        if (!"ACTIVE".equalsIgnoreCase(status) && !"INACTIVE".equalsIgnoreCase(status)) throw new ApiException(400, "Status must be ACTIVE or INACTIVE.");
        u.status = status.toUpperCase(Locale.ROOT);
        return J.user(users.save(u));
    }

    public List<Map<String, Object>> payments() {
        return payments.findAllByOrderByIdDesc().stream().map(p -> J.map("id", p.id, "bookingId", p.bookingId,
                "booking", BookingService.displayId(p.bookingId), "amount", p.amount, "method", p.method, "status", p.status,
                "createdAt", p.createdAt == null ? "" : p.createdAt.toString())).collect(Collectors.toList());
    }

    public List<Map<String, Object>> refunds() {
        return refunds.findAllByOrderByIdDesc().stream().map(r -> J.map("id", r.id, "bookingId", r.bookingId,
                "booking", BookingService.displayId(r.bookingId), "amount", r.amount, "status", r.status, "reason", r.reason == null ? "" : r.reason,
                "createdAt", r.createdAt == null ? "" : r.createdAt.toString())).collect(Collectors.toList());
    }

    public Object report(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "booking":
                return bookingService.allBookings();
            case "cancellation":
                return bookingService.allBookings().stream().filter(b -> "Cancelled".equals(b.get("status"))).collect(Collectors.toList());
            case "revenue": {
                long gross = 0, refunded = 0;
                Map<String, Long> byDate = new TreeMap<>();
                for (Payment p : payments.findAll()) {
                    if (!"SUCCESS".equals(p.status)) continue;
                    gross += p.amount;
                    byDate.merge(p.createdAt == null ? "unknown" : p.createdAt.toLocalDate().toString(), p.amount, Long::sum);
                }
                for (Refund r : refunds.findAll()) if ("REFUNDED".equals(r.status)) refunded += r.amount;
                return J.map("grossRevenue", gross, "refunded", refunded, "netRevenue", gross - refunded, "byDate", byDate);
            }
            case "occupancy": {
                Map<Long, Integer> booked = new HashMap<>();
                for (Object[] o : seats.activeCounts()) booked.put(((Number) o[0]).longValue(), ((Number) o[1]).intValue());
                Map<Long, Bus> bm = catalog.busMap();
                Map<Long, Route> rm = catalog.routeMap();
                List<Map<String, Object>> out = new ArrayList<>();
                List<Schedule> all = new ArrayList<>(schedules.findAll());
                all.sort(Comparator.comparing((Schedule s) -> s.travelDate).thenComparing(s -> s.departureTime));
                for (Schedule s : all) {
                    Bus b = bm.get(s.busId);
                    Route r = rm.get(s.routeId);
                    if (b == null || r == null) continue;
                    int n = booked.getOrDefault(s.id, 0);
                    out.add(J.map("scheduleId", s.id, "bus", b.busNumber, "route", r.origin + " → " + r.destination,
                            "date", s.travelDate.toString(), "depart", s.departureTime, "totalSeats", s.totalSeats, "bookedSeats", n,
                            "occupancyPercent", s.totalSeats == 0 ? 0 : Math.round(n * 100.0 / s.totalSeats)));
                }
                return out;
            }
            default:
                throw new ApiException(404, "Unknown report type.");
        }
    }

    public Map<String, Object> settings() { return settings.allFlags(); }

    public Map<String, Object> saveSettings(Body b) {
        for (String k : SettingService.KEYS) if (b.present(k)) settings.put(k, String.valueOf(b.bool(k)));
        return settings.allFlags();
    }
}
