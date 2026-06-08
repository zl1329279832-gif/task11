package com.clinic.appointment;

import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.AppointmentMapper;
import com.clinic.appointment.mapper.DepartmentMapper;
import com.clinic.appointment.mapper.DoctorMapper;
import com.clinic.appointment.mapper.ScheduleMapper;
import com.clinic.appointment.mapper.SlotMapper;
import com.clinic.appointment.mapper.WaitlistMapper;
import com.clinic.appointment.model.dto.BookingRequest;
import com.clinic.appointment.model.dto.CancelRequest;
import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.entity.Department;
import com.clinic.appointment.model.entity.Doctor;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.model.entity.Waitlist;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.WaitlistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the waitlist queue and automatic backfill on cancellation.
 * Verifies join, FIFO ordering, capacity limits, backfill conversion, and cancel.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class WaitlistBackfillTest {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private WaitlistService waitlistService;

    @Autowired
    private DepartmentMapper departmentMapper;

    @Autowired
    private DoctorMapper doctorMapper;

    @Autowired
    private ScheduleMapper scheduleMapper;

    @Autowired
    private SlotMapper slotMapper;

    @Autowired
    private AppointmentMapper appointmentMapper;

    @Autowired
    private WaitlistMapper waitlistMapper;

    /** Holds references to test entities created by the setup helper. */
    private static class TestData {
        Long departmentId;
        Long doctorId;
        Long scheduleId;
        List<Long> slotIds = new ArrayList<>();
        List<Appointment> bookedAppointments = new ArrayList<>();
    }

    /**
     * Create a schedule with the given number of slots, then book ALL of them.
     * Returns test data including slot IDs and booked appointments.
     */
    private TestData setupFullyBookedSchedule(String suffix, int slotCount) {
        TestData data = new TestData();
        LocalDate date = LocalDate.now().plusDays(1);

        Department dept = Department.builder()
                .name("WL Dept " + suffix)
                .code("WDEPT_" + suffix)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        departmentMapper.insert(dept);
        data.departmentId = dept.getId();

        Doctor doctor = Doctor.builder()
                .name("Dr. WL " + suffix)
                .code("WDOC_" + suffix)
                .title("Attending")
                .departmentId(dept.getId())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        doctorMapper.insert(doctor);
        data.doctorId = doctor.getId();

        Schedule schedule = Schedule.builder()
                .doctorId(doctor.getId())
                .scheduleDate(date)
                .period("AM")
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(12, 0))
                .totalSlots(slotCount)
                .bookedCount(0)
                .extraSlots(0)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        scheduleMapper.insert(schedule);
        data.scheduleId = schedule.getId();

        // Create and book all slots
        for (int i = 0; i < slotCount; i++) {
            Slot slot = Slot.builder()
                    .scheduleId(schedule.getId())
                    .doctorId(doctor.getId())
                    .scheduleDate(date)
                    .period("AM")
                    .seqNum(i + 1)
                    .startTime(LocalTime.of(8 + i, 0))
                    .endTime(LocalTime.of(8 + i, 30))
                    .status("AVAILABLE")
                    .isExtra(false)
                    .version(0)
                    .build();
            slotMapper.insert(slot);
            data.slotIds.add(slot.getId());

            // Book the slot
            BookingRequest request = BookingRequest.builder()
                    .patientId("WP_" + suffix + "_" + i)
                    .patientName("Wait Patient " + suffix + " " + i)
                    .slotId(slot.getId())
                    .build();
            Appointment appt = appointmentService.book(request);
            data.bookedAppointments.add(appt);
        }

        return data;
    }

    @Test
    @Transactional
    void testJoinWaitlist() {
        // Setup: schedule with all slots booked
        TestData data = setupFullyBookedSchedule("JOIN", 2);

        // Join waitlist
        Waitlist entry = waitlistService.joinWaitlist("WC_JOIN", "Patient C Join", data.scheduleId);

        // Verify waitlist entry created
        assertNotNull(entry);
        assertNotNull(entry.getId());
        assertEquals("WAITING", entry.getStatus());
        assertEquals(1, entry.getQueuePosition());
        assertEquals(data.scheduleId, entry.getScheduleId());
        assertEquals(data.doctorId, entry.getDoctorId());
        assertEquals(data.departmentId, entry.getDepartmentId());
        assertEquals("WC_JOIN", entry.getPatientId());
        assertEquals("Patient C Join", entry.getPatientName());
    }

    @Test
    @Transactional
    void testWaitlistBackfillOnCancel() {
        // Setup: schedule with 2 slots, both booked
        TestData data = setupFullyBookedSchedule("BACKFILL", 2);

        // Patient C joins waitlist
        Waitlist waitlistEntry = waitlistService.joinWaitlist(
                "WC_BF", "Patient C Backfill", data.scheduleId);
        assertEquals("WAITING", waitlistEntry.getStatus());

        // Cancel the appointment on slot 0 -- this triggers tryFillFromWaitlist
        Appointment apptToCancel = data.bookedAppointments.get(0);
        CancelRequest cancelRequest = CancelRequest.builder()
                .appointmentId(apptToCancel.getId())
                .reason("Patient cannot attend")
                .build();
        appointmentService.cancel(cancelRequest);

        // Verify waitlist entry was converted
        Waitlist updatedEntry = waitlistMapper.selectById(waitlistEntry.getId());
        assertEquals("CONVERTED", updatedEntry.getStatus(),
                "Waitlist entry should be converted after slot was freed");

        // Verify a new appointment was created for Patient C from the waitlist
        List<Appointment> patientCAppointments = appointmentMapper.selectByPatientId("WC_BF");
        assertFalse(patientCAppointments.isEmpty(), "Patient C should have an appointment");

        boolean hasWaitlistAppointment = patientCAppointments.stream()
                .anyMatch(a -> "BOOKED".equals(a.getStatus()) && "WAITLIST".equals(a.getSource()));
        assertTrue(hasWaitlistAppointment,
                "Patient C should have a BOOKED appointment with source WAITLIST");

        // Verify the new appointment is on the correct schedule
        Appointment waitlistAppt = patientCAppointments.stream()
                .filter(a -> "WAITLIST".equals(a.getSource()))
                .findFirst()
                .orElseThrow();
        assertEquals(data.scheduleId, waitlistAppt.getScheduleId());
        assertEquals(data.doctorId, waitlistAppt.getDoctorId());
    }

    @Test
    @Transactional
    void testWaitlistQueueOrder() {
        // Setup: schedule with 2 slots, both booked
        TestData data = setupFullyBookedSchedule("ORDER", 2);

        // Patient A joins waitlist at position 1
        Waitlist entryA = waitlistService.joinWaitlist(
                "WO_A", "Patient A Order", data.scheduleId);
        // Patient B joins waitlist at position 2
        Waitlist entryB = waitlistService.joinWaitlist(
                "WO_B", "Patient B Order", data.scheduleId);

        assertEquals(1, entryA.getQueuePosition(), "Patient A should be at position 1");
        assertEquals(2, entryB.getQueuePosition(), "Patient B should be at position 2");

        // Cancel one appointment to trigger backfill
        Appointment apptToCancel = data.bookedAppointments.get(0);
        appointmentService.cancel(CancelRequest.builder()
                .appointmentId(apptToCancel.getId())
                .reason("Cancel for queue order test")
                .build());

        // Patient A (position 1, first in queue) should get the freed slot
        Waitlist updatedA = waitlistMapper.selectById(entryA.getId());
        assertEquals("CONVERTED", updatedA.getStatus(),
                "Patient A (first in queue) should be converted");

        // Patient B should still be WAITING
        Waitlist updatedB = waitlistMapper.selectById(entryB.getId());
        assertEquals("WAITING", updatedB.getStatus(),
                "Patient B (second in queue) should still be waiting");

        // Verify Patient A has the new appointment
        List<Appointment> patientAAppointments = appointmentMapper.selectByPatientId("WO_A");
        boolean patientABooked = patientAAppointments.stream()
                .anyMatch(a -> "BOOKED".equals(a.getStatus()) && "WAITLIST".equals(a.getSource()));
        assertTrue(patientABooked, "Patient A should have the backfilled appointment");

        // Verify Patient B does NOT have a new appointment
        List<Appointment> patientBAppointments = appointmentMapper.selectByPatientId("WO_B");
        boolean patientBBooked = patientBAppointments.stream()
                .anyMatch(a -> "BOOKED".equals(a.getStatus()) && "WAITLIST".equals(a.getSource()));
        assertFalse(patientBBooked, "Patient B should NOT have a backfilled appointment yet");
    }

    @Test
    @Transactional
    void testWaitlistFull() {
        // Setup: schedule with all slots booked
        TestData data = setupFullyBookedSchedule("FULL", 2);

        // Fill waitlist to max capacity (5 in test config: application-test.yml)
        for (int i = 0; i < 5; i++) {
            waitlistService.joinWaitlist(
                    "WF_" + i, "Full Patient " + i, data.scheduleId);
        }

        // Try to join one more -- should throw BusinessException
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            waitlistService.joinWaitlist(
                    "WF_EXTRA", "Extra Patient", data.scheduleId);
        });
        assertEquals("Waitlist is full", ex.getMessage());
    }

    @Test
    @Transactional
    void testCancelWaitlistEntry() {
        // Setup: schedule with all slots booked
        TestData data = setupFullyBookedSchedule("WCANCEL", 2);

        // Join waitlist
        Waitlist entry = waitlistService.joinWaitlist(
                "WCANCEL_P", "Cancel WL Patient", data.scheduleId);
        assertEquals("WAITING", entry.getStatus());

        // Cancel the waitlist entry
        waitlistService.cancelWaitlist(entry.getId());

        // Verify status is CANCELLED
        Waitlist cancelled = waitlistMapper.selectById(entry.getId());
        assertEquals("CANCELLED", cancelled.getStatus(),
                "Cancelled waitlist entry should have CANCELLED status");
    }
}
