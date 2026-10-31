package com.saburi.travel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.saburi.travel.model.Setting;
import com.saburi.travel.repo.Repos;
import com.saburi.travel.util.J;

@Service
public class SettingService {
    public static final List<String> KEYS = List.of("smsNotifications", "emailConfirmations", "autoCancelUnpaidHolds", "maintenanceMode");
    private static final Map<String, Boolean> DEFAULTS = Map.of(
            "smsNotifications", true, "emailConfirmations", true, "autoCancelUnpaidHolds", true, "maintenanceMode", false);

    private final Repos.SettingRepo repo;

    public SettingService(Repos.SettingRepo repo) { this.repo = repo; }

    public String get(String k, String def) { return repo.findById(k).map(s -> s.value).orElse(def); }

    public void put(String k, String v) {
        Setting s = repo.findById(k).orElseGet(Setting::new);
        s.key = k;
        s.value = v;
        repo.save(s);
    }

    public boolean flag(String k) { return Boolean.parseBoolean(get(k, String.valueOf(DEFAULTS.getOrDefault(k, false)))); }

    public Map<String, Object> allFlags() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : KEYS) m.put(k, flag(k));
        return m;
    }

    /** Secret used to sign ticket QR codes; generated once and persisted. */
    public synchronized String ticketSecret() {
        String s = get("ticketSecret", null);
        if (s == null) { s = J.token(32); put("ticketSecret", s); }
        return s;
    }
}
