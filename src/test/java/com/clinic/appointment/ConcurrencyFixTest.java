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
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 高并发场景下的事务边界与锁粒度回归测试
 *
 * 覆盖场景：
 * 1. 并发抢号：多患者同时抢同一号源，仅1人成功
 * 2. 取消+补位交错：取消触发候补补位，严格FIFO验证
 * 3. 并发取消同一预约：只有一个取消成功，号源不被双重释放
 * 4. 停诊+定时任务竞争：停诊后号源为SUSPENDED，定时任务不会重新释放为AVAILABLE
 * 5. 停诊期间号源状态无残留：所有号源均为SUSPENDED，无AVAILABLE残留
 * 6. 超时释放与候补交错：模拟超时释放号源后候补补位
 *
 * 运行前提：需要MySQL和Redis
 *   docker run -d --name clinic-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root123 -e MYSQL_DATABASE=clinic_test mysql:8
 *   docker run -d --name clinic-redis -p 6379:6379 redis:7
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConcurrencyFixTest {

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

        targetDate = LocalDate.now().plusDays(3);

        // 科室
        Department dept = new Department();
        dept.setName("测试科");
        dept.setCode("TEST_" + System.nanoTime());
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        // 医生
        Doctor doctor = new Doctor();
        doctor.setName("测试医生");
        doctor.setEmployeeNo("T" + System.nanoTime());
        doctor.setDepartmentId(deptId);
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();

        // 排班
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

        // 5个号源
        for (int i = 1; i <= 5; i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setScheduleId(schedule.getId());
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

    // ────────────────────────────────────────────
    // 场景1：并发抢号 + 取消竞争
    // ────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("并发抢号：同一号源10人同时抢，仅1人成功")
    void concurrentBookingOnlyOneSucceeds() throws Exception {
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Long targetSlotId = slots.get(0).getId();

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final long patientId = 10000L + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    BookRequest req = new BookRequest();
                    req.setPatientId(patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(targetSlotId);
                    appointmentService.book(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("患者{}抢号失败: {}", patientId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "应该只有1个预约成功");
        assertEquals(threads - 1, failCount.get(), "其余应该失败");

        // 号源状态校验
        ScheduleSlot slot = slotMapper.selectById(targetSlotId);
        assertEquals(SlotStatus.BOOKED.name(), slot.getStatus());
        assertNotNull(slot.getAppointmentId());
    }

    // ────────────────────────────────────────────
    // 场景2：并发取消同一预约
    // ────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("并发取消同一预约：仅1次取消成功，号源不被双重释放")
    void concurrentCancelSameAppointment() throws Exception {
        // 先预约
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(20001L);
        bookReq.setPatientName("患者20001");
        bookReq.setSlotId(slots.get(0).getId());
        Appointment appt = appointmentService.book(bookReq);

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CancelRequest cancelReq = new CancelRequest();
                    cancelReq.setAppointmentId(appt.getId());
                    cancelReq.setReason("并发取消测试");
                    appointmentService.cancel(cancelReq);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("并发取消失败: {}", e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "应该只有1次取消成功");
        assertEquals(threads - 1, failCount.get(), "其余取消应失败（状态已变更）");

        // 号源应该为AVAILABLE（释放了），不是双重释放
        ScheduleSlot slot = slotMapper.selectById(slots.get(0).getId());
        assertTrue(
            SlotStatus.AVAILABLE.name().equals(slot.getStatus()),
            "号源应为AVAILABLE（被释放一次）, 实际: " + slot.getStatus()
        );
        assertNull(slot.getAppointmentId(), "号源的appointmentId应被清空");
    }

    // ────────────────────────────────────────────
    // 场景3：取消+候补补位严格FIFO
    // ────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("取消触发候补补位：严格按FIFO顺序，第一个候补患者获得号源")
    void cancelTriggersBackfillStrictFIFO() throws Exception {
        // 约满前2个号源
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt1 = bookSlot(slots.get(0).getId(), 30001L, "患者30001");
        bookSlot(slots.get(1).getId(), 30002L, "患者30002");

        // 5个患者按顺序加入候补
        Waitlist w1 = joinWaitlist(30003L, "候补患者1");
        Waitlist w2 = joinWaitlist(30004L, "候补患者2");
        Waitlist w3 = joinWaitlist(30005L, "候补患者3");
        Waitlist w4 = joinWaitlist(30006L, "候补患者4");
        Waitlist w5 = joinWaitlist(30007L, "候补患者5");

        // 验证FIFO顺序
        assertTrue(w1.getId() < w2.getId(), "w1应在w2之前");
        assertTrue(w2.getId() < w3.getId(), "w2应在w3之前");

        // 取消第一个预约 → 释放1个号源 → 候补第1人应补位
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(appt1.getId());
        cancelReq.setReason("FIFO测试取消");
        appointmentService.cancel(cancelReq);

        // 等待afterCommit回调执行（异步）
        Thread.sleep(2000);

        // 验证：第一个候补患者获得号源
        Waitlist uw1 = waitlistMapper.selectById(w1.getId());
        Waitlist uw2 = waitlistMapper.selectById(w2.getId());
        Waitlist uw3 = waitlistMapper.selectById(w3.getId());

        assertEquals("FULFILLED", uw1.getStatus(), "候补第1人应补位成功");
        assertNotNull(uw1.getAppointmentId(), "应有新预约ID");

        // 验证新预约患者ID正确
        Appointment backfilled = appointmentMapper.selectById(uw1.getAppointmentId());
        assertNotNull(backfilled);
        assertEquals(30003L, backfilled.getPatientId(), "补位患者应为30003");

        assertEquals("WAITING", uw2.getStatus(), "候补第2人仍等待");
        assertEquals("WAITING", uw3.getStatus(), "候补第3人仍等待");
    }

    // ────────────────────────────────────────────
    // 场景4：停诊后号源不被定时任务重新释放
    // ────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("停诊后号源为SUSPENDED：定时任务不会重新释放为AVAILABLE")
    void suspensionPreventsScheduledTaskReRelease() throws Exception {
        // 创建3个预约
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        for (int i = 0; i < 3; i++) {
            bookSlot(slots.get(i).getId(), 40001L + i, "患者" + (40001 + i));
        }

        // 发布停诊
        SuspendRequest suspendReq = new SuspendRequest();
        suspendReq.setDoctorId(doctorId);
        suspendReq.setStartDate(targetDate);
        suspendReq.setEndDate(targetDate);
        suspendReq.setReason("测试停诊");
        suspendReq.setMigrateType("CANCEL");

        Map<String, Object> result = suspensionService.suspend(suspendReq);
        log.info("停诊结果: {}", result);

        // 验证所有号源为SUSPENDED（不是AVAILABLE）
        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getDoctorId, doctorId)
          .eq(ScheduleSlot::getSlotDate, targetDate);
        List<ScheduleSlot> allSlots = slotMapper.selectList(qw);

        for (ScheduleSlot slot : allSlots) {
            assertEquals(SlotStatus.SUSPENDED.name(), slot.getStatus(),
                    "停诊后所有号源应为SUSPENDED, slotNo=" + slot.getSlotNo() + " 实际: " + slot.getStatus());
        }

        // 执行定时补位补偿任务（模拟定时任务触发）
        scheduledTasks.waitlistBackfillCompensation();

        // 再次检查：号源仍为SUSPENDED（没有被定时任务改为AVAILABLE或被预约）
        List<ScheduleSlot> afterTask = slotMapper.selectList(qw);
        for (ScheduleSlot slot : afterTask) {
            assertEquals(SlotStatus.SUSPENDED.name(), slot.getStatus(),
                    "定时任务后号源仍应为SUSPENDED, slotNo=" + slot.getSlotNo());
        }

        // 验证预约全部取消
        List<Appointment> appointments = appointmentMapper.findByDoctorAndDate(doctorId, targetDate);
        for (Appointment a : appointments) {
            assertEquals(AppointmentStatus.CANCELLED.name(), a.getStatus());
        }
    }

    // ────────────────────────────────────────────
    // 场景5：停诊期间号源状态无残留
    // ────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("停诊迁移模式：原号源SUSPENDED，新预约正确创建")
    void suspensionMigrationNoResidue() throws Exception {
        // 创建替代医生
        Doctor altDoctor = new Doctor();
        altDoctor.setName("替代医生");
        altDoctor.setEmployeeNo("ALT" + System.nanoTime());
        altDoctor.setDepartmentId(deptId);
        altDoctor.setStatus(1);
        doctorMapper.insert(altDoctor);

        // 替代医生的排班和号源
        DoctorSchedule altSchedule = new DoctorSchedule();
        altSchedule.setDoctorId(altDoctor.getId());
        altSchedule.setDepartmentId(deptId);
        altSchedule.setScheduleDate(targetDate);
        altSchedule.setTimePeriod("MORNING");
        altSchedule.setTotalSlots(5);
        altSchedule.setBookedSlots(0);
        altSchedule.setExtraSlots(0);
        altSchedule.setStatus("NORMAL");
        scheduleMapper.insert(altSchedule);

        for (int i = 1; i <= 5; i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setScheduleId(altSchedule.getId());
            slot.setDoctorId(altDoctor.getId());
            slot.setDepartmentId(deptId);
            slot.setSlotDate(targetDate);
            slot.setSlotTime(LocalTime.of(9, 0).plusMinutes(i * 10L));
            slot.setSlotNo(i);
            slot.setStatus(SlotStatus.AVAILABLE.name());
            slot.setIsExtra(0);
            slot.setVersion(0);
            slotMapper.insert(slot);
        }

        // 给原医生创建2个预约
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        bookSlot(slots.get(0).getId(), 50001L, "患者50001");
        bookSlot(slots.get(1).getId(), 50002L, "患者50002");

        // 迁移模式停诊
        SuspendRequest suspendReq = new SuspendRequest();
        suspendReq.setDoctorId(doctorId);
        suspendReq.setStartDate(targetDate);
        suspendReq.setEndDate(targetDate);
        suspendReq.setReason("外出进修");
        suspendReq.setMigrateType("MIGRATE");

        Map<String, Object> result = suspensionService.suspend(suspendReq);
        log.info("停诊迁移结果: {}", result);

        // 验证原医生号源全部为SUSPENDED
        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getDoctorId, doctorId)
          .eq(ScheduleSlot::getSlotDate, targetDate);
        List<ScheduleSlot> originalSlots = slotMapper.selectList(qw);
        for (ScheduleSlot slot : originalSlots) {
            assertEquals(SlotStatus.SUSPENDED.name(), slot.getStatus(),
                    "原医生号源应全部为SUSPENDED");
        }

        // 验证替代医生有新预约
        List<Appointment> altAppts = appointmentMapper.findByDoctorAndDate(altDoctor.getId(), targetDate);
        assertEquals(2, altAppts.size(), "替代医生应有2个迁移预约");
        for (Appointment a : altAppts) {
            assertEquals(AppointmentStatus.CONFIRMED.name(), a.getStatus());
        }

        // 执行定时任务，确认不会干扰
        scheduledTasks.waitlistBackfillCompensation();

        // 再次检查原医生号源
        List<ScheduleSlot> afterTask = slotMapper.selectList(qw);
        for (ScheduleSlot slot : afterTask) {
            assertEquals(SlotStatus.SUSPENDED.name(), slot.getStatus(),
                    "定时任务后原医生号源仍应为SUSPENDED");
        }
    }

    // ────────────────────────────────────────────
    // 场景6：超时释放与候补交错
    // ────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("超时释放号源后候补补位：候补患者正确获得号源")
    void timeoutReleaseThenBackfill() throws Exception {
        // 约满2个号源
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt1 = bookSlot(slots.get(0).getId(), 60001L, "患者60001");
        bookSlot(slots.get(1).getId(), 60002L, "患者60002");

        // 加入候补
        Waitlist w1 = joinWaitlist(60003L, "候补患者A");
        Waitlist w2 = joinWaitlist(60004L, "候补患者B");

        // 模拟超时释放（手动释放号源 + 取消预约）
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(appt1.getId());
        cancelReq.setReason("超时自动取消");
        appointmentService.cancel(cancelReq);

        // 等待afterCommit
        Thread.sleep(2000);

        // 验证第一个候补患者获得号源
        Waitlist uw1 = waitlistMapper.selectById(w1.getId());
        assertEquals("FULFILLED", uw1.getStatus(), "超时释放后第一个候补应补位");
        assertNotNull(uw1.getAppointmentId());

        Appointment backfilled = appointmentMapper.selectById(uw1.getAppointmentId());
        assertEquals(60003L, backfilled.getPatientId());

        // 第二个仍在等待（只释放了1个号源）
        Waitlist uw2 = waitlistMapper.selectById(w2.getId());
        assertEquals("WAITING", uw2.getStatus());
    }

    // ────────────────────────────────────────────
    // 场景7：并发取消+抢号+候补交错执行
    // ────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("取消与外部抢号并发：候补补位不受干扰")
    void cancelAndExternalBookingConcurrent() throws Exception {
        // 约满
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt1 = bookSlot(slots.get(0).getId(), 70001L, "患者70001");

        // 加入候补
        Waitlist w1 = joinWaitlist(70003L, "候补患者X");

        // 同时执行：取消appt1 + 外部抢号（抢释放后的号源）
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger cancelSuccess = new AtomicInteger(0);
        AtomicInteger bookSuccess = new AtomicInteger(0);
        AtomicReference<Long> bookedPatientId = new AtomicReference<>();

        // 1个线程取消
        executor.submit(() -> {
            try {
                startLatch.await();
                CancelRequest req = new CancelRequest();
                req.setAppointmentId(appt1.getId());
                req.setReason("竞争取消");
                appointmentService.cancel(req);
                cancelSuccess.incrementAndGet();
            } catch (Exception e) {
                log.debug("取消失败: {}", e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        // 5个线程尝试抢号（同一slotId）
        for (int i = 0; i < 5; i++) {
            final long patientId = 70010L + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    BookRequest req = new BookRequest();
                    req.setPatientId(patientId);
                    req.setPatientName("抢号患者" + patientId);
                    req.setSlotId(slots.get(0).getId());
                    Appointment a = appointmentService.book(req);
                    bookSuccess.incrementAndGet();
                    bookedPatientId.set(patientId);
                } catch (Exception e) {
                    log.debug("抢号失败: {}", e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 等待afterCommit回调
        Thread.sleep(2000);

        assertEquals(1, cancelSuccess.get(), "取消应成功1次");

        // 号源最终应被占用（要么外部抢号成功，要么候补补位成功）
        ScheduleSlot slot = slotMapper.selectById(slots.get(0).getId());
        assertEquals(SlotStatus.BOOKED.name(), slot.getStatus(),
                "号源应最终被占用");
        assertNotNull(slot.getAppointmentId());

        // 总的成功预约数应为1（该号源只能被1个人占用）
        // 可能是外部抢号者或候补补位者
        log.info("最终占用号源的患者: appointmentId={}, 外部抢号成功数={}, bookedPatient={}",
                slot.getAppointmentId(), bookSuccess.get(), bookedPatientId.get());
    }

    // ────────────────────────────────────────────
    // 场景8：停诊并发安全（停诊与预约同时进行）
    // ────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("停诊与外部预约并发：停诊后号源不可被新预约")
    void suspendConcurrentWithBooking() throws Exception {
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);

        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger bookSuccess = new AtomicInteger(0);
        AtomicInteger bookFail = new AtomicInteger(0);
        AtomicReference<Exception> suspendError = new AtomicReference<>();

        // 1个线程执行停诊
        executor.submit(() -> {
            try {
                startLatch.await();
                SuspendRequest req = new SuspendRequest();
                req.setDoctorId(doctorId);
                req.setStartDate(targetDate);
                req.setEndDate(targetDate);
                req.setReason("并发停诊测试");
                req.setMigrateType("CANCEL");
                suspensionService.suspend(req);
            } catch (Exception e) {
                suspendError.set(e);
                log.error("停诊失败: {}", e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        // 5个线程尝试预约
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    BookRequest req = new BookRequest();
                    req.setPatientId(80001L + idx);
                    req.setPatientName("并发患者" + (80001 + idx));
                    req.setSlotId(slots.get(idx).getId());
                    appointmentService.book(req);
                    bookSuccess.incrementAndGet();
                } catch (Exception e) {
                    bookFail.incrementAndGet();
                    log.debug("并发预约失败: {}", e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertNull(suspendError.get(), "停诊不应失败");

        // 停诊后，所有号源应为SUSPENDED
        LambdaQueryWrapper<ScheduleSlot> qw = new LambdaQueryWrapper<>();
        qw.eq(ScheduleSlot::getDoctorId, doctorId)
          .eq(ScheduleSlot::getSlotDate, targetDate);
        List<ScheduleSlot> allSlots = slotMapper.selectList(qw);

        long suspendedCount = allSlots.stream()
                .filter(s -> SlotStatus.SUSPENDED.name().equals(s.getStatus()))
                .count();

        // 可能有部分号源在停诊前被成功预约（BOOKED），但batchSuspend会将它们也标记为SUSPENDED
        // 所以最终所有号源都应该是SUSPENDED
        assertEquals(allSlots.size(), suspendedCount,
                "停诊后所有号源应为SUSPENDED");

        // 在停诊之后尝试预约应该失败
        assertThrows(Exception.class, () -> {
            BookRequest req = new BookRequest();
            req.setPatientId(89999L);
            req.setPatientName("停诊后患者");
            req.setSlotId(slots.get(0).getId());
            appointmentService.book(req);
        }, "停诊后不应能预约");
    }

    // ────────────────────────────────────────────
    // 场景9：多次取消依次补位（FIFO严格验证）
    // ────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("多次取消依次触发候补补位：严格按进入顺序")
    void sequentialCancelBackfillFIFO() throws Exception {
        // 约满3个号源
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment a1 = bookSlot(slots.get(0).getId(), 90001L, "患者90001");
        Appointment a2 = bookSlot(slots.get(1).getId(), 90002L, "患者90002");
        Appointment a3 = bookSlot(slots.get(2).getId(), 90003L, "患者90003");

        // 3个候补患者按顺序加入
        Waitlist w1 = joinWaitlist(90004L, "候补A");
        Waitlist w2 = joinWaitlist(90005L, "候补B");
        Waitlist w3 = joinWaitlist(90006L, "候补C");

        // 取消第一个预约 → w1补位
        appointmentService.cancel(new CancelRequest() {{
            setAppointmentId(a1.getId());
            setReason("取消1");
        }});
        Thread.sleep(2000); // 等待afterCommit

        assertEquals("FULFILLED", waitlistMapper.selectById(w1.getId()).getStatus());
        assertEquals("WAITING", waitlistMapper.selectById(w2.getId()).getStatus());
        assertEquals("WAITING", waitlistMapper.selectById(w3.getId()).getStatus());

        // 取消第二个 → w2补位
        appointmentService.cancel(new CancelRequest() {{
            setAppointmentId(a2.getId());
            setReason("取消2");
        }});
        Thread.sleep(2000);

        assertEquals("FULFILLED", waitlistMapper.selectById(w1.getId()).getStatus());
        assertEquals("FULFILLED", waitlistMapper.selectById(w2.getId()).getStatus());
        assertEquals("WAITING", waitlistMapper.selectById(w3.getId()).getStatus());

        // 取消第三个 → w3补位
        appointmentService.cancel(new CancelRequest() {{
            setAppointmentId(a3.getId());
            setReason("取消3");
        }});
        Thread.sleep(2000);

        assertEquals("FULFILLED", waitlistMapper.selectById(w1.getId()).getStatus());
        assertEquals("FULFILLED", waitlistMapper.selectById(w2.getId()).getStatus());
        assertEquals("FULFILLED", waitlistMapper.selectById(w3.getId()).getStatus());

        // 验证补位患者ID对应正确
        Appointment ba = appointmentMapper.selectById(waitlistMapper.selectById(w1.getId()).getAppointmentId());
        Appointment bb = appointmentMapper.selectById(waitlistMapper.selectById(w2.getId()).getAppointmentId());
        Appointment bc = appointmentMapper.selectById(waitlistMapper.selectById(w3.getId()).getAppointmentId());
        assertEquals(90004L, ba.getPatientId(), "w1补位应为候补A");
        assertEquals(90005L, bb.getPatientId(), "w2补位应为候补B");
        assertEquals(90006L, bc.getPatientId(), "w3补位应为候补C");
    }

    // ────────────────────────────────────────────
    // 场景10：停诊取消模式下的候补不应被误补位
    // ────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("停诊取消模式：候补患者不会被补位到已停诊的号源")
    void suspensionCancelModeNoBackfill() throws Exception {
        // 约满
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        bookSlot(slots.get(0).getId(), 100001L, "患者100001");
        bookSlot(slots.get(1).getId(), 100002L, "患者100002");

        // 加入候补
        Waitlist w1 = joinWaitlist(100003L, "候补停诊测试");

        // 停诊
        SuspendRequest req = new SuspendRequest();
        req.setDoctorId(doctorId);
        req.setStartDate(targetDate);
        req.setEndDate(targetDate);
        req.setReason("停诊测试");
        req.setMigrateType("CANCEL");
        suspensionService.suspend(req);

        // 候补患者状态应仍为WAITING（号源已SUSPENDED，不可补位）
        Waitlist uw1 = waitlistMapper.selectById(w1.getId());
        assertEquals("WAITING", uw1.getStatus(),
                "停诊后候补患者应保持WAITING（号源已SUSPENDED不可预约）");

        // 定时任务运行后，候补患者仍为WAITING
        scheduledTasks.waitlistBackfillCompensation();
        uw1 = waitlistMapper.selectById(w1.getId());
        assertEquals("WAITING", uw1.getStatus(),
                "定时任务后候补患者仍应保持WAITING");
    }

    // ── Helper Methods ────────────────────────────

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
