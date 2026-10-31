package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

/** One seat of a booking plus its passenger. lockKey = "scheduleId:seatNo" while active, NULL once cancelled; its UNIQUE constraint makes double-booking impossible. */
@Document(collection = "booking_seats")
public class BookingSeat {
    @Id public Long id;
    public Long bookingId;
    public Long scheduleId;
    public int seatNo;
    @Indexed(unique = true, partialFilter = "{'lockKey': {$type: 'string'}}") public String lockKey;
    public String passengerName;
    public Integer age;
    public String gender;
    public String mobile;
    public String email;
}
