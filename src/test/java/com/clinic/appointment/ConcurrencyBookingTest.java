package com.clinic.appointment;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AppointmentService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发预约测试
 *
 * 场景：同一个号源被多个患者同时预约，验证只有一个能成功。
 *
 * 运行前提：需要MySQL和Redis。可用 docker-compose 启动：
 *   docker run -d --name clinic-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root123 -e MYSQL_DATABASE=clinic mysql:8
 *   docker run -d --name clinic-redis -p 6379:6379 redis:7
 * 然后执行 src/main/resources/schema.sql 初始化表结构。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConcurrencyBookingTest {

    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DoctorScheduleMapper scheduleMapper;
    @Autowired private ScheduleSlotMapper slotMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private AppointmentService appointmentService;

    private static Long testDeptId;
    private static Long testDoctorId;
    private static Long testScheduleId;
    private static Long testSlotId;

    @BeforeAll
    static void initClass() {
        // Will be set in setupTestData
    }

    @BeforeEach
    void setupTestData() {
        // 清理旧数据
        appointmentMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);

        // 1. 创建科室
        Department dept = new Department();
        dept.setName("内科");
        dept.setCode("NEIKE");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        testDeptId = dept.getId();

        // 2. 创建医生
        Doctor doctor = new Doctor();
        doctor.setName("张医生");
        doctor.setEmployeeNo("D001");
        doctor.setDepartmentId(testDeptId);
        doctor.setTitle("主任医师");
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        testDoctorId = doctor.getId();

        // 3. 创建排班
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctorId(testDoctorId);
        schedule.setDepartmentId(testDeptId);
        schedule.setScheduleDate(tomorrow);
        schedule.setTimePeriod("MORNING");
        schedule.setTotalSlots(5);
        schedule.setBookedSlots(0);
        schedule.setExtraSlots(0);
        schedule.setStatus("NORMAL");
        scheduleMapper.insert(schedule);
        testScheduleId = schedule.getId();

        // 4. 创建号源（只创建1个，用于并发竞争）
        ScheduleSlot slot = new ScheduleSlot();
        slot.setScheduleId(testScheduleId);
        slot.setDoctorId(testDoctorId);
        slot.setDepartmentId(testDeptId);
        slot.setSlotDate(tomorrow);
        slot.setSlotTime(LocalTime.of(9, 0));
        slot.setSlotNo(1);
        slot.setStatus(SlotStatus.AVAILABLE.name());
        slot.setIsExtra(0);
        slot.setVersion(0);
        slotMapper.insert(slot);
        testSlotId = slot.getId();
    }

    @Test
    @DisplayName("并发预约同一号源：仅1人成功")
    void concurrentBookingOnlyOneSucceeds() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        // 提交10个并发预约请求
        for (int i = 0; i < threadCount; i++) {
            final int patientId = 1000 + i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 所有线程同时开始

                    BookRequest req = new BookRequest();
                    req.setPatientId((long) patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(testSlotId);

                    appointmentService.book(req);
                    successCount.incrementAndGet();
                    log.info("患者{}预约成功", patientId);
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    errors.add("患者" + patientId + ": " + e.getMessage());
                    log.debug("患者{}预约失败: {}", patientId, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // 同时释放所有线程
        startLatch.countDown();
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // ── 断言 ──
        log.info("并发结果: success={}, fail={}", successCount.get(), failCount.get());

        assertEquals(1, successCount.get(), "应该只有1个预约成功");
        assertEquals(threadCount - 1, failCount.get(), "其余应该失败");

        // 验证号源状态
        ScheduleSlot slot = slotMapper.selectById(testSlotId);
        assertEquals(SlotStatus.BOOKED.name(), slot.getStatus(), "号源应为已预约");
        assertNotNull(slot.getAppointmentId(), "号源应关联预约ID");

        // 验证只创建了一条有效预约
        List<Appointment> appointments = appointmentMapper.findByDoctorAndDate(
                testDoctorId, LocalDate.now().plusDays(1));
        assertEquals(1, appointments.size(), "应该只有1条预约记录");
    }

    @Test
    @DisplayName("多号源并发预约：不同号源各自成功")
    void concurrentBookingDifferentSlots() throws Exception {
        // 额外创建4个号源（加上之前的共5个）
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        for (int i = 2; i <= 5; i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setScheduleId(testScheduleId);
            slot.setDoctorId(testDoctorId);
            slot.setDepartmentId(testDeptId);
            slot.setSlotDate(tomorrow);
            slot.setSlotTime(LocalTime.of(9, 0).plusMinutes(i * 10L));
            slot.setSlotNo(i);
            slot.setStatus(SlotStatus.AVAILABLE.name());
            slot.setIsExtra(0);
            slot.setVersion(0);
            slotMapper.insert(slot);
        }

        // 5个患者各约不同的号源
        int slotCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(slotCount);
        CountDownLatch latch = new CountDownLatch(slotCount);
        AtomicInteger successCount = new AtomicInteger(0);

        List<ScheduleSlot> allSlots = slotMapper.findAvailable(testDoctorId, tomorrow);

        for (int i = 0; i < slotCount; i++) {
            final int patientId = 2000 + i;
            final Long slotId = allSlots.get(i).getId();
            executor.submit(() -> {
                try {
                    BookRequest req = new BookRequest();
                    req.setPatientId((long) patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(slotId);
                    appointmentService.book(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("患者{}预约失败: {}", patientId, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(slotCount, successCount.get(), "5个不同号源应该都能预约成功");
    }
}
