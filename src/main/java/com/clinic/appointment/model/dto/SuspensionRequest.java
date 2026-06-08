package com.clinic.appointment.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuspensionRequest {

    @NotNull
    private Long doctorId;

    @NotNull
    private LocalDate suspendDate;

    private String period;

    @NotBlank
    private String reason;

    @NotBlank
    private String action;

    private Long targetDoctorId;
}
