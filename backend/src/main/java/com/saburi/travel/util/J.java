package com.saburi.travel.util;

import com.saburi.travel.model.AppUser;
import java.security.SecureRandom;
import java.util.*;

/** Small helpers for building JSON responses with the exact key names the frontend expects. */
public final class J {
    private J() {}
    private static final SecureRandom RND = new SecureRandom();

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String token(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static Map<String, Object> user(AppUser u) {
        return map("id", u.id, "name", u.name, "phone", u.phone,
                "email", u.email == null ? "" : u.email, "city", u.city == null ? "" : u.city,
                "role", u.role == null ? "CUSTOMER" : u.role, "status", u.status, "joined", u.joined == null ? "" : u.joined.toString());
    }
}
