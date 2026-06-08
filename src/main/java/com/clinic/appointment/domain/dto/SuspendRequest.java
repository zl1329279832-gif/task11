package com.clinic.appointment.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class SuspendRequest {
    @NotNull(message = "医生ID不能为空")
    private Long doctorId;
    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;
    @NotNull(message = "结束日期不能为空")
    private LocalDate endDate;
    private String reason;
    /** CANCEL-取消 / MIGRATE-迁移 */
    private String migrateType = "CANCEL";
}
