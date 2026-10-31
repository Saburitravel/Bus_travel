package com.saburi.travel;

import com.saburi.travel.model.AppUser;
import com.saburi.travel.model.BookingSeat;
import com.saburi.travel.model.SeatHold;
import com.saburi.travel.model.SeatSegmentLock;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.annotation.Id;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class MongoSchemaTest {
    @Test void collectionsAreMappedToMongoDocuments() {
        assertEquals("app_users", AppUser.class.getAnnotation(Document.class).collection());
        assertEquals("booking_seats", BookingSeat.class.getAnnotation(Document.class).collection());
        assertEquals("seat_holds", SeatHold.class.getAnnotation(Document.class).collection());
        assertEquals("seat_segment_locks", SeatSegmentLock.class.getAnnotation(Document.class).collection());
    }

    @Test void seatLockHasUniqueIndex() throws Exception {
        Field field = BookingSeat.class.getField("lockKey");
        Indexed index = field.getAnnotation(Indexed.class);
        assertNotNull(index);
        assertTrue(index.unique());
        assertTrue(index.partialFilter().contains("lockKey"));
    }

    @Test void activeSeatHoldsHaveCompoundUniqueness() {
        CompoundIndex index = SeatHold.class.getAnnotation(CompoundIndex.class);
        assertNotNull(index);
        assertTrue(index.unique());
        assertTrue(index.def().contains("scheduleId"));
        assertTrue(index.def().contains("seatNo"));
    }

    @Test void segmentLocksAreUniquePerScheduleSeatAndRouteEdge() {
        CompoundIndex index = SeatSegmentLock.class.getAnnotation(CompoundIndex.class);
        assertNotNull(index);
        assertTrue(index.unique());
        assertTrue(index.def().contains("scheduleId"));
        assertTrue(index.def().contains("seatNo"));
        assertTrue(index.def().contains("segmentIndex"));
    }

    @Test void longIdEntitiesHaveMongoIdAnnotation() throws Exception {
        assertNotNull(AppUser.class.getField("id").getAnnotation(Id.class));
        assertNotNull(BookingSeat.class.getField("id").getAnnotation(Id.class));
        assertNotNull(SeatSegmentLock.class.getField("id").getAnnotation(Id.class));
    }
}
