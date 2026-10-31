package com.saburi.travel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;

/** An independently configured fare for one boarding/destination pair on a route. */
@Document(collection = "route_fares")
@CompoundIndex(name = "uniq_route_fare_pair", def = "{ 'routeId': 1, 'fromKey': 1, 'toKey': 1 }", unique = true)
public class RouteFare {
    @Id public Long id;
    public Long routeId;
    /** ORIGIN or STOP:<id>. */
    public String fromKey;
    /** STOP:<id> or DESTINATION. */
    public String toKey;
    /** Cached labels for administration; keys remain the stable references. */
    public String fromLabel;
    public String toLabel;
    public double fare;
}
