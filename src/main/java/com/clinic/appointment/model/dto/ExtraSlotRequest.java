package com.clinic.appointment.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtraSlotRequest {

    @NotNull
    private Long scheduleId;

    @NotNull
    @Min(1)
    private Integer count;
}
