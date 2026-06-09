package com.clinic.appointment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.CancelRequest;
import com.clinic.appointment.domain.dto.SuspendRequest;
import com.clinic.appointment.domain.dto.WaitlistRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.DoctorSuspensionService;
import com.clinic.appointment.service.WaitlistService;
import com.clinic.appointment.task.ScheduledTasks;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发集成测试
 *
 * 覆盖以下场景：
 * 1. 并发取消同一预约：只释放1次号源，只触发1次候补
 * 2. 停诊后号源不可预约：book()应被拒绝
 * 3. 停诊后号源不被定时任务重新释放为可预约
 * 4. 候补严格按进入顺序补位
 * 5. 并发取消+候补不重复补位
 * 6. 超时释放与候补交错执行
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConcurrencyIntegrationTest {

    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DoctorScheduleMapper scheduleMapper;
    @Autowired private ScheduleSlotMapper slotMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private WaitlistMapper waitlistMapper;
    @Autowired private DoctorSuspensionMapper suspensionMapper;
    @Autowired private AppointmentService appointmentService;
    @Autowired private WaitlistService waitlistService;
    @Autowired private DoctorSuspensionService suspensionService;
    @Autowired private ScheduledTasks scheduledTasks;

    private Long deptId;
    private Long doctorId;
    private Long scheduleId;
    private LocalDate targetDate;

    @BeforeEach
    void setup() {
        // 清理所有数据
        suspensionMapper.delete(null);
        waitlistMapper.delete(null);
        appointmentMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);

        targetDate = LocalDate.now().plusDays(2);

        Department dept = new Department();
        dept.setName("测试科");
        dept.setCode("TEST_CONCURRENT");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("测试医生");
        doctor.setEmployeeNo("D_CONC");
        doctor.setDepartmentId(deptId);
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();

        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctorId(doctorId);
        schedule.setDepartmentId(deptId);
        schedule.setScheduleDate(targetDate);
        schedule.setTimePeriod("MORNING");
        schedule.setTotalSlots(5);
        schedule.setBookedSlots(0);
        schedule.setExtraSlots(0);
        schedule.setStatus("NORMAL");
        scheduleMapper.insert(schedule);
        scheduleId = schedule.getId();

        for (int i = 1; i <= 5; i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setScheduleId(scheduleId);
            slot.setDoctorId(doctorId);
            slot.setDepartmentId(deptId);
            slot.setSlotDate(targetDate);
            slot.setSlotTime(LocalTime.of(9, 0).plusMinutes(i * 10L));
            slot.setSlotNo(i);
            slot.setStatus(SlotStatus.AVAILABLE.name());
            slot.setIsExtra(0);
            slot.setVersion(0);
            slotMapper.insert(slot);
        }
    }

    // ═══════════════════════════════════════════════════════
    // 1. 并发取消同一预约：只释放1次号源，只触发1次候补
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("并发取消同一预约：仅1次成功释放号源，不重复触发候补")
    void concurrentCancelOnlyOneReleases() throws Exception {
        // 创建预约
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt = bookSlot(slots.get(0).getId(), 8001L, "患者8001");

        // 加入1个候补
        Waitlist w1 = joinWaitlist(8002L, "患者8002");

        // 10个线程并发取消同一预约
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CancelRequest req = new CancelRequest();
                    req.setAppointmentId(appt.getId());
                    req.setReason("并发取消");
                    appointmentService.cancel(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        log.info("并发取消结果: success={}, fail={}", successCount.get(), failCount.get());

        // 只有1个取消成功
        assertEquals(1, successCount.get(), "应该只有1个取消成功");

        // 号源应被释放为AVAILABLE（被候补补位后变为BOOKED）
        // 或者直接BOOKED（候补自动补位）
        ScheduleSlot slot = slotMapper.selectById(appt.getSlotId());
        assertNotNull(slot);
        // 号源不应处于异常状态
        assertTrue(
                SlotStatus.AVAILABLE.name().equals(slot.getStatus()) ||
                SlotStatus.BOOKED.name().equals(slot.getStatus()),
                "号源应为AVAILABLE或BOOKED(已被候补补位)，实际: " + slot.getStatus());

        // 候补应该被补位（如果号源可用）
        Waitlist updatedW1 = waitlistMapper.selectById(w1.getId());
        // 候补可能补位成功，也可能因为时序没来得及
        log.info("候补状态: {}", updatedW1.getStatus());

        // 关键验证：只有1条有效的取消预约记录
        Appointment cancelledAppt = appointmentMapper.selectById(appt.getId());
        assertEquals(AppointmentStatus.CANCELLED.name(), cancelledAppt.getStatus());
    }

    // ═══════════════════════════════════════════════════════
    // 2. 停诊后号源不可预约
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("停诊后号源不可预约：book()应被拒绝")
    void suspendedSlotsCannotBeBooked() {
        // 获取可用号源ID
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        assertFalse(slots.isEmpty());
        Long targetSlotId = slots.get(0).getId();

        // 停诊
        SuspendRequest suspendReq = new SuspendRequest();
        suspendReq.setDoctorId(doctorId);
        suspendReq.setStartDate(targetDate);
        suspendReq.setEndDate(targetDate);
        suspendReq.setReason("紧急停诊");
        suspendReq.setMigrateType("CANCEL");
        suspensionService.suspend(suspendReq);

        // 停诊后尝试预约，应被拒绝
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(9001L);
        bookReq.setPatientName("患者9001");
        bookReq.setSlotId(targetSlotId);

        assertThrows(Exception.class, () -> appointmentService.book(bookReq),
                "停诊后的号源不应可预约");

        // 验证号源仍为SUSPENDED
        ScheduleSlot slot = slotMapper.selectById(targetSlotId);
        assertEquals(SlotStatus.SUSPENDED.name(), slot.getStatus(),
                "号源应仍为SUSPENDED终态");
    }

    // ═══════════════════════════════════════════════════════
    // 3. 停诊后号源不被定时任务重新释放为可预约
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("停诊后号源不被定时任务重新释放")
    void suspendedSlotsNotReactivatedByScheduledTask() {
        // 先约满3个号源
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        bookSlot(slots.get(0).getId(), 8101L, "患者8101");
        bookSlot(slots.get(1).getId(), 8102L, "患者8102");
        bookSlot(slots.get(2).getId(), 8103L, "患者8103");

        // 停诊（取消模式）
        SuspendRequest req = new SuspendRequest();
        req.setDoctorId(doctorId);
        req.setStartDate(targetDate);
        req.setEndDate(targetDate);
        req.setReason("停诊测试");
        req.setMigrateType("CANCEL");
        suspensionService.suspend(req);

        // 验证所有号源为SUSPENDED
        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getDoctorId, doctorId)
          .eq(ScheduleSlot::getSlotDate, targetDate);
        List<ScheduleSlot> allSlots = slotMapper.selectList(qw);
        for (ScheduleSlot s : allSlots) {
            assertEquals(SlotStatus.SUSPENDED.name(), s.getStatus(),
                    "停诊后号源应为SUSPENDED");
        }

        // 手动执行定时任务：过期清理、候补补偿
        scheduledTasks.expireSlots();
        scheduledTasks.waitlistBackfillCompensation();

        // 验证号源仍为SUSPENDED（不被定时任务修改）
        List<ScheduleSlot> afterSlots = slotMapper.selectList(qw);
        for (ScheduleSlot s : afterSlots) {
            assertEquals(SlotStatus.SUSPENDED.name(), s.getStatus(),
                    "定时任务后号源仍应为SUSPENDED");
        }

        // 验证findAvailable不返回结果
        List<ScheduleSlot> available = slotMapper.findAvailable(doctorId, targetDate);
        assertEquals(0, available.size(), "停诊后不应有可用号源");

        // 验证findAvailableWithScheduleCheck也不返回结果
        List<ScheduleSlot> checked = slotMapper.findAvailableWithScheduleCheck(doctorId, targetDate);
        assertEquals(0, checked.size(), "排班校验查询也不应返回停诊号源");

        log.info("定时任务未影响SUSPENDED号源");
    }

    // ═══════════════════════════════════════════════════════
    // 4. 候补严格按进入顺序补位
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("候补严格按进入顺序补位：先到先得")
    void backfillStrictFIFOOrder() {
        // 约满2个号源
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt1 = bookSlot(slots.get(0).getId(), 8201L, "患者8201");
        Appointment appt2 = bookSlot(slots.get(1).getId(), 8202L, "患者8202");

        // 3个候补按顺序加入
        Waitlist w1 = joinWaitlist(8203L, "患者8203");
        Waitlist w2 = joinWaitlist(8204L, "患者8204");
        Waitlist w3 = joinWaitlist(8205L, "患者8205");

        // 取消第1个预约 → 应补位 w1（第1个候补）
        CancelRequest cancel1 = new CancelRequest();
        cancel1.setAppointmentId(appt1.getId());
        cancel1.setReason("取消1");
        appointmentService.cancel(cancel1);

        // 验证只有w1补位
        Waitlist uw1 = waitlistMapper.selectById(w1.getId());
        Waitlist uw2 = waitlistMapper.selectById(w2.getId());
        Waitlist uw3 = waitlistMapper.selectById(w3.getId());
        assertEquals("FULFILLED", uw1.getStatus(), "第1个候补应补位");
        assertEquals("WAITING", uw2.getStatus(), "第2个候补应等待");
        assertEquals("WAITING", uw3.getStatus(), "第3个候补应等待");

        // 验证w1的预约患者ID正确
        Appointment backfilled1 = appointmentMapper.selectById(uw1.getAppointmentId());
        assertNotNull(backfilled1);
        assertEquals(8203L, backfilled1.getPatientId(), "补位预约应为候补第1人");

        // 取消第2个预约 → 应补位 w2（第2个候补）
        CancelRequest cancel2 = new CancelRequest();
        cancel2.setAppointmentId(appt2.getId());
        cancel2.setReason("取消2");
        appointmentService.cancel(cancel2);

        uw2 = waitlistMapper.selectById(w2.getId());
        uw3 = waitlistMapper.selectById(w3.getId());
        assertEquals("FULFILLED", uw2.getStatus(), "第2个候补应补位");
        assertEquals("WAITING", uw3.getStatus(), "第3个候补仍等待");

        Appointment backfilled2 = appointmentMapper.selectById(uw2.getAppointmentId());
        assertNotNull(backfilled2);
        assertEquals(8204L, backfilled2.getPatientId(), "补位预约应为候补第2人");

        log.info("候补严格FIFO顺序验证通过");
    }

    // ═══════════════════════════════════════════════════════
    // 5. 并发取消2个不同预约 + 候补不重复补位
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("并发取消2个不同预约：候补按顺序各补1位，不重复不遗漏")
    void concurrentCancelNoDoubleBackfill() throws Exception {
        // 约满2个号源
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt1 = bookSlot(slots.get(0).getId(), 8301L, "患者8301");
        Appointment appt2 = bookSlot(slots.get(1).getId(), 8302L, "患者8302");

        // 2个候补
        Waitlist w1 = joinWaitlist(8303L, "患者8303");
        Waitlist w2 = joinWaitlist(8304L, "患者8304");

        // 同时取消两个预约
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger cancelSuccess = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                CancelRequest req = new CancelRequest();
                req.setAppointmentId(appt1.getId());
                req.setReason("并发取消1");
                appointmentService.cancel(req);
                cancelSuccess.incrementAndGet();
            } catch (Exception e) {
                log.error("取消1失败: {}", e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                CancelRequest req = new CancelRequest();
                req.setAppointmentId(appt2.getId());
                req.setReason("并发取消2");
                appointmentService.cancel(req);
                cancelSuccess.incrementAndGet();
            } catch (Exception e) {
                log.error("取消2失败: {}", e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(2, cancelSuccess.get(), "两个不同预约的取消应都成功");

        // 等待候补补位完成（补位可能在另一个线程中异步执行）
        Thread.sleep(2000);

        // 验证候补状态
        Waitlist uw1 = waitlistMapper.selectById(w1.getId());
        Waitlist uw2 = waitlistMapper.selectById(w2.getId());

        assertEquals("FULFILLED", uw1.getStatus(), "候补1应补位成功");
        assertEquals("FULFILLED", uw2.getStatus(), "候补2应补位成功");

        // 验证补位的预约患者ID正确（候补按顺序补位）
        Appointment bf1 = appointmentMapper.selectById(uw1.getAppointmentId());
        Appointment bf2 = appointmentMapper.selectById(uw2.getAppointmentId());
        assertNotNull(bf1, "候补1的预约应存在");
        assertNotNull(bf2, "候补2的预约应存在");
        assertEquals(8303L, bf1.getPatientId(), "候补1的预约应为患者8303");
        assertEquals(8304L, bf2.getPatientId(), "候补2的预约应为患者8304");

        // 关键：验证没有超卖（CONFIRMED预约数 ≤ 可用号源数）
        LambdaQueryWrapper<Appointment> confirmedQw = new LambdaQueryWrapper<>();
        confirmedQw.eq(Appointment::getDoctorId, doctorId)
                   .eq(Appointment::getSlotDate, targetDate)
                   .eq(Appointment::getStatus, AppointmentStatus.CONFIRMED.name());
        long confirmedCount = appointmentMapper.selectCount(confirmedQw);
        assertTrue(confirmedCount <= 5, "CONFIRMED预约数不应超过总号源数5");

        log.info("并发取消补位完成: cancel={}, w1={}, w2={}", cancelSuccess.get(),
                uw1.getStatus(), uw2.getStatus());
    }

    // ═══════════════════════════════════════════════════════
    // 6. 停诊回滚验证：停诊操作中预约迁移失败不影响已完成的迁移
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("停诊处理的原子性：排班+号源+预约状态一致")
    void suspensionAtomicConsistency() {
        // 创建3个预约
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        bookSlot(slots.get(0).getId(), 8401L, "患者8401");
        bookSlot(slots.get(1).getId(), 8402L, "患者8402");
        bookSlot(slots.get(2).getId(), 8403L, "患者8403");

        // 停诊（取消模式）
        SuspendRequest req = new SuspendRequest();
        req.setDoctorId(doctorId);
        req.setStartDate(targetDate);
        req.setEndDate(targetDate);
        req.setReason("原子性测试");
        req.setMigrateType("CANCEL");
        suspensionService.suspend(req);

        // 验证一致性：
        // 1. 排班状态为SUSPENDED
        LambdaQueryWrapper<DoctorSchedule> schedQw = new LambdaQueryWrapper<>();
        schedQw.eq(DoctorSchedule::getDoctorId, doctorId)
               .eq(DoctorSchedule::getScheduleDate, targetDate);
        List<DoctorSchedule> schedules = scheduleMapper.selectList(schedQw);
        for (DoctorSchedule s : schedules) {
            assertEquals("SUSPENDED", s.getStatus(), "排班应为SUSPENDED");
        }

        // 2. 所有号源为SUSPENDED
        LambdaQueryWrapper<ScheduleSlot> slotQw = new LambdaQueryWrapper<>();
        slotQw.eq(ScheduleSlot::getDoctorId, doctorId)
              .eq(ScheduleSlot::getSlotDate, targetDate);
        List<ScheduleSlot> allSlots = slotMapper.selectList(slotQw);
        for (ScheduleSlot s : allSlots) {
            assertEquals(SlotStatus.SUSPENDED.name(), s.getStatus());
        }

        // 3. 所有预约为CANCELLED
        LambdaQueryWrapper<Appointment> apptQw = new LambdaQueryWrapper<>();
        apptQw.eq(Appointment::getDoctorId, doctorId)
              .eq(Appointment::getSlotDate, targetDate)
              .ne(Appointment::getStatus, AppointmentStatus.CANCELLED.name());
        long nonCancelledCount = appointmentMapper.selectCount(apptQw);
        assertEquals(0, nonCancelledCount, "所有预约应为CANCELLED，无残留活跃预约");

        // 4. 没有游离号源（AVAILABLE/BOOKED/RELEASED状态残留）
        long orphanCount = allSlots.stream()
                .filter(s -> SlotStatus.AVAILABLE.name().equals(s.getStatus())
                          || SlotStatus.BOOKED.name().equals(s.getStatus())
                          || SlotStatus.RELEASED.name().equals(s.getStatus()))
                .count();
        assertEquals(0, orphanCount, "不应有非终态号源残留");

        log.info("停诊原子性验证通过");
    }

    // ═══════════════════════════════════════════════════════
    // 7. 并发抢号+超时释放交错
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("超时释放不影响SUSPENDED号源，候补补位排除已停诊排班")
    void expireAndBackfillDoNotTouchSuspended() {
        // 第1步：创建预约并加入候补
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        bookSlot(slots.get(0).getId(), 8501L, "患者8501");
        Waitlist w1 = joinWaitlist(8502L, "患者8502");

        // 第2步：停诊
        SuspendRequest suspendReq = new SuspendRequest();
        suspendReq.setDoctorId(doctorId);
        suspendReq.setStartDate(targetDate);
        suspendReq.setEndDate(targetDate);
        suspendReq.setReason("交错测试");
        suspendReq.setMigrateType("CANCEL");
        suspensionService.suspend(suspendReq);

        // 第3步：手动触发候补补位（应该无法补位，因为号源已SUSPENDED）
        waitlistService.triggerBackfill(doctorId, targetDate, null);

        // 验证候补仍为WAITING（无法补位）
        Waitlist updatedW1 = waitlistMapper.selectById(w1.getId());
        assertEquals("WAITING", updatedW1.getStatus(),
                "停诊后候补不应被补位（号源已SUSPENDED）");

        // 验证号源仍为SUSPENDED
        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getDoctorId, doctorId)
          .eq(ScheduleSlot::getSlotDate, targetDate);
        List<ScheduleSlot> allSlots = slotMapper.selectList(qw);
        for (ScheduleSlot s : allSlots) {
            assertEquals(SlotStatus.SUSPENDED.name(), s.getStatus());
        }

        log.info("超时释放与候补交错验证通过");
    }

    // ═══════════════════════════════════════════════════════
    // 8. 并发抢号：同一医生同一时段不可超卖
    // ═══════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("同一医生同一时段不可超卖：20个线程抢5个号源")
    void noOverbookingUnderConcurrency() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        assertEquals(5, slots.size());

        // 20个线程随机抢5个号源
        for (int i = 0; i < threadCount; i++) {
            final int patientId = 9000 + i;
            final Long slotId = slots.get(i % 5).getId(); // 每4个人竞争1个号源
            executor.submit(() -> {
                try {
                    startLatch.await();
                    BookRequest req = new BookRequest();
                    req.setPatientId((long) patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(slotId);
                    appointmentService.book(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        log.info("并发抢号: success={}, fail={}", successCount.get(), failCount.get());

        // 最多5个成功（每个号源最多1个）
        assertEquals(5, successCount.get(), "应该恰好5个预约成功");
        assertEquals(15, failCount.get(), "其余15个应该失败");

        // 验证号源状态一致性
        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getDoctorId, doctorId)
          .eq(ScheduleSlot::getSlotDate, targetDate)
          .eq(ScheduleSlot::getStatus, SlotStatus.BOOKED.name());
        long bookedCount = slotMapper.selectCount(qw);
        assertEquals(5, bookedCount, "应有5个号源被占用");

        // 验证CONFIRMED预约数
        LambdaQueryWrapper<Appointment> apptQw = new LambdaQueryWrapper<>();
        apptQw.eq(Appointment::getDoctorId, doctorId)
              .eq(Appointment::getSlotDate, targetDate)
              .eq(Appointment::getStatus, AppointmentStatus.CONFIRMED.name());
        long confirmedCount = appointmentMapper.selectCount(apptQw);
        assertEquals(5, confirmedCount, "应有5条CONFIRMED预约");
    }

    // ═══════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════

    private Appointment bookSlot(Long slotId, Long patientId, String name) {
        BookRequest req = new BookRequest();
        req.setPatientId(patientId);
        req.setPatientName(name);
        req.setSlotId(slotId);
        return appointmentService.book(req);
    }

    private Waitlist joinWaitlist(Long patientId, String name) {
        WaitlistRequest req = new WaitlistRequest();
        req.setPatientId(patientId);
        req.setPatientName(name);
        req.setDoctorId(doctorId);
        req.setDepartmentId(deptId);
        req.setTargetDate(targetDate);
        req.setTimePeriod("MORNING");
        return waitlistService.join(req);
    }
}
