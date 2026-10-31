package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "refunds")
public class Refund {
    @Id public Long id;
    public Long bookingId;
    public long amount;
    public String status;
    public String reason;
    public LocalDateTime createdAt;
}
