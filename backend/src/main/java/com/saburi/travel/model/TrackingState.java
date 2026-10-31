package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "tracking_states")
public class TrackingState {
    @Id public Long scheduleId;
    public int progress;
    public LocalDateTime updatedAt;
}
