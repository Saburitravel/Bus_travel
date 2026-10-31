package com.saburi.travel.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.saburi.travel.model.AppUser;
import com.saburi.travel.model.Booking;
import com.saburi.travel.model.BookingSeat;
import com.saburi.travel.model.Bus;
import com.saburi.travel.model.Payment;
import com.saburi.travel.model.Refund;
import com.saburi.travel.model.Route;
import com.saburi.travel.model.Schedule;
import com.saburi.travel.model.SeatHold;
import com.saburi.travel.model.SeatSegmentLock;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.util.ApiException;
import com.saburi.travel.util.Body;
import com.saburi.travel.util.J;
import com.saburi.travel.util.Time;

@Service
public class BookingService {
    static final double TAX_RATE = 0.06;
    static final int CONVENIENCE_FEE = 5;
    static final int MAX_SEATS = 6;
    private static final Pattern MOBILE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final List<String> GENDERS = List.of("Male", "Female", "Other");

    private final Repos.ScheduleRepo schedules;
    private final Repos.BusRepo buses;
    private final Repos.RouteRepo routes;
    private final Repos.BookingRepo bookings;
    private final Repos.SeatRepo seats;
    private final Repos.HoldRepo holds;
    private final Repos.SegmentLockRepo segmentLocks;
    private final JourneyService journeys;
    private final Repos.PaymentRepo payments;
    private final Repos.RefundRepo refunds;
    private final Time time;
    private final SettingService settings;
    private final int holdSeconds;
    private final int fullRefundHours;
    private final int halfRefundHours;
    private final String adminUpiId;

    public BookingService(Repos.ScheduleRepo schedules, Repos.BusRepo buses, Repos.RouteRepo routes, Repos.BookingRepo bookings,
                          Repos.SeatRepo seats, Repos.HoldRepo holds, Repos.SegmentLockRepo segmentLocks, JourneyService journeys,
                          Repos.PaymentRepo payments, Repos.RefundRepo refunds, Time time, SettingService settings,
                          @Value("${app.hold-seconds:120}") int holdSeconds,
                          @Value("${app.refund.full-hours:24}") int fullRefundHours,
                          @Value("${app.refund.half-hours:6}") int halfRefundHours,
                          @Value("${app.admin.upi-id:}") String adminUpiId) {
        this.schedules = schedules; this.buses = buses; this.routes = routes; this.bookings = bookings; this.seats = seats;
        this.holds = holds; this.segmentLocks = segmentLocks; this.journeys = journeys;
        this.payments = payments; this.refunds = refunds; this.time = time; this.settings = settings;
        this.holdSeconds = holdSeconds; this.fullRefundHours = fullRefundHours; this.halfRefundHours = halfRefundHours; this.adminUpiId = adminUpiId == null ? "" : adminUpiId.trim();
    }

    public static String displayId(Long id) { return "SST-" + String.format("%06d", id); }

    // ---------- validation helpers ----------
    private void requireBookingsOpen() {
        if (settings.flag("maintenanceMode")) throw new ApiException(503, "Bookings are temporarily unavailable for maintenance. Please try again later.");
    }

    private Schedule openSchedule(Long id) {
        Schedule s = schedules.findById(id).orElseThrow(() -> new ApiException(404, "Schedule not found."));
        Bus b = buses.findById(s.busId).orElseThrow(() -> new ApiException(404, "Bus not found."));
        if (!"Open".equals(s.status) || !"Active".equals(b.status)) throw new ApiException(400, "This service is not open for booking.");
        if (!time.departure(s).isAfter(time.now())) throw new ApiException(400, "This bus has already departed.");
        return s;
    }

