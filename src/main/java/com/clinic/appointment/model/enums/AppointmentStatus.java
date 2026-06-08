package com.clinic.appointment.model.enums;

public enum AppointmentStatus {
    BOOKED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    RESCHEDULED,
    PASSED;

    public boolean canCancel() {
        return this == BOOKED;
    }

    public boolean canCheckIn() {
        return this == BOOKED;
    }

    public boolean canReschedule() {
        return this == BOOKED;
    }

    public boolean canPass() {
        return this == CHECKED_IN;
    }

    public boolean canComplete() {
        return this == CHECKED_IN;
    }
}
