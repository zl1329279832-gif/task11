package com.clinic.appointment;

import com.clinic.appointment.mapper.AppointmentMapper;
import com.clinic.appointment.mapper.DepartmentMapper;
import com.clinic.appointment.mapper.DoctorMapper;
import com.clinic.appointment.mapper.ScheduleMapper;
import com.clinic.appointment.mapper.SlotMapper;
import com.clinic.appointment.model.dto.BookingRequest;
import com.clinic.appointment.model.dto.SuspensionRequest;
import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.entity.Department;
import com.clinic.appointment.model.entity.Doctor;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.model.entity.Suspension;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.SuspensionService;
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
 * Tests doctor suspension with both CANCEL and MIGRATE actions.
 * Verifies schedule status changes, appointment status transitions,
 * slot release, and partial migration behavior.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SuspensionMigrationTest {

    @Autowired
    private SuspensionService suspensionService;

    @Autowired
    private AppointmentService appointmentService;

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

    /** Holds references to test entities created by the setup helper. */
    private static class TestScheduleData {
        Long departmentId;
        Long doctorId;
        Long scheduleId;
        List<Long> slotIds = new ArrayList<>();
    }

    /**
     * Create a department (unless deptId provided), doctor, schedule, and slots.
     * All slots start as AVAILABLE with version 0.
     */
    private TestScheduleData createScheduleWithSlots(String suffix, int slotCount,
                                                     Long deptId, LocalDate date) {
        TestScheduleData data = new TestScheduleData();

        if (date == null) {
            date = LocalDate.now().plusDays(1);
        }

        // Create or reuse department
        if (deptId == null) {
            Department dept = Department.builder()
                    .name("Susp Dept " + suffix)
                    .code("SDEPT_" + suffix)
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            departmentMapper.insert(dept);
            data.departmentId = dept.getId();
        } else {
            data.departmentId = deptId;
        }

        // Create doctor
        Doctor doctor = Doctor.builder()
                .name("Dr. " + suffix)
                .code("SDOC_" + suffix)
                .title("Attending")
                .departmentId(data.departmentId)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        doctorMapper.insert(doctor);
        data.doctorId = doctor.getId();

        // Create schedule
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

        // Create individual slots
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
        }

        return data;
    }

    /** Convenience overload: creates a new department automatically. */
    private TestScheduleData createScheduleWithSlots(String suffix, int slotCount) {
        return createScheduleWithSlots(suffix, slotCount, null, null);
    }

    /** Book a single slot through the service layer. */
    private Appointment bookSlot(Long slotId, String patientId, String patientName) {
        BookingRequest request = BookingRequest.builder()
                .patientId(patientId)
                .patientName(patientName)
                .slotId(slotId)
                .build();
        return appointmentService.book(request);
    }

    @Test
    @Transactional
    void testSuspendWithCancelAll() {
        // Setup: create schedule with 3 slots, book 2 of them
        TestScheduleData data = createScheduleWithSlots("CANCEL", 3);
        bookSlot(data.slotIds.get(0), "PC1", "Patient Cancel 1");
        bookSlot(data.slotIds.get(1), "PC2", "Patient Cancel 2");

        // Suspend with CANCEL action
        SuspensionRequest suspensionRequest = SuspensionRequest.builder()
                .doctorId(data.doctorId)
                .suspendDate(LocalDate.now().plusDays(1))
                .period("AM")
                .reason("Doctor emergency")
                .action("CANCEL")
                .build();

        Suspension suspension = suspensionService.suspend(suspensionRequest);

        // Verify suspension record created
        assertNotNull(suspension);
        assertNotNull(suspension.getId());
        assertEquals("CANCEL", suspension.getActionTaken());
        assertEquals(data.doctorId, suspension.getDoctorId());

        // Verify schedule is SUSPENDED
        Schedule schedule = scheduleMapper.selectById(data.scheduleId);
        assertEquals("SUSPENDED", schedule.getStatus());

        // Verify all booked appointments are CANCELLED with correct reason
        List<Appointment> appointments = appointmentMapper.selectByScheduleId(data.scheduleId);
        long cancelledCount = appointments.stream()
                .filter(a -> "CANCELLED".equals(a.getStatus()))
                .count();
        assertEquals(2, cancelledCount, "Both booked appointments should be cancelled");

        for (Appointment appt : appointments) {
            if ("CANCELLED".equals(appt.getStatus())) {
                assertNotNull(appt.getCancelReason());
                assertTrue(appt.getCancelReason().contains("Doctor suspension"),
                        "Cancel reason should reference doctor suspension");
            }
        }

        // Verify all slots are released back to AVAILABLE
        for (Long slotId : data.slotIds) {
            Slot slot = slotMapper.selectById(slotId);
            assertEquals("AVAILABLE", slot.getStatus(),
                    "Slot " + slotId + " should be released to AVAILABLE");
        }
    }

    @Test
    @Transactional
    void testSuspendWithMigration() {
        LocalDate date = LocalDate.now().plusDays(1);

        // Create shared department
        Department dept = Department.builder()
                .name("Migration Dept")
                .code("MDEPT_MIG")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        departmentMapper.insert(dept);

        // Setup doctorA with 3 slots, book 2
        TestScheduleData dataA = createScheduleWithSlots("MIGA", 3, dept.getId(), date);
        bookSlot(dataA.slotIds.get(0), "PM1", "Patient Migrate 1");
        bookSlot(dataA.slotIds.get(1), "PM2", "Patient Migrate 2");

        // Setup doctorB with 3 available slots (all AVAILABLE)
        TestScheduleData dataB = createScheduleWithSlots("MIGB", 3, dept.getId(), date);

        // Suspend doctorA with MIGRATE to doctorB
        SuspensionRequest request = SuspensionRequest.builder()
                .doctorId(dataA.doctorId)
                .suspendDate(date)
                .period("AM")
                .reason("Doctor leave")
                .action("MIGRATE")
                .targetDoctorId(dataB.doctorId)
                .build();

        Suspension suspension = suspensionService.suspend(request);

        // Verify suspension record
        assertNotNull(suspension);
        assertEquals("MIGRATE", suspension.getActionTaken());
        assertEquals(dataB.doctorId, suspension.getTargetDoctorId());

        // Verify doctorA's schedule is SUSPENDED
        Schedule scheduleA = scheduleMapper.selectById(dataA.scheduleId);
        assertEquals("SUSPENDED", scheduleA.getStatus());

        // Verify old appointments on doctorA are RESCHEDULED
        List<Appointment> oldAppointments = appointmentMapper.selectByScheduleId(dataA.scheduleId);
        long rescheduledCount = oldAppointments.stream()
                .filter(a -> "RESCHEDULED".equals(a.getStatus()))
                .count();
        assertEquals(2, rescheduledCount, "Both appointments should be rescheduled");

        // Verify new appointments created on doctorB's schedule
        List<Appointment> newAppointments = appointmentMapper.selectByScheduleId(dataB.scheduleId);
        assertEquals(2, newAppointments.size(), "2 new appointments should exist on target schedule");

        for (Appointment appt : newAppointments) {
            assertEquals("BOOKED", appt.getStatus());
            assertEquals("MIGRATION", appt.getSource());
            assertEquals(dataB.doctorId, appt.getDoctorId());
            assertNotNull(appt.getOriginalAppointmentId(),
                    "New appointment should reference the original");
        }

        // Verify doctorB's schedule booked count increased
        Schedule scheduleB = scheduleMapper.selectById(dataB.scheduleId);
        assertEquals(2, scheduleB.getBookedCount());
    }

    @Test
    @Transactional
    void testSuspendWithPartialMigration() {
        LocalDate date = LocalDate.now().plusDays(1);

        // Create shared department
        Department dept = Department.builder()
                .name("Partial Dept")
                .code("PDEPT_PART")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        departmentMapper.insert(dept);

        // DoctorA with 3 slots, book all 3
        TestScheduleData dataA = createScheduleWithSlots("PARTA", 3, dept.getId(), date);
        bookSlot(dataA.slotIds.get(0), "PP1", "Patient Partial 1");
        bookSlot(dataA.slotIds.get(1), "PP2", "Patient Partial 2");
        bookSlot(dataA.slotIds.get(2), "PP3", "Patient Partial 3");

        // DoctorB with only 1 available slot
        TestScheduleData dataB = createScheduleWithSlots("PARTB", 1, dept.getId(), date);

        // Suspend doctorA with MIGRATE to doctorB
        SuspensionRequest request = SuspensionRequest.builder()
                .doctorId(dataA.doctorId)
                .suspendDate(date)
                .period("AM")
                .reason("Partial migration test")
                .action("MIGRATE")
                .targetDoctorId(dataB.doctorId)
                .build();

        Suspension suspension = suspensionService.suspend(request);
        assertNotNull(suspension);

        // Verify old appointments: 1 RESCHEDULED, 2 CANCELLED
        List<Appointment> oldAppointments = appointmentMapper.selectByScheduleId(dataA.scheduleId);
        long rescheduledCount = oldAppointments.stream()
                .filter(a -> "RESCHEDULED".equals(a.getStatus()))
                .count();
        long cancelledCount = oldAppointments.stream()
                .filter(a -> "CANCELLED".equals(a.getStatus()))
                .count();

        assertEquals(1, rescheduledCount, "1 appointment should be migrated (rescheduled)");
        assertEquals(2, cancelledCount, "2 appointments should be cancelled (not enough target slots)");

        // Verify cancelled appointments mention insufficient slots
        oldAppointments.stream()
                .filter(a -> "CANCELLED".equals(a.getStatus()))
                .forEach(a -> {
                    assertNotNull(a.getCancelReason());
                    assertTrue(a.getCancelReason().contains("not enough slots"),
                            "Cancel reason should explain insufficient target slots");
                });

        // Verify 1 new appointment on doctorB
        List<Appointment> newAppointments = appointmentMapper.selectByScheduleId(dataB.scheduleId);
        assertEquals(1, newAppointments.size(), "Exactly 1 appointment should be migrated to target");
        assertEquals("BOOKED", newAppointments.get(0).getStatus());
        assertEquals("MIGRATION", newAppointments.get(0).getSource());
    }
}
