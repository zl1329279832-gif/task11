package com.clinic.appointment.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class JointBookRequest {
    @NotNull(message = "患者ID不能为空")
    private Long patientId;
    private String patientName;
    @NotNull(message = "号源ID不能为空")
    private Long slotId;
    @NotNull(message = "检查类型不能为空")
    private String examType;
    private Long preferredRoomId;
    private Long preferredEquipmentId;
    private Long preferredNursingStaffId;
}