    private List<Integer> parseSeats(List<String> raw, int total) {
        if (raw.isEmpty()) throw new ApiException(400, "Select at least one seat.");
        if (raw.size() > MAX_SEATS) throw new ApiException(400, "Maximum " + MAX_SEATS + " seats per booking.");
        Set<Integer> out = new TreeSet<>();
        for (String r : raw) {
            int n;
            try { n = Integer.parseInt(r.trim()); } catch (NumberFormatException e) { throw new ApiException(400, "Invalid seat number: " + r); }
            if (n < 1 || n > total) throw new ApiException(400, "Seat " + n + " does not exist on this bus.");
            if (!out.add(n)) throw new ApiException(400, "Duplicate seat selected: " + n);
        }
        return new ArrayList<>(out);
    }

    // ---------- segment-aware seat holds ----------
    @Transactional
    public Map<String, Object> hold(Body b, String clientIp) {
        requireBookingsOpen();
        Schedule schedule = openSchedule(b.lng("scheduleId"));
        RouteJourney.Journey journey = journeys.require(schedule, b.str("from"), b.str("to"));
        List<Integer> wanted = parseSeats(b.strList("seats"), schedule.totalSeats);
        LocalDateTime now = time.now();
        String previous = b.str("holdToken");

        // Clean expired rows in both the new segment lock collection and the legacy whole-schedule collection.
        holds.deleteByExpiresAtBefore(now);
        journeys.purgeExpiredSegmentHolds(now);
        List<SeatSegmentLock> previousSegmentRows = previous.isEmpty() ? List.of() : segmentLocks.findByToken(previous);
        List<SeatHold> previousLegacyRows = previous.isEmpty() ? List.of() : holds.findByToken(previous);
        if (previousSegmentRows.stream().anyMatch(row -> !schedule.id.equals(row.scheduleId)
                || !java.util.Objects.equals(clientIp, row.clientIp) || row.bookingId != null
                || row.token == null || !row.token.equals(previous)))
            throw new ApiException(409, "Your seat hold is no longer valid. Please select your seats again.");
        if (previousLegacyRows.stream().anyMatch(row -> !schedule.id.equals(row.scheduleId)
                || !java.util.Objects.equals(clientIp, row.clientIp) || row.bookingId != null))
            throw new ApiException(409, "Your seat hold is no longer valid. Please select your seats again.");

        long currentNewHeldSeats = journeys.activeHeldSeatCount(clientIp, now);
        long oldNewHeldSeats = journeys.heldSeatCountForToken(previous);
        long currentLegacyHeldSeats = holds.countByClientIpAndExpiresAtAfter(clientIp, now);
        long oldLegacyHeldSeats = previousLegacyRows.stream()
                .filter(row -> row.expiresAt != null && row.expiresAt.isAfter(now))
                .map(row -> row.scheduleId + ":" + row.seatNo).distinct().count();
        if (currentNewHeldSeats - oldNewHeldSeats + currentLegacyHeldSeats - oldLegacyHeldSeats + wanted.size() > 2L * MAX_SEATS)
            throw new ApiException(429, "Too many seats on hold. Please complete or release your current selection first.");

        String token = !previousSegmentRows.isEmpty() || !previousLegacyRows.isEmpty() ? previous : J.token(16);
        journeys.assertSeatsAvailable(schedule, journey, wanted, token);

        // Replace this token's complete held path. Unique indexes on (schedule, seat, segment) resolve races.
        segmentLocks.deleteByToken(token);
        holds.deleteByToken(token); // remove any legacy whole-schedule holds owned by this token
        LocalDateTime expires = now.plusSeconds(holdSeconds);
        try {
            for (int seat : wanted) {
                for (int edge : journey.edgeIndices()) {
                    SeatSegmentLock lock = new SeatSegmentLock();
                    lock.scheduleId = schedule.id;
                    lock.seatNo = seat;
                    lock.segmentIndex = edge;
                    lock.token = token;
                    lock.expiresAt = expires;
                    lock.clientIp = clientIp;
                    lock.bookingId = null;
                    segmentLocks.save(lock);
                }
            }
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(409, "One of those seats was just taken for an overlapping part of the journey. Please pick again.");
        }
        return J.map("holdToken", token, "expiresAt", expires.toString(),
                "seats", wanted.stream().map(String::valueOf).collect(Collectors.toList()));
    }

