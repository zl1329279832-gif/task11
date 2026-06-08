package com.clinic.appointment.model.enums;

/**
 * Slot status state machine:
 *
 *   AVAILABLE ──lock──▶ LOCKED ──confirm──▶ BOOKED ──checkIn──▶ CHECKED_IN ──complete──▶ COMPLETED
 *       ▲                  │                   │                                │
 *       │              timeout/fail            │                              pass
 *       │                  │                cancel                              │
 *       └──────release─────┘                   │                                ▼
 *       └─────────────release──────────────────┘                             PASSED
 *
 *   EXTRA: extra slots added ad-hoc, follow same flow as AVAILABLE after creation
 */
public enum SlotStatus {
    /** Slot open for booking */
    AVAILABLE,
    /** Temporarily locked during booking transaction */
    LOCKED,
    /** Successfully booked */
    BOOKED,
    /** Patient checked in */
    CHECKED_IN,
    /** Visit completed */
    COMPLETED,
    /** Booking cancelled, slot released */
    CANCELLED,
    /** Patient missed their turn */
    PASSED;

    public boolean canLock() {
        return this == AVAILABLE;
    }

    public boolean canBook() {
        return this == LOCKED;
    }

    public boolean canCancel() {
        return this == BOOKED;
    }

    public boolean canCheckIn() {
        return this == BOOKED;
    }

    public boolean canPass() {
        return this == CHECKED_IN;
    }

    public boolean canComplete() {
        return this == CHECKED_IN;
    }
}
