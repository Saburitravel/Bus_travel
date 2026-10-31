package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "route_stops")
public class RouteStop {
    @Id public Long id;
    public Long routeId;
    public int stopOrder;
    public String name;
    public String city;
    public Double latitude;
    public Double longitude;
    /** Fare from the immediately preceding route point to this stop. */
    public Double segmentFare;
}
