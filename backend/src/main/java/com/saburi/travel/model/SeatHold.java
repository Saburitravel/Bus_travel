package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "seat_holds")
@CompoundIndex(name = "schedule_seat_unique", def = "{'scheduleId': 1, 'seatNo': 1}", unique = true)
public class SeatHold {
    @Id public Long id;
    public String token;
    public Long scheduleId;
    public int seatNo;
    public LocalDateTime expiresAt;
    public String clientIp;
    public Long bookingId;
}
