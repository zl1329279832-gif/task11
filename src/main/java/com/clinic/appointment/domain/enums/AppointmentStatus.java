package com.clinic.appointment.domain.enums;

/**
 * 预约状态机
 * <pre>
 *   PENDING ──confirm──▶ CONFIRMED ──checkin──▶ CHECKED_IN (终态)
 *      │                    │
 *      │                    ├──cancel──▶ CANCELLED (终态)
 *      │                    │
 *      │                    └──reschedule──▶ RESCHEDULED (终态，新预约产生)
 *      │
 *      ├──cancel──▶ CANCELLED (终态)
 *      │
 *      └──timeout──▶ CANCELLED (终态)
 *
 *   另外: CONFIRMED ──noshow──▶ MISSED (终态)
 * </pre>
 */
public enum AppointmentStatus {
    PENDING("待确认"),
    CONFIRMED("已确认"),
    CANCELLED("已取消"),
    RESCHEDULED("已改签"),
    CHECKED_IN("已签到"),
    MISSED("过号");

    private final String desc;

    AppointmentStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