    public Map<String, Object> release(Body b) {
        String token = b.str("holdToken");
        if (!token.isEmpty()) {
            holds.deleteByToken(token);
            segmentLocks.deleteByToken(token);
        }
        return J.map("released", true);
    }

    // ---------- create booking ----------
    private long discount(AppUser user, String code, long base) {
        if (code.isEmpty() || user == null || user.id == null) return 0;
        if (!code.equalsIgnoreCase("SABURI100")) throw new ApiException(400, "Invalid offer code.");
        if (base <= 699) throw new ApiException(400, "SABURI100 is valid on bookings above ₹699.");
        if (bookings.countByUserId(user.id) > 0) throw new ApiException(400, "SABURI100 is valid on your first booking only.");
        return 100;
    }

    @Transactional
    public Map<String, Object> create(AppUser user, Body b) {
        requireBookingsOpen();
        String phone = AuthService.normalizePhone(b.str("customerPhone"));
        if (user == null || user.phone == null || !phone.equals(user.phone)) throw new ApiException(403, "Booking phone number does not match the active booking session.");
        String customerEmail = b.str("customerEmail").trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(customerEmail).matches() || (user.email != null && !customerEmail.equalsIgnoreCase(user.email)))
            throw new ApiException(403, "Booking email does not match the active booking session.");
        Schedule s = openSchedule(b.lng("scheduleId"));
        RouteJourney.Journey journey = journeys.require(s, b.str("from"), b.str("to"));
        List<Integer> want = parseSeats(b.strList("seats"), s.totalSeats);
        String token = b.str("holdToken");
        if (token.isEmpty()) throw new ApiException(409, "Your seat hold expired. Please select your seats again.");
        LocalDateTime nowForHold = time.now();
        holds.deleteByExpiresAtBefore(nowForHold);
        journeys.purgeExpiredSegmentHolds(nowForHold);
        List<SeatSegmentLock> tokenRows = segmentLocks.findByToken(token);
        if (tokenRows.isEmpty()) throw new ApiException(409, "Your seat hold expired. Please select your seats again.");
        boolean invalidTokenRow = tokenRows.stream().anyMatch(row -> !s.id.equals(row.scheduleId)
                || row.bookingId != null || !token.equals(row.token) || row.expiresAt == null
                || !row.expiresAt.isAfter(nowForHold) || row.seatNo < 1 || row.seatNo > s.totalSeats);
        Set<String> actualLockKeys = tokenRows.stream()
                .map(row -> JourneyService.lockPairKey(row.scheduleId, row.seatNo, row.segmentIndex))
                .collect(Collectors.toSet());
        Set<String> expectedLockKeys = JourneyService.expectedLockKeys(s.id, want, journey);
        if (invalidTokenRow || !actualLockKeys.equals(expectedLockKeys))
            throw new ApiException(409, "Your seat hold expired or changed. Please select your seats again.");
        journeys.assertSeatsAvailable(s, journey, want, token);

        List<Body> pax = b.objList("passengers");
        if (pax.size() != want.size()) throw new ApiException(400, "Enter passenger details for every selected seat.");
        Map<Integer, Body> paxBySeat = new HashMap<>();
        for (Body p : pax) {
            int seat;
            try { seat = Integer.parseInt(p.str("seat")); } catch (NumberFormatException e) { throw new ApiException(400, "Invalid passenger seat."); }
            if (!want.contains(seat) || paxBySeat.put(seat, p) != null) throw new ApiException(400, "Passenger details do not match the selected seats.");
            String name = p.str("name");
            if (name.length() < 2 || name.length() > 80) throw new ApiException(400, "Enter every passenger name.");
            int age;
            try { age = Integer.parseInt(p.str("age")); } catch (NumberFormatException e) { throw new ApiException(400, "Enter a valid age for every passenger."); }
            if (age < 1 || age > 120) throw new ApiException(400, "Enter a valid age for every passenger.");
            if (!MOBILE.matcher(AuthService.normalizePhone(p.str("mobile"))).matches()) throw new ApiException(400, "Enter a valid 10-digit mobile number for every passenger.");
            String email = p.str("email");
            if (!email.isEmpty() && (email.length() > 120 || !EMAIL.matcher(email).matches())) throw new ApiException(400, "Enter a valid passenger email.");
        }

