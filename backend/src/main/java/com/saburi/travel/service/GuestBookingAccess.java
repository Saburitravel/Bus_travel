package com.saburi.travel.service;

import com.saburi.travel.util.J;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Generates and verifies one-time-displayed guest booking access secrets. */
public final class GuestBookingAccess {
    private GuestBookingAccess() {}
    public static String generate() { return J.token(32); }
    public static String hash(String token) {
        if (token == null || token.isBlank()) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.trim().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Unable to hash guest access token.", e); }
    }
    public static boolean matches(String storedHash, String candidate) {
        if (storedHash == null || storedHash.isBlank() || candidate == null || candidate.isBlank()) return false;
        return MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII), hash(candidate).getBytes(StandardCharsets.US_ASCII));
    }
}
