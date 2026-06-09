package com.clinic.appointment.domain.enums;

/**
 * 预约类型
 */
public enum AppointmentType {
    NORMAL("普通门诊"),
    EXAM("检查型门诊");

    private final String desc;

    AppointmentType(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
