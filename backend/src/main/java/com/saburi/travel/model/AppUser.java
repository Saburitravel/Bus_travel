package com.saburi.travel.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.time.LocalDate;


@Document(collection = "app_users")
public class AppUser {
    @Id public Long id;
    public String name;
    @Indexed(unique = true) public String phone;
    public String email;
    public String passwordHash;
    public String role = "CUSTOMER";
    public String city;
    public String status = "ACTIVE";
    public LocalDate joined;
}