        String method = b.str("paymentMethod").isEmpty() ? "UPI" : b.str("paymentMethod").trim().toUpperCase(Locale.ROOT);
        if (!"UPI".equals(method))
            throw new ApiException(400, "Only UPI payments are currently supported.");

        long base = Math.round(journey.fare * want.size());
        long tax = Math.round(base * TAX_RATE);
        String code = b.str("offerCode");
        long total = Math.max(0, base + tax + CONVENIENCE_FEE - discount(user, code, base));

        LocalDateTime now = time.now();
        Booking bk = new Booking();
        String guestAccessToken = user.id == null ? GuestBookingAccess.generate() : null;
        bk.userId = user.id; bk.guestAccessTokenHash = guestAccessToken == null ? null : GuestBookingAccess.hash(guestAccessToken); bk.customerPhone = phone; bk.customerEmail = customerEmail; bk.customerName = pax.isEmpty() ? user.name : pax.get(0).str("name"); bk.scheduleId = s.id;
        bk.journeyFrom = journey.from; bk.journeyTo = journey.to;
        bk.journeyFromIndex = journey.fromIndex; bk.journeyToIndex = journey.toIndex;
        bk.amount = total; bk.status = "Pending"; bk.paymentMethod = method; bk.offerCode = code.isEmpty() ? null : code.toUpperCase(Locale.ROOT);
        bk.createdAt = now;
        bk = bookings.save(bk);

        for (int n : want) {
            Body p = paxBySeat.get(n);
            BookingSeat bs = new BookingSeat();
            bs.bookingId = bk.id; bs.scheduleId = s.id; bs.seatNo = n; bs.lockKey = "BOOKING:" + bk.id + ":" + n;
            bs.passengerName = p.str("name"); bs.age = Integer.parseInt(p.str("age"));
            bs.gender = GENDERS.stream().filter(g -> g.equalsIgnoreCase(p.str("gender"))).findFirst().orElse("Other");
            bs.mobile = AuthService.normalizePhone(p.str("mobile")); bs.email = p.str("email");
            seats.save(bs);
        }

        // Convert each temporary segment lock into a permanent lock belonging to this booking.
        for (SeatSegmentLock lock : tokenRows) {
            lock.token = null; lock.expiresAt = null; lock.clientIp = null; lock.bookingId = bk.id;
            segmentLocks.save(lock);
        }
        holds.deleteByToken(token);

        // Only payment metadata is stored; never store card numbers, CVV, or bank credentials.
        Payment pay = new Payment();
        pay.bookingId = bk.id; pay.amount = total; pay.method = method; pay.status = "PENDING"; pay.createdAt = now;
        payments.save(pay);

