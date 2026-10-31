package com.saburi.travel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One record locks one seat on one consecutive route segment. */
@Document(collection = "seat_segment_locks")
@CompoundIndex(name = "schedule_seat_segment_unique_v1", def = "{'scheduleId': 1, 'seatNo': 1, 'segmentIndex': 1}", unique = true)
public class SeatSegmentLock {
    @Id public Long id;
    public Long scheduleId;
    public int seatNo;
    /** Zero-based edge: 0 is route origin to first intermediate stop. */
    public int segmentIndex;
    /** Non-null while held temporarily; cleared when converted to a booking lock. */
    public String token;
    public LocalDateTime expiresAt;
    public String clientIp;
    /** Non-null once the segment is reserved by a pending/confirmed booking. */
    public Long bookingId;
}
