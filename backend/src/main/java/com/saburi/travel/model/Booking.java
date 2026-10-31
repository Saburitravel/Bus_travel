package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "bookings")
public class Booking {
    @Id public Long id;
    public Long userId;
    /** SHA-256 hash of a high-entropy guest access secret; never return this value to clients. */
    public String guestAccessTokenHash;
    public String customerPhone;
    public String customerName;
    public String customerEmail;
    public Long scheduleId;
    public String journeyFrom;
    public String journeyTo;
    /** Zero-based route point snapshots; toIndex is exclusive for segment edges. */
    public Integer journeyFromIndex;
    public Integer journeyToIndex;
    public long amount;
    public String status = "Pending";
    public String paymentMethod;
    public String offerCode;
    public LocalDateTime createdAt;
    public LocalDateTime cancelledAt;
}
