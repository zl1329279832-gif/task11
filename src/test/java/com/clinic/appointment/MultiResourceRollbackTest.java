package com.clinic.appointment;

import com.clinic.appointment.domain.dto.*;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.ResourceStatus;
import com.clinic.appointment.domain.enums.ResourceType;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.MultiResourceBookingService;
import com.clinic.appointment.service.ResourceAvailabilityService;
import com.clinic.appointment.service.WaitlistService;
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
 * 多资源联合预约回滚与幂等测试
 * - 并发预约资源无残留
 * - 设备停用中候补补位被拒绝
 * - 改约失败原预约完整回滚
 * - 重复取消幂等
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MultiResourceRollbackTest {

    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DoctorScheduleMapper scheduleMapper;
    @Autowired private ScheduleSlotMapper slotMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private AppointmentResourceMapper appointmentResourceMapper;
    @Autowired private ExamRoomMapper examRoomMapper;
    @Autowired private EquipmentMapper equipmentMapper;
    @Autowired private NursingStaffMapper nursingStaffMapper;
    @Autowired private ResourceAvailabilityMapper resourceAvailabilityMapper;
    @Autowired private WaitlistMapper waitlistMapper;
    @Autowired private MultiResourceBookingService multiResourceBookingService;
    @Autowired private ResourceAvailabilityService resourceAvailabilityService;
    @Autowired private WaitlistService waitlistService;

    private Long testDeptId;
    private Long testDoctorId;
    private Long testScheduleId;
    private LocalDate tomorrow;

    @BeforeEach
    void setupTestData() {
        waitlistMapper.delete(null);
        appointmentResourceMapper.delete(null);
        appointmentMapper.delete(null);
        resourceAvailabilityMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        equipmentMapper.delete(null);
        examRoomMapper.delete(null);
        nursingStaffMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);

        tomorrow = LocalDate.now().plusDays(1);

        Department dept = new Department();
        dept.setName("影像科-回滚测试");
        dept.setCode("IMG-ROLLBACK");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        testDeptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("回滚医生");
        doctor.setEmployeeNo("D-ROLLBACK");
        doctor.setDepartmentId(testDeptId);
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        testDoctorId = doctor.getId();

        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctorId(testDoctorId);
        schedule.setDepartmentId(testDeptId);
        schedule.setScheduleDate(tomorrow);
        schedule.setTimePeriod("MORNING");
        schedule.setTotalSlots(10);
        schedule.setBookedSlots(0);
        schedule.setExtraSlots(0);
        schedule.setStatus("NORMAL");
        scheduleMapper.insert(schedule);
        testScheduleId = schedule.getId();

        ExamRoom room = new ExamRoom();
        room.setName("回滚检查室");
        room.setCode("R-ROLLBACK");
        room.setDepartmentId(testDeptId);
        room.setStatus("ACTIVE");
        examRoomMapper.insert(room);

        Equipment equip = new Equipment();
        equip.setName("回滚CT");
        equip.setCode("E-ROLLBACK");
        equip.setEquipmentType("CT");
        equip.setDepartmentId(testDeptId);
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);

        NursingStaff nurse = new NursingStaff();
        nurse.setName("回滚护士");
        nurse.setEmployeeNo("N-ROLLBACK");
        nurse.setDepartmentId(testDeptId);
        nurse.setStatus(1);
        nursingStaffMapper.insert(nurse);

        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");
    }

    // ────────────────────────────────────────────
    // Test 1: 并发联合预约同一slot，失败方无资源残留
    // ────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("并发联合预约：仅1人成功，失败方无AppointmentResource和资源窗口残留")
    void concurrentJointBookingNoResourceLeak() throws Exception {
        ScheduleSlot slot = createSlot(1, LocalTime.of(9, 0));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long patientId = 20000L + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    JointBookRequest req = new JointBookRequest();
                    req.setPatientId(patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(slot.getId());
                    req.setExamType("CT");
                    multiResourceBookingService.jointBook(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("患者{}并发联合预约失败: {}", patientId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "测试超时");
        executor.shutdown();

        // 验证：仅1成功
        assertEquals(1, successCount.get(), "应只有1个联合预约成功");
        assertEquals(threadCount - 1, failCount.get(), "其余应全部失败");

        // 验证：数据库中只有1条appointment
        List<Appointment> allAppointments = appointmentMapper.findByDoctorAndDate(testDoctorId, tomorrow);
        assertEquals(1, allAppointments.size(), "数据库应只有1条预约记录");

        // 验证：只有3条AppointmentResource（诊室+设备+护理），无残留
        List<AppointmentResource> allResources = appointmentResourceMapper
                .findByAppointment(allAppointments.get(0).getId());
        assertEquals(3, allResources.size(), "成功预约应有3条资源关联");

        // 验证：9:00时段的资源窗口中，只有3个BOOKED（room/equip/nurse各1个），其余仍AVAILABLE
        long bookedWindowCount = countBookedWindows(tomorrow);
        assertEquals(3, bookedWindowCount, "应只有3个资源窗口被占用，无残留");

        // 验证：号源状态正确
        ScheduleSlot freshSlot = slotMapper.selectById(slot.getId());
        assertEquals(SlotStatus.BOOKED.name(), freshSlot.getStatus());
    }

    // ────────────────────────────────────────────
    // Test 2: 设备停用后候补补位不命中已停用设备
    // ────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("设备停用后取消预约：候补补位不命中已停用设备的窗口")
    void backfillRejectsDeactivatedEquipmentWindows() {
        ScheduleSlot slot = createSlot(1, LocalTime.of(9, 0));

        // 1. 创建联合预约
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(30001L);
        bookReq.setPatientName("患者A");
        bookReq.setSlotId(slot.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        assertNotNull(result.getAppointment());

        // 2. 查找使用的设备
        List<AppointmentResource> resources = appointmentResourceMapper
                .findByAppointment(result.getAppointment().getId());
        AppointmentResource equipResource = resources.stream()
                .filter(r -> ResourceType.EQUIPMENT.name().equals(r.getResourceType()))
                .findFirst().orElseThrow();
        Long equipmentId = equipResource.getResourceId();

        // 3. 停用设备（会取消关联预约并封锁窗口）
        EquipmentDeactivateRequest deactivateReq = new EquipmentDeactivateRequest();
        deactivateReq.setEquipmentId(equipmentId);
        deactivateReq.setEffectiveDate(tomorrow);
        deactivateReq.setReason("维修");
        resourceAvailabilityService.deactivateEquipment(deactivateReq);

        // 4. 验证预约已被取消
        Appointment cancelled = appointmentMapper.selectById(result.getAppointment().getId());
        assertEquals(AppointmentStatus.CANCELLED.name(), cancelled.getStatus());

        // 5. 验证设备窗口不是AVAILABLE（应是BLOCKED）
        List<ResourceAvailability> equipWindows = resourceAvailabilityMapper.findAvailableWindow(
                ResourceType.EQUIPMENT.name(), equipmentId, tomorrow, LocalTime.of(9, 0));
        assertTrue(equipWindows.isEmpty(), "停用设备的窗口不应为AVAILABLE（应已被BLOCKED）");

        // 6. 加入EXAM类型候补
        WaitlistRequest wlReq = new WaitlistRequest();
        wlReq.setPatientId(30002L);
        wlReq.setPatientName("患者B");
        wlReq.setDoctorId(testDoctorId);
        wlReq.setDepartmentId(testDeptId);
        wlReq.setTargetDate(tomorrow);
        wlReq.setTimePeriod("MORNING");
        wlReq.setAppointmentType("EXAM");
        wlReq.setExamType("CT");
        Waitlist waitlist = waitlistService.join(wlReq);

        // 7. 号源已释放（因停用取消了预约），手动触发补位
        //    补位会因设备不可用而失败，内部事务可能标记为rollback-only
        try {
            waitlistService.triggerBackfill(testDoctorId, tomorrow, LocalTime.of(9, 0));
        } catch (Exception e) {
            log.info("补位事务回滚（设备已停用，预期行为）: {}", e.getMessage());
        }

        // 8. 验证候补仍为WAITING（因为唯一的CT设备已停用，补位应失败）
        Waitlist updatedWl = waitlistMapper.selectById(waitlist.getId());
        assertEquals("WAITING", updatedWl.getStatus(),
                "设备已停用，候补补位应失败，状态仍为WAITING");
    }

    // ────────────────────────────────────────────
    // Test 3: 改约失败时原预约完整回滚
    // ────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("改约失败回滚：原预约状态、号源、资源窗口、关联记录全部不变")
    void rescheduleFailureFullRollback() {
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(10, 0));

        // 1. 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(40001L);
        bookReq.setPatientName("患者C");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        Long originalApptId = result.getAppointment().getId();

        // 记录原始资源状态
        List<AppointmentResource> originalResources = appointmentResourceMapper.findByAppointment(originalApptId);
        assertEquals(3, originalResources.size());

        // 2. 封锁slot2时间对应的设备窗口（模拟设备被占用）
        List<ResourceAvailability> equipWindows = resourceAvailabilityMapper.findAvailableEquipmentInDept(
                testDeptId, "CT", tomorrow, LocalTime.of(10, 0));
        for (ResourceAvailability w : equipWindows) {
            w.setStatus(ResourceStatus.BLOCKED.name());
            resourceAvailabilityMapper.updateById(w);
        }

        // 3. 改约应失败（新时间的设备不可用）
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(originalApptId);
        reschReq.setNewSlotId(slot2.getId());
        reschReq.setReason("测试改约");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> multiResourceBookingService.jointReschedule(reschReq));
        assertEquals("RESOURCE_UNAVAILABLE", ex.getCode());

        // 4. 验证原预约状态不变
        Appointment original = appointmentMapper.selectById(originalApptId);
        assertEquals(AppointmentStatus.CONFIRMED.name(), original.getStatus(),
                "改约失败后原预约应仍为CONFIRMED");
        assertEquals(slot1.getId(), original.getSlotId(),
                "改约失败后原预约slotId应不变");

        // 5. 验证原号源仍为BOOKED
        ScheduleSlot freshSlot1 = slotMapper.selectById(slot1.getId());
        assertEquals(SlotStatus.BOOKED.name(), freshSlot1.getStatus(),
                "改约失败后原号源应仍为BOOKED");

        // 6. 验证新号源仍为AVAILABLE
        ScheduleSlot freshSlot2 = slotMapper.selectById(slot2.getId());
        assertEquals(SlotStatus.AVAILABLE.name(), freshSlot2.getStatus(),
                "改约失败后新号源应仍为AVAILABLE");

        // 7. 验证原资源关联记录完好
        List<AppointmentResource> afterResources = appointmentResourceMapper.findByAppointment(originalApptId);
        assertEquals(3, afterResources.size(),
                "改约失败后应仍有3条原始资源关联记录");

        // 8. 验证原资源窗口仍为BOOKED
        for (AppointmentResource ar : afterResources) {
            ResourceAvailability avail = resourceAvailabilityMapper.selectById(ar.getAvailabilityId());
            assertEquals(ResourceStatus.BOOKED.name(), avail.getStatus(),
                    "改约失败后原资源窗口应仍为BOOKED: type=" + ar.getResourceType());
        }
    }

    // ────────────────────────────────────────────
    // Test 4: 重复取消幂等
    // ────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("重复取消幂等：第二次取消不抛异常，返回已取消的预约")
    void duplicateCancelIsIdempotent() {
        ScheduleSlot slot = createSlot(1, LocalTime.of(9, 0));

        // 1. 创建联合预约
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(50001L);
        bookReq.setPatientName("患者D");
        bookReq.setSlotId(slot.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        Long apptId = result.getAppointment().getId();

        // 2. 第一次取消：应成功
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(apptId);
        cancelReq.setReason("测试取消");
        Appointment cancelled = multiResourceBookingService.jointCancel(cancelReq);
        assertEquals(AppointmentStatus.CANCELLED.name(), cancelled.getStatus());

        // 3. 验证号源已释放
        ScheduleSlot freshSlot = slotMapper.selectById(slot.getId());
        assertEquals(SlotStatus.AVAILABLE.name(), freshSlot.getStatus(),
                "取消后号源应释放为AVAILABLE");

        // 4. 验证资源关联已删除
        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(apptId);
        assertTrue(resources.isEmpty(), "取消后资源关联应已删除");

        // 5. 第二次取消：应幂等返回，不抛异常
        CancelRequest cancelReq2 = new CancelRequest();
        cancelReq2.setAppointmentId(apptId);
        cancelReq2.setReason("重复取消");
        Appointment idempotentResult = assertDoesNotThrow(
                () -> multiResourceBookingService.jointCancel(cancelReq2),
                "重复取消不应抛异常");
        assertEquals(AppointmentStatus.CANCELLED.name(), idempotentResult.getStatus(),
                "幂等返回的预约状态应为CANCELLED");

        // 6. 验证数据库状态一致（不会多次释放号源或资源）
        ScheduleSlot slotAfterDouble = slotMapper.selectById(slot.getId());
        assertEquals(SlotStatus.AVAILABLE.name(), slotAfterDouble.getStatus(),
                "重复取消后号源应仍为AVAILABLE");
    }

    // ────────────────────────────────────────────
    // 辅助方法
    // ────────────────────────────────────────────

    private ScheduleSlot createSlot(int no, LocalTime time) {
        ScheduleSlot slot = new ScheduleSlot();
        slot.setScheduleId(testScheduleId);
        slot.setDoctorId(testDoctorId);
        slot.setDepartmentId(testDeptId);
        slot.setSlotDate(tomorrow);
        slot.setSlotTime(time);
        slot.setSlotNo(no);
        slot.setStatus(SlotStatus.AVAILABLE.name());
        slot.setIsExtra(0);
        slot.setVersion(0);
        slotMapper.insert(slot);
        return slot;
    }

    private long countBookedWindows(LocalDate date) {
        return resourceAvailabilityMapper.selectList(null).stream()
                .filter(ra -> date.equals(ra.getAvailDate()))
                .filter(ra -> ResourceStatus.BOOKED.name().equals(ra.getStatus()))
                .count();
    }
}
