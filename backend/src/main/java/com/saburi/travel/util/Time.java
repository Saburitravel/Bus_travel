package com.saburi.travel.util;

import com.saburi.travel.model.Schedule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;

@Component
public class Time {
    private final ZoneId zone;

    public Time(@Value("${app.zone:Asia/Kolkata}") String zone) { this.zone = ZoneId.of(zone); }

    public LocalDateTime now() { return LocalDateTime.now(zone); }
    public LocalDate today() { return LocalDate.now(zone); }

    public LocalDateTime departure(Schedule s) {
        return LocalDateTime.of(s.travelDate, LocalTime.parse(s.departureTime));
    }
}
