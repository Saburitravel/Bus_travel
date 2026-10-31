package com.saburi.travel.model;

import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@Document(collection = "auth_sessions")
public class AuthSession {
    @Id public String token;
    public String role;
    public Long userId;
    public String identity;
    public String phone;
    public LocalDateTime expiresAt;
}
