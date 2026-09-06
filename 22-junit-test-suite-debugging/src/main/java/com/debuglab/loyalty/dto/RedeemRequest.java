package com.debuglab.loyalty.dto;

import jakarta.validation.constraints.Min;

public class RedeemRequest {

    @Min(1)
    private int points;

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }
}
