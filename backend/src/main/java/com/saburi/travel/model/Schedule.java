package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "schedules")
public class Schedule {
    @Id public Long id;
    public Long busId;
    public Long routeId;
    public LocalDate travelDate;
    public String departureTime;
    public String arrivalTime;
    public double fare;
    public int totalSeats;
    public String status = "Open";
}
