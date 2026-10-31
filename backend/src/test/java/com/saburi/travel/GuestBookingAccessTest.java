package com.saburi.travel;

import com.saburi.travel.service.GuestBookingAccess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuestBookingAccessTest {
    @Test void generatedSecretsAreHighEntropyAndDifferent() {
        String first = GuestBookingAccess.generate();
        String second = GuestBookingAccess.generate();
        assertEquals(64, first.length());
        assertNotEquals(first, second);
    }
    @Test void onlyMatchingSecretCanBeVerified() {
        String token = GuestBookingAccess.generate();
        String hash = GuestBookingAccess.hash(token);
        assertNotNull(hash);
        assertNotEquals(token, hash);
        assertTrue(GuestBookingAccess.matches(hash, token));
        assertFalse(GuestBookingAccess.matches(hash, token + "x"));
        assertFalse(GuestBookingAccess.matches(hash, ""));
        assertFalse(GuestBookingAccess.matches(null, token));
    }
}
