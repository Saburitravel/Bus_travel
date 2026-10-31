package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "payments")
public class Payment {
    @Id public Long id;
    public Long bookingId;
    public long amount;
    public String method;
    public String status;
    public String providerOrderId;
    public String providerPaymentId;
    public String providerSignature;
    public LocalDateTime verifiedAt;
    public LocalDateTime createdAt;
}
