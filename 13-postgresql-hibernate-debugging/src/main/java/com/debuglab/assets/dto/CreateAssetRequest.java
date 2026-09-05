package com.debuglab.assets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public class CreateAssetRequest {

    @NotBlank(message = "assetTag is required")
    private String assetTag;

    @NotBlank(message = "name is required")
    private String name;

    private Long categoryId;

    private String location;

    @NotNull(message = "purchaseCost is required")
    private Double purchaseCost;

    @NotNull(message = "purchasedAt is required")
    private LocalDate purchasedAt;

    public String getAssetTag() {
        return assetTag;
    }

    public void setAssetTag(String assetTag) {
        this.assetTag = assetTag;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Double getPurchaseCost() {
        return purchaseCost;
    }

    public void setPurchaseCost(Double purchaseCost) {
        this.purchaseCost = purchaseCost;
    }

    public LocalDate getPurchasedAt() {
        return purchasedAt;
    }

    public void setPurchasedAt(LocalDate purchasedAt) {
        this.purchasedAt = purchasedAt;
    }
}
