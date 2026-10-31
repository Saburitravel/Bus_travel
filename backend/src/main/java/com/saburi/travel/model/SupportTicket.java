package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "support_tickets")
public class SupportTicket {
    @Id public Long id;
    public Long userId;
    public String phone;
    public String subject;
    public String message;
    public String status = "OPEN";
    public LocalDateTime createdAt;
}
