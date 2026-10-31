package com.saburi.travel.web;

import com.saburi.travel.model.*;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.service.*;
import com.saburi.travel.util.Body;
import com.saburi.travel.util.J;
import com.saburi.travel.util.Time;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final AuthService auth;
    private final AdminService admin;
    private final TrackingService tracking;
    private final Repos.TicketRepo tickets;
    private final Time time;

    public AdminController(AuthService auth, AdminService admin, TrackingService tracking, Repos.TicketRepo tickets, Time time) {
        this.auth = auth; this.admin = admin; this.tracking = tracking; this.tickets = tickets; this.time = time;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(HttpServletRequest req) { auth.requireAdmin(req); return admin.users(); }

    @PutMapping("/users/{id}/status")
    public Map<String, Object> userStatus(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        auth.requireAdmin(req);
        return admin.setUserStatus(id, new Body(body).str("status"));
    }

    @GetMapping("/admin/payments")
    public List<Map<String, Object>> payments(HttpServletRequest req) { auth.requireAdmin(req); return admin.payments(); }

    @GetMapping("/admin/refunds")
    public List<Map<String, Object>> refunds(HttpServletRequest req) { auth.requireAdmin(req); return admin.refunds(); }

    @GetMapping("/admin/reports/{type}")
    public Object report(HttpServletRequest req, @PathVariable String type) { auth.requireAdmin(req); return admin.report(type); }

    @GetMapping("/admin/settings")
    public Map<String, Object> settings(HttpServletRequest req) { auth.requireAdmin(req); return admin.settings(); }

    @PutMapping("/admin/settings")
    public Map<String, Object> saveSettings(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        auth.requireAdmin(req);
        return admin.saveSettings(new Body(body));
    }

    // live tracking: readable by any logged-in user, advanced by admins
    @GetMapping("/tracking/{scheduleId}")
    public Map<String, Object> track(HttpServletRequest req, @PathVariable Long scheduleId) {
        auth.requireAny(req);
        return tracking.get(scheduleId);
    }

    @PostMapping("/tracking/{scheduleId}/advance")
    public Map<String, Object> advance(HttpServletRequest req, @PathVariable Long scheduleId) {
        auth.requireAdmin(req);
        return tracking.advance(scheduleId);
    }

    @PostMapping("/support")
    public Map<String, Object> support(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Body b = new Body(body);
        String message = b.str("message");
        if (message.length() < 5) throw new com.saburi.travel.util.ApiException(400, "Please describe your issue (at least 5 characters).");
        if (message.length() > 2000) throw new com.saburi.travel.util.ApiException(400, "Message is too long.");
        SupportTicket t = new SupportTicket();
        AuthSession s = auth.session(req);
        if (s != null && "customer".equals(s.role)) { t.userId = s.userId; t.phone = s.identity; }
        t.subject = b.str("subject").length() > 120 ? b.str("subject").substring(0, 120) : b.str("subject");
        t.message = message;
        t.createdAt = time.now();
        t = tickets.save(t);
        return J.map("id", t.id, "status", t.status);
    }
}
