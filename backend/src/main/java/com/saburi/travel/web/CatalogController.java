package com.saburi.travel.web;

import com.saburi.travel.service.AuthService;
import com.saburi.travel.service.CatalogService;
import com.saburi.travel.util.Body;
import com.saburi.travel.util.J;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final CatalogService catalog;
    private final AuthService auth;

    public CatalogController(CatalogService catalog, AuthService auth) { this.catalog = catalog; this.auth = auth; }

    // ----- public -----
    @GetMapping("/catalog")
    public Map<String, Object> publicCatalog() { return catalog.catalog(false); }

    @GetMapping("/schedules/search")
    public List<Map<String, Object>> search(@RequestParam String from, @RequestParam String to, @RequestParam String date) {
        return catalog.search(from, to, date);
    }

    @GetMapping("/schedules/route")
    public List<Map<String, Object>> upcomingRoute(@RequestParam String from, @RequestParam String to) {
        return catalog.upcomingRoute(from, to);
    }

    @GetMapping("/schedules/{id}/seats")
    public List<Map<String, Object>> seats(@PathVariable Long id,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) String holdToken) {
        return catalog.seatMap(id, from, to, holdToken);
    }

    // ----- admin -----
    @GetMapping("/admin/catalog")
    public Map<String, Object> adminCatalog(HttpServletRequest req) { auth.requireAdmin(req); return catalog.catalog(true); }

    @GetMapping("/buses")
    public List<Map<String, Object>> buses(HttpServletRequest req) { auth.requireAdmin(req); return catalog.listBuses(); }

    @PostMapping("/buses")
    public Map<String, Object> addBus(HttpServletRequest req, @RequestBody Map<String, Object> b) { auth.requireAdmin(req); return catalog.createBus(new Body(b)); }

    @PutMapping("/buses/{id}")
    public Map<String, Object> updateBus(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> b) {
        auth.requireAdmin(req);
        return catalog.updateBus(id, new Body(b));
    }

    @DeleteMapping("/buses/{id}")
    public Map<String, Object> deleteBus(HttpServletRequest req, @PathVariable Long id) { auth.requireAdmin(req); catalog.deleteBus(id); return J.map("deleted", true); }

    @GetMapping("/routes")
    public List<Map<String, Object>> routes(HttpServletRequest req) { auth.requireAdmin(req); return catalog.listRoutes(); }

    @PostMapping("/routes")
    public Map<String, Object> addRoute(HttpServletRequest req, @RequestBody Map<String, Object> b) { auth.requireAdmin(req); return catalog.createRoute(new Body(b)); }

    @PutMapping("/routes/{id}")
    public Map<String, Object> updateRoute(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> b) {
        auth.requireAdmin(req);
        return catalog.updateRoute(id, new Body(b));
    }

    @DeleteMapping("/routes/{id}")
    public Map<String, Object> deleteRoute(HttpServletRequest req, @PathVariable Long id) { auth.requireAdmin(req); catalog.deleteRoute(id); return J.map("deleted", true); }

    @GetMapping("/routes/{id}/stops")
    public List<Map<String, Object>> stops(@PathVariable Long id) { return catalog.listStops(id); }

    @PostMapping("/admin/routes/{id}/stops")
    public Map<String, Object> addStop(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> b) {
        auth.requireAdmin(req);
        return catalog.addStop(id, new Body(b));
    }

    @PutMapping("/admin/stops/{id}")
    public Map<String, Object> updateStop(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> b) {
        auth.requireAdmin(req);
        return catalog.updateStop(id, new Body(b));
    }

    @DeleteMapping("/admin/stops/{id}")
    public Map<String, Object> deleteStop(HttpServletRequest req, @PathVariable Long id) { auth.requireAdmin(req); catalog.deleteStop(id); return J.map("deleted", true); }

    @GetMapping("/admin/routes/{id}/fares")
    public List<Map<String, Object>> routeFares(HttpServletRequest req, @PathVariable Long id) {
        auth.requireAdmin(req); return catalog.listRouteFares(id);
    }

    @PostMapping("/admin/routes/{id}/fares")
    public Map<String, Object> addRouteFare(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> b) {
        auth.requireAdmin(req); return catalog.addRouteFare(id, new Body(b));
    }

    @PutMapping("/admin/fares/{id}")
    public Map<String, Object> updateRouteFare(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> b) {
        auth.requireAdmin(req); return catalog.updateRouteFare(id, new Body(b));
    }

    @DeleteMapping("/admin/fares/{id}")
    public Map<String, Object> deleteRouteFare(HttpServletRequest req, @PathVariable Long id) {
        auth.requireAdmin(req); catalog.deleteRouteFare(id); return J.map("deleted", true);
    }

    @GetMapping("/schedules")
    public List<Map<String, Object>> schedules(HttpServletRequest req) { auth.requireAdmin(req); return catalog.listSchedules(); }

    @PostMapping("/schedules")
    public Map<String, Object> addSchedule(HttpServletRequest req, @RequestBody Map<String, Object> b) { auth.requireAdmin(req); return catalog.createSchedule(new Body(b)); }

    @PutMapping("/schedules/{id}")
    public Map<String, Object> updateSchedule(HttpServletRequest req, @PathVariable Long id, @RequestBody Map<String, Object> b) {
        auth.requireAdmin(req);
        return catalog.updateSchedule(id, new Body(b));
    }

    @DeleteMapping("/schedules/{id}")
    public Map<String, Object> deleteSchedule(HttpServletRequest req, @PathVariable Long id) { auth.requireAdmin(req); catalog.deleteSchedule(id); return J.map("deleted", true); }
}
