package com.clinic.appointment.domain.enums;

/**
 * 资源可用性状态
 */
public enum ResourceStatus {
    AVAILABLE("可用"),
    BOOKED("已预约"),
    BLOCKED("已封锁");

    private final String desc;

    ResourceStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
