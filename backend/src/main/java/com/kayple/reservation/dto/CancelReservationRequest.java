package com.kayple.reservation.dto;

import jakarta.validation.constraints.NotBlank;

public class CancelReservationRequest {

    @NotBlank
    private String phone;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