        // MongoRepository writes immediately; MongoDB unique indexes enforce seat uniqueness.
        Map<String, Object> result = dtos(List.of(bk)).get(0);
        if (guestAccessToken != null) {
            // Return this high-entropy secret once. Only its hash is persisted.
            result.put("guestAccessToken", guestAccessToken);
            result.put("guestAccessTokenNotice", "Save this secret. It is required to retrieve, reschedule, or cancel this guest booking.");
        }
        return result;
    }

    // ---------- admin payment/booking confirmation ----------
    @Transactional
    public Map<String, Object> confirmBooking(Long id) {
        Booking b = bookings.findById(id).orElseThrow(() -> new ApiException(404, "Booking not found."));
        if (!"Pending".equalsIgnoreCase(b.status)) throw new ApiException(400, "Only pending bookings can be confirmed.");
        Payment pay = payments.findByBookingIdIn(List.of(b.id)).stream().findFirst().orElseThrow(() -> new ApiException(400, "Payment record not found."));
        if (!"UPI".equalsIgnoreCase(pay.method))
            throw new ApiException(400, "Only UPI payments are currently supported.");
        pay.status = "SUCCESS"; payments.save(pay);
        b.status = "Confirmed"; bookings.save(b);
        return dtos(List.of(b)).get(0);
    }

    @Transactional
    public Map<String, Object> rejectBooking(Long id) {
        Booking b = bookings.findById(id).orElseThrow(() -> new ApiException(404, "Booking not found."));
        if (!"Pending".equalsIgnoreCase(b.status)) throw new ApiException(400, "Only pending bookings can be rejected.");
        Payment pay = payments.findByBookingIdIn(List.of(b.id)).stream().findFirst().orElse(null);
        if (pay != null) { pay.status = "REJECTED"; payments.save(pay); }
        b.status = "Cancelled"; b.cancelledAt = time.now(); bookings.save(b);
        for (BookingSeat bs : seats.findByBookingIdOrderBySeatNo(b.id)) { bs.lockKey = null; seats.save(bs); }
        holds.deleteByBookingId(b.id);
        segmentLocks.deleteByBookingId(b.id);
        return dtos(List.of(b)).get(0);
    }

    /** Build a UPI deep link from the persisted booking, never from a client-supplied amount. */
    public Map<String, Object> paymentInfo(long bookingId, AppUser owner, String guestAccessToken) {
        if (adminUpiId.isEmpty()) throw new ApiException(503, "Admin UPI ID is not configured. Set ADMIN_UPI_ID before accepting payments.");
        Booking booking = owned(bookingId, owner, guestAccessToken);
        if (!"Pending".equalsIgnoreCase(booking.status))
            throw new ApiException(400, "UPI payment is available only for pending bookings.");
        Payment payment = payments.findByBookingIdIn(List.of(booking.id)).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "Payment record not found."));
        if (!"PENDING".equalsIgnoreCase(payment.status))
            throw new ApiException(400, "This payment is no longer pending.");

        long amount = booking.amount;
        String displayBookingId = displayId(booking.id);
        String tn = "Saburi Swami Travel " + displayBookingId;
        String uri = "upi://pay?pa=" + java.net.URLEncoder.encode(adminUpiId, StandardCharsets.UTF_8)
                + "&pn=" + java.net.URLEncoder.encode("Saburi Swami Travel", StandardCharsets.UTF_8)
                + "&am=" + String.format(Locale.ROOT, "%.2f", (double) amount)
                + "&cu=INR&tn=" + java.net.URLEncoder.encode(tn, StandardCharsets.UTF_8);
        return J.map("upiId", adminUpiId, "amount", amount, "bookingId", displayBookingId, "upiUri", uri);
    }

    // ---------- cancel / reschedule ----------
    private boolean validGuestAccessToken(Booking booking, String token) {
        return GuestBookingAccess.matches(booking.guestAccessTokenHash, token);
    }

    private Booking owned(Long id, AppUser owner, String guestAccessToken) {
        Booking b = bookings.findById(id).orElseThrow(() -> new ApiException(404, "Booking not found."));
        if (owner != null) {
            if (owner.id != null) {
                if (!owner.id.equals(b.userId)) throw new ApiException(404, "Booking not found.");
            } else if (!validGuestAccessToken(b, guestAccessToken)) {
                // A phone number/email or a guest session is not proof of booking ownership.
                throw new ApiException(404, "Booking not found or guest access secret is invalid.");
            }
        }
        return b;
    }

    public void authorizePaymentAccess(long bookingId, AppUser owner, String accessToken) { owned(bookingId, owner, accessToken); }

    public Map<String, Object> guestBooking(String reference, String accessToken) {
        String raw = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
        if (raw.startsWith("SST-")) raw = raw.substring(4);
        final long id;
        try { id = Long.parseLong(raw); } catch (NumberFormatException e) { throw new ApiException(404, "Booking not found."); }
        Booking b = owned(id, new AppUser(), accessToken);
        return dtos(List.of(b)).get(0);
    }

    /** owner == null means an administrator is cancelling (always a full refund, no departure check). */
    @Transactional
    public Map<String, Object> cancel(Long id, AppUser owner, String guestAccessToken) {
        Booking b = owned(id, owner, guestAccessToken);
        if (!"Confirmed".equals(b.status)) throw new ApiException(400, "Only confirmed bookings can be cancelled.");
        Schedule s = schedules.findById(b.scheduleId).orElseThrow(() -> new ApiException(404, "Schedule not found."));
        LocalDateTime now = time.now();
        long refund;
        String reason;
        if (owner == null) {
            refund = b.amount; reason = "Cancelled by admin";
        } else {
            LocalDateTime dep = time.departure(s);
            if (!dep.isAfter(now)) throw new ApiException(400, "This journey has already departed and cannot be cancelled.");
            long hours = Duration.between(now, dep).toHours();
            long refundable = Math.max(0, b.amount - CONVENIENCE_FEE);
            if (hours >= fullRefundHours) { refund = refundable; reason = "Cancelled by customer (full refund)"; }
            else if (hours >= halfRefundHours) { refund = refundable / 2; reason = "Cancelled by customer (50% refund)"; }
            else { refund = 0; reason = "Cancelled by customer (too close to departure)"; }
        }
        b.status = "Cancelled"; b.cancelledAt = now;
        bookings.save(b);
        for (BookingSeat bs : seats.findByBookingIdOrderBySeatNo(b.id)) { bs.lockKey = null; seats.save(bs); }
        holds.deleteByBookingId(b.id);
        segmentLocks.deleteByBookingId(b.id);
        Refund r = new Refund();
        r.bookingId = b.id; r.amount = refund; r.status = refund > 0 ? "REFUNDED" : "NOT_ELIGIBLE"; r.reason = reason; r.createdAt = now;
        refunds.save(r);
        return dtos(List.of(b)).get(0);
    }

    @Transactional
    public Map<String, Object> reschedule(Long id, AppUser owner, Body body, String guestAccessToken) {
        requireBookingsOpen();
        Booking b = owned(id, owner, guestAccessToken);
        if (!"Confirmed".equals(b.status)) throw new ApiException(400, "Only confirmed bookings can be rescheduled.");
        Schedule old = schedules.findById(b.scheduleId).orElseThrow(() -> new ApiException(404, "Schedule not found."));
        if (!time.departure(old).isAfter(time.now())) throw new ApiException(400, "This journey has already departed.");
        Schedule nw = openSchedule(body.lng("newScheduleId"));
        if (nw.id.equals(old.id)) throw new ApiException(400, "Please choose a different schedule.");
        Route or = routes.findById(old.routeId).orElseThrow();
        Route nr = routes.findById(nw.routeId).orElseThrow();
        if (!CatalogService.norm(or.origin).equals(CatalogService.norm(nr.origin)) || !CatalogService.norm(or.destination).equals(CatalogService.norm(nr.destination)))
            throw new ApiException(400, "You can only reschedule to the same route.");
        String journeyFrom = b.journeyFrom == null || b.journeyFrom.isBlank() ? or.origin : b.journeyFrom;
        String journeyTo = b.journeyTo == null || b.journeyTo.isBlank() ? or.destination : b.journeyTo;
        RouteJourney.Journey oldJourney = journeys.require(old, journeyFrom, journeyTo);
        RouteJourney.Journey newJourney = journeys.require(nw, journeyFrom, journeyTo);
        if (newJourney.fare > oldJourney.fare) throw new ApiException(400, "The new journey fare is higher. Please cancel and book again.");

        List<BookingSeat> rows = seats.findByBookingIdOrderBySeatNo(b.id);
        List<Integer> want = parseSeats(body.strList("seats"), nw.totalSeats);
        if (want.size() != rows.size()) throw new ApiException(400, "Select exactly " + rows.size() + " seat(s).");
        LocalDateTime now = time.now();
        holds.deleteByExpiresAtBefore(now);
        journeys.purgeExpiredSegmentHolds(now);
        journeys.assertSeatsAvailable(nw, newJourney, want, null);
        try {
            // Reserve destination segments before releasing the old journey; the transaction rolls back on any conflict.
            for (int seat : want) for (int edge : newJourney.edgeIndices()) {
                SeatSegmentLock lock = new SeatSegmentLock();
                lock.scheduleId = nw.id; lock.seatNo = seat; lock.segmentIndex = edge;
                lock.bookingId = b.id;
                segmentLocks.save(lock);
            }
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(409, "A selected seat is no longer available on the new journey segment.");
        }
        holds.deleteByBookingId(b.id);
        segmentLocks.deleteByBookingIdAndScheduleId(b.id, old.id);
        // Free the old per-booking seat keys first so changing the seat numbers cannot collide
        // with another seat row belonging to this same booking.
        for (BookingSeat bs : rows) { bs.lockKey = null; seats.save(bs); }
        for (int i = 0; i < rows.size(); i++) {
            BookingSeat bs = rows.get(i);
            bs.scheduleId = nw.id; bs.seatNo = want.get(i); bs.lockKey = "BOOKING:" + b.id + ":" + want.get(i);
            seats.save(bs);
        }
        b.scheduleId = nw.id;
        b.journeyFrom = newJourney.from; b.journeyTo = newJourney.to;
        b.journeyFromIndex = newJourney.fromIndex; b.journeyToIndex = newJourney.toIndex;
        bookings.save(b);
        // MongoRepository writes immediately; MongoDB unique indexes enforce seat uniqueness.
        return dtos(List.of(b)).get(0);
    }

    // ---------- queries ----------
    public List<Map<String, Object>> myBookings(AppUser user, String phone) {
        String p = AuthService.normalizePhone(phone);
        if (!p.isEmpty() && !p.equals(user.phone)) throw new ApiException(403, "You can only view your own bookings.");
        if (user.id == null) throw new ApiException(403, "Guest bookings must be retrieved using the booking reference and guest access secret.");
        return dtos(bookings.findByUserIdOrderByIdDesc(user.id));
    }

    public List<Map<String, Object>> allBookings() { return dtos(bookings.findAllByOrderByIdDesc()); }

    public Booking find(Long id) { return bookings.findById(id).orElseThrow(() -> new ApiException(404, "Booking not found.")); }

    public List<Map<String, Object>> dtos(List<Booking> list) {
        if (list.isEmpty()) return new ArrayList<>();
        List<Long> ids = list.stream().map(b -> b.id).toList();
        Map<Long, Schedule> sm = new HashMap<>();
        for (Schedule s : schedules.findAllById(list.stream().map(b -> b.scheduleId).collect(Collectors.toSet()))) sm.put(s.id, s);
        Map<Long, Bus> bm = new HashMap<>();
        for (Bus b : buses.findAllById(sm.values().stream().map(s -> s.busId).collect(Collectors.toSet()))) bm.put(b.id, b);
        Map<Long, Route> rm = new HashMap<>();
        for (Route r : routes.findAllById(sm.values().stream().map(s -> s.routeId).collect(Collectors.toSet()))) rm.put(r.id, r);
        Map<Long, List<BookingSeat>> seatMap = new HashMap<>();
        for (BookingSeat bs : seats.findByBookingIdIn(ids)) seatMap.computeIfAbsent(bs.bookingId, k -> new ArrayList<>()).add(bs);
        Map<Long, Payment> payMap = new HashMap<>();
        for (Payment p : payments.findByBookingIdIn(ids)) payMap.putIfAbsent(p.bookingId, p);
        Map<Long, Long> refundMap = new HashMap<>();
        for (Refund r : refunds.findByBookingIdIn(ids)) if ("REFUNDED".equals(r.status)) refundMap.merge(r.bookingId, r.amount, Long::sum);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Booking b : list) {
            Schedule s = sm.get(b.scheduleId);
            Bus bus = s == null ? null : bm.get(s.busId);
            Route r = s == null ? null : rm.get(s.routeId);
            List<BookingSeat> rows = seatMap.getOrDefault(b.id, new ArrayList<>());
            rows.sort(Comparator.comparingInt(x -> x.seatNo));
            List<Object> pax = new ArrayList<>();
            for (BookingSeat bs : rows)
                pax.add(J.map("seat", String.valueOf(bs.seatNo), "name", bs.passengerName, "age", bs.age, "gender", bs.gender, "mobile", bs.mobile, "email", bs.email == null ? "" : bs.email));
            Payment pay = payMap.get(b.id);
            out.add(J.map("id", displayId(b.id), "dbId", b.id, "customerPhone", b.customerPhone, "customerName", b.customerName,
                    "scheduleId", b.scheduleId, "bus", bus == null ? "" : bus.busNumber, "operator", bus == null ? "" : bus.operatorName,
                    "type", bus == null ? "" : bus.type, "from", b.journeyFrom != null ? b.journeyFrom : (r == null ? "" : r.origin), "to", b.journeyTo != null ? b.journeyTo : (r == null ? "" : r.destination), "customerEmail", b.customerEmail,
                    "date", s == null ? "" : s.travelDate.toString(), "depart", s == null ? "" : s.departureTime, "arrive", s == null ? "" : s.arrivalTime,
                    "seats", rows.stream().map(x -> String.valueOf(x.seatNo)).collect(Collectors.toList()), "passengers", pax, "passengerName", b.customerName,
                    "amount", b.amount, "status", b.status, "paymentMethod", b.paymentMethod == null ? "" : b.paymentMethod,
                    "payment", J.map("status", pay == null ? "" : pay.status, "method", pay == null ? "" : pay.method),
                    "refundAmount", refundMap.getOrDefault(b.id, 0L), "createdAt", b.createdAt == null ? "" : b.createdAt.toString()));
        }
        return out;
    }

    // ---------- ticket QR ----------
    private String sign(Long id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.ticketSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("ticket:" + id).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) { throw new ApiException(500, "Unable to sign ticket."); }
    }

    public byte[] qrPng(Booking b, String baseUrl) {
        String url = baseUrl + "/api/tickets/verify?id=" + b.id + "&sig=" + sign(b.id);
        try {
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320, Map.of(EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) { throw new ApiException(500, "Unable to generate QR code."); }
    }

    public Map<String, Object> verifyTicket(Long id, String sig) {
        boolean ok = MessageDigest.isEqual(sign(id).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
        Booking b = ok ? bookings.findById(id).orElse(null) : null;
        if (b == null) return J.map("valid", false, "message", "Ticket could not be verified.");
        Map<String, Object> d = dtos(List.of(b)).get(0);
        String first = "";
        if (d.get("passengers") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> p0) first = String.valueOf(p0.get("name")).split(" ")[0];
        return J.map("valid", "Confirmed".equals(b.status), "id", d.get("id"), "status", d.get("status"), "passenger", first,
                "bus", d.get("bus"), "from", d.get("from"), "to", d.get("to"), "date", d.get("date"), "depart", d.get("depart"), "seats", d.get("seats"));
    }
}
