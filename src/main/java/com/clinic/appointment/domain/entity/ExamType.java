package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_type")
public class ExamType {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private Integer needRoom;
    private Integer needEquipment;
    private Integer needNursing;
    private String equipmentCode;
    private String roomCode;
    private Integer patientDailyLimit;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
