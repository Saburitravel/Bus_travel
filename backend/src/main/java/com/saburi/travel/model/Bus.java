package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "buses")
public class Bus {
    @Id public Long id;
    @Indexed(unique = true) public String busNumber;
    public String operatorName;
    public String type;
    public int totalSeats;
    public String registrationNumber;
    public String status = "Active";
}
