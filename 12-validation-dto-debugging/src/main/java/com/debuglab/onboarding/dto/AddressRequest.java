package com.debuglab.onboarding.dto;

import com.debuglab.onboarding.validation.Pincode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AddressRequest {

    @NotBlank(message = "addressLine1 is required")
    @Size(max = 200, message = "addressLine1 must be at most 200 characters")
    private String addressLine1;

    @NotBlank(message = "city is required")
    @Size(max = 80, message = "city must be at most 80 characters")
    private String city;

    @NotBlank(message = "state is required")
    @Size(max = 80, message = "state must be at most 80 characters")
    private String state;

    @NotBlank(message = "pincode is required")
    @Pincode
    private String pincode;

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }
}
