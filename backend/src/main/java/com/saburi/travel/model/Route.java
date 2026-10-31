package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "routes")
public class Route {
    @Id public Long id;
    @Indexed(unique = true) public String routeCode;
    public String origin;
    public String destination;
    public String distance;
    public int stops;
    public String duration;
    public String status = "Active";
}
