package com.debuglab.onboarding.dto;

import java.time.LocalDateTime;

public class CustomerResponse {

    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private int age;
    private String addressLine1;
    private String city;
    private String state;
    private String pincode;
    private boolean marketingOptIn;
    private LocalDateTime createdAt;

    public CustomerResponse(Long id, String fullName, String email, String phone, int age,
                            String addressLine1, String city, String state, String pincode,
                            boolean marketingOptIn, LocalDateTime createdAt) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.age = age;
        this.addressLine1 = addressLine1;
        this.city = city;
        this.state = state;
        this.pincode = pincode;
        this.marketingOptIn = marketingOptIn;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public int getAge() {
        return age;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPincode() {
        return pincode;
    }

    public boolean isMarketingOptIn() {
        return marketingOptIn;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
