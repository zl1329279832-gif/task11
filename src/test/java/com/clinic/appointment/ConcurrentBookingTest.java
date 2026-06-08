package com.clinic.appointment;

import com.clinic.appointment.exception.SlotUnavailableException;
import com.clinic.appointment.mapper.DepartmentMapper;
import com.clinic.appointment.mapper.DoctorMapper;
import com.clinic.appointment.mapper.ScheduleMapper;
import com.clinic.appointment.mapper.SlotMapper;
import com.clinic.appointment.model.dto.BookingRequest;
import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.entity.Department;
import com.clinic.appointment.model.entity.Doctor;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.SlotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests concurrent booking of the same slot.
 * Verifies that only one booking succeeds via the CAS (optimistic locking) mechanism.
 *
 * Since RedissonClient is mocked to always grant the lock, concurrency control
 * relies entirely on the optimistic locking (CAS on version column) in the database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ConcurrentBookingTest {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private SlotService slotService;

    @Autowired
    private DepartmentMapper departmentMapper;

    @Autowired
    private DoctorMapper doctorMapper;

    @Autowired
    private ScheduleMapper scheduleMapper;

    @Autowired
    private SlotMapper slotMapper;

    /**
     * Insert a department, doctor, schedule, and one slot directly via mappers.
     * Returns the auto-generated slot ID.
     */
    private Long setupTestData(String suffix) {
        LocalDate scheduleDate = LocalDate.now().plusDays(1);

        Department dept = Department.builder()
                .name("Booking Dept " + suffix)
                .code("BDEPT_" + suffix)
                .description("Test department for booking")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        departmentMapper.insert(dept);

        Doctor doctor = Doctor.builder()
                .name("Dr. Booking " + suffix)
                .code("BDOC_" + suffix)
                .title("Attending")
                .departmentId(dept.getId())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        doctorMapper.insert(doctor);

        Schedule schedule = Schedule.builder()
                .doctorId(doctor.getId())
                .scheduleDate(scheduleDate)
                .period("AM")
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(12, 0))
                .totalSlots(3)
                .bookedCount(0)
                .extraSlots(0)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        scheduleMapper.insert(schedule);

        Slot slot = Slot.builder()
                .scheduleId(schedule.getId())
                .doctorId(doctor.getId())
                .scheduleDate(scheduleDate)
                .period("AM")
                .seqNum(1)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(8, 30))
                .status("AVAILABLE")
                .isExtra(false)
                .version(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        slotMapper.insert(slot);

        return slot.getId();
    }

    @Test
    @Transactional
    void testSingleBookingSuccess() {
        Long slotId = setupTestData("SINGLE");

        BookingRequest request = BookingRequest.builder()
                .patientId("P001")
                .patientName("Patient One")
                .slotId(slotId)
                .build();

        Appointment appointment = appointmentService.book(request);

        assertNotNull(appointment);
        assertNotNull(appointment.getId());
        assertEquals("BOOKED", appointment.getStatus());
        assertEquals("P001", appointment.getPatientId());
        assertEquals("Patient One", appointment.getPatientName());
        assertEquals(slotId, appointment.getSlotId());
        assertEquals("NORMAL", appointment.getSource());

        // Verify slot is now BOOKED
        Slot updatedSlot = slotService.getById(slotId);
        assertEquals("BOOKED", updatedSlot.getStatus());
        assertEquals(1, updatedSlot.getVersion());
    }

    /**
     * Concurrent booking test: 10 threads simultaneously attempt to book the same slot.
     * Due to CAS (optimistic locking on version column), exactly one should succeed.
     *
     * This test is NOT @Transactional because each thread needs its own transaction
     * context. Data is committed directly and the threads can see it.
     *
     * Note: With H2 in-memory database, actual concurrent CAS may not perfectly
     * simulate MySQL row-level locking, but it tests the logic path.
     */
    @Test
    void testConcurrentBookingSameSlot() throws InterruptedException {
        Long slotId = setupTestData("CONCURRENT");

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start at once
                    BookingRequest request = BookingRequest.builder()
                            .patientId("P" + idx)
                            .patientName("Patient " + idx)
                            .slotId(slotId)
                            .build();
                    appointmentService.book(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one should succeed due to optimistic locking
        assertEquals(1, successCount.get(), "Exactly one booking should succeed");
        assertEquals(threadCount - 1, failCount.get(), "Rest should fail");

        // Verify slot is booked
        Slot slot = slotService.getById(slotId);
        assertEquals("BOOKED", slot.getStatus());
    }

    @Test
    @Transactional
    void testBookingUnavailableSlot() {
        Long slotId = setupTestData("UNAVAIL");

        // First booking should succeed
        BookingRequest request1 = BookingRequest.builder()
                .patientId("P_FIRST")
                .patientName("First Patient")
                .slotId(slotId)
                .build();
        Appointment firstAppt = appointmentService.book(request1);
        assertNotNull(firstAppt);
        assertEquals("BOOKED", firstAppt.getStatus());

        // Second booking on same slot should throw SlotUnavailableException
        BookingRequest request2 = BookingRequest.builder()
                .patientId("P_SECOND")
                .patientName("Second Patient")
                .slotId(slotId)
                .build();

        assertThrows(SlotUnavailableException.class, () -> {
            appointmentService.book(request2);
        });

        // Verify slot is still BOOKED (unchanged by the failed attempt)
        Slot slot = slotService.getById(slotId);
        assertEquals("BOOKED", slot.getStatus());
    }
}
