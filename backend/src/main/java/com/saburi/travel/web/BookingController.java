package com.saburi.travel.web;

import com.saburi.travel.model.AuthSession;
import com.saburi.travel.model.Booking;
import com.saburi.travel.model.AppUser;
import com.saburi.travel.service.AuthService;
import com.saburi.travel.service.BookingService;
import com.saburi.travel.util.ApiException;
import com.saburi.travel.util.Body;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BookingController {
    private final BookingService bookings;
    private final AuthService auth;

    public BookingController(BookingService bookings, AuthService auth) { this.bookings = bookings; this.auth = auth; }

    // seat holds are open to guests: the random holdToken is the only credential
    @PostMapping("/seats/hold")
    public Map<String, Object> hold(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return bookings.hold(new Body(body), req.getRemoteAddr());
    }

    @PostMapping("/seats/release")
    public Map<String, Object> release(@RequestBody Map<String, Object> body) { return bookings.release(new Body(body)); }

    @PostMapping("/bookings")
    public Map<String, Object> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return bookings.create(auth.requireBookingUser(req), new Body(body));
    }

    @GetMapping("/bookings/my")
    public List<Map<String, Object>> mine(HttpServletRequest req, @RequestParam(required = false) String phone) {
        return bookings.myBookings(auth.requireBookingUser(req), phone);
    }

    @GetMapping("/bookings/guest")
    public Map<String, Object> guestBooking(@RequestParam String bookingId, @RequestHeader("X-Guest-Booking-Token") String accessToken) {
        return bookings.guestBooking(bookingId, accessToken);
    }

    @GetMapping("/bookings/all")
    public List<Map<String, Object>> all(HttpServletRequest req) { auth.requireAdmin(req); return bookings.allBookings(); }

    @PostMapping("/bookings/{id}/cancel")
    public Map<String, Object> cancel(HttpServletRequest req, @PathVariable Long id, @RequestHeader(value = "X-Guest-Booking-Token", required = false) String accessToken) {
        return bookings.cancel(id, ownerOrGuestSecret(req, accessToken), accessToken);
    }

    @PostMapping("/bookings/{id}/reschedule")
    public Map<String, Object> reschedule(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        String accessToken = req.getHeader("X-Guest-Booking-Token") != null ? req.getHeader("X-Guest-Booking-Token") : new Body(body).str("guestAccessToken");
        return bookings.reschedule(id, ownerOrGuestSecret(req, accessToken), new Body(body), accessToken);
    }

    @GetMapping("/payments/upi")
    public Map<String, Object> upi(HttpServletRequest req, @RequestParam long bookingId, @RequestHeader(value = "X-Guest-Booking-Token", required = false) String accessToken) {
        return bookings.paymentInfo(bookingId, ownerOrGuestSecret(req, accessToken), accessToken);
    }

    @PostMapping("/admin/bookings/{id}/confirm")
    public Map<String, Object> confirmBooking(HttpServletRequest req, @PathVariable Long id) { auth.requireAdmin(req); return bookings.confirmBooking(id); }

    @PostMapping("/admin/bookings/{id}/reject")
    public Map<String, Object> rejectBooking(HttpServletRequest req, @PathVariable Long id) { auth.requireAdmin(req); return bookings.rejectBooking(id); }

    @PostMapping("/admin/bookings/{id}/cancel")
    public Map<String, Object> adminCancel(HttpServletRequest req, @PathVariable Long id) { auth.requireAdmin(req); return bookings.cancel(id, null, null); }

    private AppUser ownerOrGuestSecret(HttpServletRequest req, String accessToken) {
        AuthSession session = auth.session(req);
        if (session == null || (accessToken != null && !accessToken.isBlank() && session.userId != null))
            return new AppUser(); // guest access secret will be validated by BookingService
        return auth.requireBookingUser(req);
    }

    @GetMapping("/bookings/{id}/qr")
    public ResponseEntity<byte[]> qr(HttpServletRequest req, @PathVariable Long id, @RequestHeader(value = "X-Guest-Booking-Token", required = false) String accessToken) {
        AuthSession s = auth.session(req);
        Booking b = bookings.find(id);
        if (!"Confirmed".equalsIgnoreCase(b.status)) throw new ApiException(409, "Ticket QR is available only after payment verification and admin confirmation.");
        if (s != null && "admin".equals(s.role)) { /* admins may view ticket QR */ }
        else if (s != null && "customer".equals(s.role) && b.userId != null && b.userId.equals(s.userId)) { /* account owner */ }
        else if (accessToken != null && !accessToken.isBlank()) { bookings.guestBooking(String.valueOf(id), accessToken); }
        else throw new ApiException(404, "Booking not found.");
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(bookings.qrPng(b, base));
    }

    /** Public: opened when someone scans a ticket QR code. Returns only minimal, non-sensitive details. */
    @GetMapping("/tickets/verify")
    public Map<String, Object> verify(@RequestParam Long id, @RequestParam String sig) { return bookings.verifyTicket(id, sig); }
}
