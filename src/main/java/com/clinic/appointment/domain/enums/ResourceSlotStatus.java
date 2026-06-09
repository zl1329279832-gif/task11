package com.clinic.appointment.domain.enums;

public enum ResourceSlotStatus {
    AVAILABLE("可用"),
    FULL("已满"),
    DISABLED("停用"),
    MAINTENANCE("维护中");

    private final String desc;

    ResourceSlotStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
