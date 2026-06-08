package com.clinic.appointment.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistRequest {

    @NotBlank
    private String patientId;

    @NotBlank
    private String patientName;

    @NotNull
    private Long scheduleId;
}
