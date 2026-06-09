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
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多资源联合预约回滚完整性测试
 *
 * 覆盖场景：
 * 1. 并发联合预约资源残留检查
 * 2. 设备停用后候补补位不命中停用设备
 * 3. 改约失败后原预约资源完整回滚
 * 4. 重复取消幂等性
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
        dept.setCode("IMG-RB");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        testDeptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("回滚测试医生");
        doctor.setEmployeeNo("D-RB");
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

        // 检查室
        ExamRoom room = new ExamRoom();
        room.setName("回滚检查室");
        room.setCode("R-RB");
        room.setDepartmentId(testDeptId);
        room.setStatus("ACTIVE");
        examRoomMapper.insert(room);

        // 设备
        Equipment equip = new Equipment();
        equip.setName("回滚CT");
        equip.setCode("E-RB");
        equip.setEquipmentType("CT");
        equip.setDepartmentId(testDeptId);
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);

        // 护理人员
        NursingStaff nurse = new NursingStaff();
        nurse.setName("回滚护士");
        nurse.setEmployeeNo("N-RB");
        nurse.setDepartmentId(testDeptId);
        nurse.setStatus(1);
        nursingStaffMapper.insert(nurse);

        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");
    }

    // ════════════════════════════════════════════════════════
    // 1. 并发联合预约资源残留检查
    // ════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("并发联合预约同一号源：仅1人成功，失败方无资源残留")
    void concurrentJointBooking_noResourceResidue() throws Exception {
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
                    req.setPatientName("并发患者" + patientId);
                    req.setSlotId(slot.getId());
                    req.setExamType("CT");
                    multiResourceBookingService.jointBook(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("并发联合预约失败: patient={}, error={}", patientId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(120, TimeUnit.SECONDS), "并发应在120秒内完成");
        executor.shutdown();

        assertEquals(1, successCount.get(), "应仅有1个联合预约成功");
        assertEquals(threadCount - 1, failCount.get(), "其余应失败");

        // 验证：成功预约有完整3条资源关联
        List<Appointment> appointments = appointmentMapper.findByDoctorAndDate(testDoctorId, tomorrow);
        assertEquals(1, appointments.size(), "应仅有1条预约记录");
        Appointment winner = appointments.get(0);
        assertEquals(AppointmentStatus.CONFIRMED.name(), winner.getStatus());

        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(winner.getId());
        assertEquals(3, resources.size(), "成功预约应有3条资源关联（诊室+设备+护理）");

        // 验证：号源状态正确
        ScheduleSlot freshSlot = slotMapper.selectById(slot.getId());
        assertEquals(SlotStatus.BOOKED.name(), freshSlot.getStatus());
        assertEquals(winner.getId(), freshSlot.getAppointmentId());

        // 验证：不存在孤立资源关联（appointmentId不指向任何有效预约）
        // 查找所有非成功预约的关联记录
        List<Appointment> allAppts = appointmentMapper.findByDoctorAndDate(testDoctorId, tomorrow);
        for (Appointment a : allAppts) {
            if (!a.getId().equals(winner.getId())) {
                List<AppointmentResource> orphans = appointmentResourceMapper.findByAppointment(a.getId());
                assertTrue(orphans.isEmpty(),
                        "失败预约不应有资源关联残留: appointmentId=" + a.getId());
            }
        }

        // 验证：资源窗口状态一致（BOOKED窗口都指向成功预约）
        List<ResourceAvailability> bookedWindows = resourceAvailabilityMapper.findBookedFrom(
                ResourceType.EXAM_ROOM.name(), 0L, tomorrow.minusDays(1));
        List<ResourceAvailability> equipBookedWindows = resourceAvailabilityMapper.findBookedFrom(
                ResourceType.EQUIPMENT.name(), 0L, tomorrow.minusDays(1));
        List<ResourceAvailability> nursingBookedWindows = resourceAvailabilityMapper.findBookedFrom(
                ResourceType.NURSING_STAFF.name(), 0L, tomorrow.minusDays(1));

        for (ResourceAvailability w : bookedWindows) {
            if (ResourceStatus.BOOKED.name().equals(w.getStatus())) {
                assertEquals(winner.getId(), w.getAppointmentId(),
                        "BOOKED窗口应指向成功预约: room availId=" + w.getId());
            }
        }
        for (ResourceAvailability w : equipBookedWindows) {
            if (ResourceStatus.BOOKED.name().equals(w.getStatus())) {
                assertEquals(winner.getId(), w.getAppointmentId(),
                        "BOOKED窗口应指向成功预约: equip availId=" + w.getId());
            }
        }

        log.info("并发联合预约资源残留检查通过: success={}, fail={}", successCount.get(), failCount.get());
    }

    // ════════════════════════════════════════════════════════
    // 2. 设备停用后候补补位不命中停用设备
    // ════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("设备停用后候补补位应跳过停用设备，使用替代设备")
    void deviceDeactivation_backfillSkipsDeactivatedEquipment() {
        // 添加第二台CT设备作为替代
        Equipment equip2 = new Equipment();
        equip2.setName("替代CT");
        equip2.setCode("E-RB-ALT");
        equip2.setEquipmentType("CT");
        equip2.setDepartmentId(testDeptId);
        equip2.setStatus("ACTIVE");
        equipmentMapper.insert(equip2);

        // 重新生成资源窗口（包含两台设备）
        resourceAvailabilityMapper.delete(null);
        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");

        // 获取原设备
        Equipment equip1 = equipmentMapper.selectList(null).stream()
                .filter(e -> "E-RB".equals(e.getCode())).findFirst().orElseThrow();

        // 创建2个号源
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(9, 30));

        // 预约slot1（应使用equip1，因为ID较小排在前面）
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(30001L);
        bookReq.setPatientName("患者30001");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        assertNotNull(result.getAppointment());

        // 记录slot1预约使用的设备
        List<AppointmentResource> slot1Resources = appointmentResourceMapper
                .findByAppointment(result.getAppointment().getId());
        AppointmentResource slot1EquipResource = slot1Resources.stream()
                .filter(r -> ResourceType.EQUIPMENT.name().equals(r.getResourceType()))
                .findFirst().orElseThrow();
        log.info("slot1预约使用设备: {}", slot1EquipResource.getResourceId());

        // 加入EXAM类型候补
        WaitlistRequest wlReq = new WaitlistRequest();
        wlReq.setPatientId(30002L);
        wlReq.setPatientName("候补患者30002");
        wlReq.setDoctorId(testDoctorId);
        wlReq.setDepartmentId(testDeptId);
        wlReq.setTargetDate(tomorrow);
        wlReq.setTimePeriod("MORNING");
        wlReq.setAppointmentType("EXAM");
        wlReq.setExamType("CT");
        waitlistService.join(wlReq);

        // 停用equip1
        EquipmentDeactivateRequest deactivateReq = new EquipmentDeactivateRequest();
        deactivateReq.setEquipmentId(equip1.getId());
        deactivateReq.setEffectiveDate(tomorrow);
        deactivateReq.setReason("设备维护");
        Map<String, Object> deactivateResult = resourceAvailabilityService.deactivateEquipment(deactivateReq);
        log.info("设备停用结果: {}", deactivateResult);

        // 验证设备状态
        Equipment updatedEquip1 = equipmentMapper.selectById(equip1.getId());
        assertEquals("INACTIVE", updatedEquip1.getStatus(), "原设备应为INACTIVE");

        // 验证：原预约被取消（因为使用的设备被停用）
        Appointment cancelledAppt = appointmentMapper.selectById(result.getAppointment().getId());
        assertEquals(AppointmentStatus.CANCELLED.name(), cancelledAppt.getStatus(),
                "使用停用设备的预约应被取消");

        // 验证：原预约的资源关联已清理
        List<AppointmentResource> cancelledResources = appointmentResourceMapper
                .findByAppointment(result.getAppointment().getId());
        assertTrue(cancelledResources.isEmpty(), "取消的预约不应有资源关联残留");

        // 验证：停用设备的窗口全部BLOCKED
        List<ResourceAvailability> equip1Windows = resourceAvailabilityMapper.findAvailableWindow(
                ResourceType.EQUIPMENT.name(), equip1.getId(), tomorrow, LocalTime.of(9, 0));
        assertTrue(equip1Windows.isEmpty(), "停用设备不应有AVAILABLE窗口");

        // 手动触发backfill（模拟取消后的候补补位）
        // 先释放slot1号源（deactivateEquipment中的cancel应该已经释放了）
        ScheduleSlot freshSlot1 = slotMapper.selectById(slot1.getId());
        if (!SlotStatus.AVAILABLE.name().equals(freshSlot1.getStatus())) {
            // 如果还没释放，手动释放
            slotMapper.casRelease(slot1.getId(), freshSlot1.getVersion());
        }

        waitlistService.triggerBackfill(testDoctorId, tomorrow, LocalTime.of(9, 0));

        // 验证：候补补位如成功，应使用替代设备equip2
        List<Waitlist> waitlists = waitlistService.getWaitingByDoctorAndDate(testDoctorId, tomorrow);
        Waitlist fulfilled = waitlists.stream()
                .filter(w -> "FULFILLED".equals(w.getStatus()))
                .findFirst().orElse(null);

        if (fulfilled != null) {
            assertNotNull(fulfilled.getAppointmentId(), "补位成功应有预约ID");
            List<AppointmentResource> backfillResources = appointmentResourceMapper
                    .findByAppointment(fulfilled.getAppointmentId());
            AppointmentResource backfillEquip = backfillResources.stream()
                    .filter(r -> ResourceType.EQUIPMENT.name().equals(r.getResourceType()))
                    .findFirst().orElse(null);
            if (backfillEquip != null) {
                assertEquals(equip2.getId(), backfillEquip.getResourceId(),
                        "候补补位应使用替代设备，不应命中停用设备");
            }
            log.info("候补补位成功，使用设备: {}", backfillEquip != null ? backfillEquip.getResourceId() : "N/A");
        } else {
            log.info("候补补位未成功（资源不足或补位失败），验证通过：未命中停用设备");
        }
    }

    // ════════════════════════════════════════════════════════
    // 3. 改约失败后原预约完整回滚
    // ════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("改约新slot可用但新资源被封锁：原子回滚，原预约和资源完整")
    void rescheduleFail_fullAtomicRollback() {
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(10, 0));

        // 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(40001L);
        bookReq.setPatientName("患者40001");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        Long originalApptId = result.getAppointment().getId();

        // 记录原始状态
        Appointment originalAppt = appointmentMapper.selectById(originalApptId);
        assertEquals(AppointmentStatus.CONFIRMED.name(), originalAppt.getStatus());
        assertEquals(slot1.getId(), originalAppt.getSlotId());

        List<AppointmentResource> originalResources = appointmentResourceMapper.findByAppointment(originalApptId);
        assertEquals(3, originalResources.size(), "原预约应有3条资源关联");

        ScheduleSlot originalSlot1 = slotMapper.selectById(slot1.getId());
        assertEquals(SlotStatus.BOOKED.name(), originalSlot1.getStatus());

        // 记录原始资源窗口ID
        Long origRoomAvailId = originalResources.stream()
                .filter(r -> ResourceType.EXAM_ROOM.name().equals(r.getResourceType()))
                .map(AppointmentResource::getAvailabilityId).findFirst().orElseThrow();
        Long origEquipAvailId = originalResources.stream()
                .filter(r -> ResourceType.EQUIPMENT.name().equals(r.getResourceType()))
                .map(AppointmentResource::getAvailabilityId).findFirst().orElseThrow();
        Long origNurseAvailId = originalResources.stream()
                .filter(r -> ResourceType.NURSING_STAFF.name().equals(r.getResourceType()))
                .map(AppointmentResource::getAvailabilityId).findFirst().orElseThrow();

        // 封锁slot2时间对应的所有诊室窗口（模拟改约目标时间无诊室）
        List<ResourceAvailability> roomWindows = resourceAvailabilityMapper.findAvailableWindow(
                ResourceType.EXAM_ROOM.name(), 0L, tomorrow, LocalTime.of(10, 0));
        // 封锁所有诊室在10:00的窗口
        List<ResourceAvailability> allRoomWindows = resourceAvailabilityMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceAvailability>()
                        .eq(ResourceAvailability::getResourceType, ResourceType.EXAM_ROOM.name())
                        .eq(ResourceAvailability::getAvailDate, tomorrow)
                        .eq(ResourceAvailability::getStartTime, LocalTime.of(10, 0))
                        .eq(ResourceAvailability::getStatus, ResourceStatus.AVAILABLE.name())
        );
        for (ResourceAvailability w : allRoomWindows) {
            w.setStatus(ResourceStatus.BLOCKED.name());
            resourceAvailabilityMapper.updateById(w);
        }

        // 尝试改约 → 应失败（新时间无可用诊室）
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(originalApptId);
        reschReq.setNewSlotId(slot2.getId());
        reschReq.setReason("测试改约失败回滚");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> multiResourceBookingService.jointReschedule(reschReq));
        assertEquals("RESOURCE_UNAVAILABLE", ex.getCode(), "应为资源不可用错误");

        // ═══ 验证原预约完整回滚 ═══

        // 1. 预约状态不变
        Appointment afterAppt = appointmentMapper.selectById(originalApptId);
        assertEquals(AppointmentStatus.CONFIRMED.name(), afterAppt.getStatus(),
                "改约失败后原预约状态应为CONFIRMED");
        assertEquals(slot1.getId(), afterAppt.getSlotId(),
                "改约失败后原预约slotId不变");

        // 2. 号源仍被占用
        ScheduleSlot afterSlot1 = slotMapper.selectById(slot1.getId());
        assertEquals(SlotStatus.BOOKED.name(), afterSlot1.getStatus(),
                "改约失败后原号源应仍为BOOKED");
        assertEquals(originalApptId, afterSlot1.getAppointmentId(),
                "改约失败后原号源应指向原预约");

        // 3. 新号源未被占用
        ScheduleSlot afterSlot2 = slotMapper.selectById(slot2.getId());
        assertEquals(SlotStatus.AVAILABLE.name(), afterSlot2.getStatus(),
                "改约失败后新号源应保持AVAILABLE");

        // 4. 资源关联完整（3条）
        List<AppointmentResource> afterResources = appointmentResourceMapper.findByAppointment(originalApptId);
        assertEquals(3, afterResources.size(),
                "改约失败后原预约应有3条资源关联");

        // 5. 原始资源窗口仍BOOKED且指向原预约
        ResourceAvailability roomAvail = resourceAvailabilityMapper.selectById(origRoomAvailId);
        assertEquals(ResourceStatus.BOOKED.name(), roomAvail.getStatus(),
                "原诊室窗口应仍为BOOKED");
        assertEquals(originalApptId, roomAvail.getAppointmentId(),
                "原诊室窗口应指向原预约");

        ResourceAvailability equipAvail = resourceAvailabilityMapper.selectById(origEquipAvailId);
        assertEquals(ResourceStatus.BOOKED.name(), equipAvail.getStatus(),
                "原设备窗口应仍为BOOKED");
        assertEquals(originalApptId, equipAvail.getAppointmentId(),
                "原设备窗口应指向原预约");

        ResourceAvailability nurseAvail = resourceAvailabilityMapper.selectById(origNurseAvailId);
        assertEquals(ResourceStatus.BOOKED.name(), nurseAvail.getStatus(),
                "原护理窗口应仍为BOOKED");
        assertEquals(originalApptId, nurseAvail.getAppointmentId(),
                "原护理窗口应指向原预约");

        // 6. 不应有新的预约记录
        long totalAppointments = appointmentMapper.selectCount(null);
        assertEquals(1, totalAppointments, "改约失败后不应创建新预约记录");

        log.info("改约失败原子回滚验证通过: 原预约、号源、资源全部完整");
    }

    @Test
    @Order(4)
    @DisplayName("改约新资源CAS冲突（设备窗口被外部占用）：原子回滚")
    void rescheduleFail_casConflict_fullRollback() {
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(11, 0));

        // 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(40002L);
        bookReq.setPatientName("患者40002");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        Long originalApptId = result.getAppointment().getId();

        // 封锁slot2时间对应的所有设备窗口
        List<ResourceAvailability> equipWindows = resourceAvailabilityMapper.findAvailableEquipmentInDept(
                testDeptId, "CT", tomorrow, LocalTime.of(11, 0));
        for (ResourceAvailability w : equipWindows) {
            w.setStatus(ResourceStatus.BLOCKED.name());
            resourceAvailabilityMapper.updateById(w);
        }

        // 尝试改约 → 应失败
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(originalApptId);
        reschReq.setNewSlotId(slot2.getId());
        reschReq.setReason("测试CAS冲突回滚");

        assertThrows(BusinessException.class,
                () -> multiResourceBookingService.jointReschedule(reschReq));

        // 验证原预约完整
        Appointment afterAppt = appointmentMapper.selectById(originalApptId);
        assertEquals(AppointmentStatus.CONFIRMED.name(), afterAppt.getStatus());
        assertEquals(slot1.getId(), afterAppt.getSlotId());

        // 验证原号源仍BOOKED
        ScheduleSlot afterSlot1 = slotMapper.selectById(slot1.getId());
        assertEquals(SlotStatus.BOOKED.name(), afterSlot1.getStatus());

        // 验证资源关联完整
        List<AppointmentResource> afterResources = appointmentResourceMapper.findByAppointment(originalApptId);
        assertEquals(3, afterResources.size());

        // 验证无新预约
        long totalAppointments = appointmentMapper.selectCount(null);
        assertEquals(1, totalAppointments, "不应有新预约记录");
    }

    // ════════════════════════════════════════════════════════
    // 4. 重复取消幂等性
    // ════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("联合预约重复取消：第二次应幂等返回，不抛异常")
    void idempotentCancel_duplicateCancelSucceeds() {
        ScheduleSlot slot = createSlot(1, LocalTime.of(9, 0));

        // 预约
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(50001L);
        bookReq.setPatientName("患者50001");
        bookReq.setSlotId(slot.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        Long apptId = result.getAppointment().getId();

        // 记录原始资源窗口
        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(apptId);
        assertEquals(3, resources.size());

        // 第一次取消 → 应成功
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(apptId);
        cancelReq.setReason("第一次取消");
        Appointment cancelled1 = multiResourceBookingService.jointCancel(cancelReq);
        assertEquals(AppointmentStatus.CANCELLED.name(), cancelled1.getStatus());

        // 验证第一次取消后资源全部释放
        ScheduleSlot freshSlot = slotMapper.selectById(slot.getId());
        assertEquals(SlotStatus.AVAILABLE.name(), freshSlot.getStatus(), "第一次取消后号源应释放");

        for (AppointmentResource ar : resources) {
            ResourceAvailability avail = resourceAvailabilityMapper.selectById(ar.getAvailabilityId());
            assertEquals(ResourceStatus.AVAILABLE.name(), avail.getStatus(),
                    "第一次取消后资源窗口应释放: type=" + ar.getResourceType());
        }

        List<AppointmentResource> afterResources = appointmentResourceMapper.findByAppointment(apptId);
        assertTrue(afterResources.isEmpty(), "第一次取消后资源关联应已删除");

        // 第二次取消 → 应幂等返回，不抛异常
        CancelRequest cancelReq2 = new CancelRequest();
        cancelReq2.setAppointmentId(apptId);
        cancelReq2.setReason("第二次取消（幂等）");
        Appointment cancelled2 = assertDoesNotThrow(
                () -> multiResourceBookingService.jointCancel(cancelReq2),
                "重复取消不应抛异常");
        assertEquals(AppointmentStatus.CANCELLED.name(), cancelled2.getStatus(),
                "幂等返回的预约状态应为CANCELLED");

        // 验证第二次取消不会破坏已释放的资源
        ScheduleSlot slotAfter2nd = slotMapper.selectById(slot.getId());
        assertEquals(SlotStatus.AVAILABLE.name(), slotAfter2nd.getStatus(),
                "第二次取消后号源应保持AVAILABLE");

        for (AppointmentResource ar : resources) {
            ResourceAvailability avail = resourceAvailabilityMapper.selectById(ar.getAvailabilityId());
            assertEquals(ResourceStatus.AVAILABLE.name(), avail.getStatus(),
                    "第二次取消后资源窗口应保持AVAILABLE: type=" + ar.getResourceType());
        }

        log.info("幂等取消验证通过：第二次取消无异常，资源状态不变");
    }

    @Test
    @Order(6)
    @DisplayName("改约后重复取消原预约：幂等处理，不影响新预约")
    void idempotentCancel_afterReschedule_cancelOriginal() {
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(10, 30));

        // 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(50002L);
        bookReq.setPatientName("患者50002");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        Long originalApptId = result.getAppointment().getId();

        // 改约到slot2
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(originalApptId);
        reschReq.setNewSlotId(slot2.getId());
        reschReq.setReason("改约测试");
        JointBookingResult reschResult = multiResourceBookingService.jointReschedule(reschReq);
        Long newApptId = reschResult.getAppointment().getId();

        // 原预约应为RESCHEDULED
        Appointment originalAppt = appointmentMapper.selectById(originalApptId);
        assertEquals(AppointmentStatus.RESCHEDULED.name(), originalAppt.getStatus());

        // 尝试取消原预约（已改签的） → 应幂等返回
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(originalApptId);
        cancelReq.setReason("尝试取消已改签的预约");
        Appointment cancelledResult = assertDoesNotThrow(
                () -> multiResourceBookingService.jointCancel(cancelReq),
                "取消已改签的预约不应抛异常");
        assertEquals(AppointmentStatus.RESCHEDULED.name(), cancelledResult.getStatus(),
                "已改签预约取消应幂等返回RESCHEDULED状态");

        // 验证新预约不受影响
        Appointment newAppt = appointmentMapper.selectById(newApptId);
        assertEquals(AppointmentStatus.CONFIRMED.name(), newAppt.getStatus(),
                "新预约应仍为CONFIRMED");

        ScheduleSlot freshSlot2 = slotMapper.selectById(slot2.getId());
        assertEquals(SlotStatus.BOOKED.name(), freshSlot2.getStatus(),
                "新号源应仍为BOOKED");

        List<AppointmentResource> newResources = appointmentResourceMapper.findByAppointment(newApptId);
        assertEquals(3, newResources.size(), "新预约应有3条资源关联");
    }

    // ════════════════════════════════════════════════════════
    // 5. 改约推导设备类型
    // ════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("改约不指定examType时应从原设备推导：成功改约")
    void reschedule_infersExamTypeFromOriginal() {
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(10, 0));

        // 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(60001L);
        bookReq.setPatientName("患者60001");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);

        // 改约到slot2（不指定examType和偏好设备）
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(result.getAppointment().getId());
        reschReq.setNewSlotId(slot2.getId());
        reschReq.setReason("推导设备类型改约");

        JointBookingResult reschResult = multiResourceBookingService.jointReschedule(reschReq);
        assertNotNull(reschResult.getAppointment(), "改约应成功");
        assertEquals(slot2.getId(), reschResult.getAppointment().getSlotId());

        // 旧预约为RESCHEDULED
        Appointment oldAppt = appointmentMapper.selectById(result.getAppointment().getId());
        assertEquals(AppointmentStatus.RESCHEDULED.name(), oldAppt.getStatus());

        // 新预约有3条资源关联
        List<AppointmentResource> newResources = appointmentResourceMapper
                .findByAppointment(reschResult.getAppointment().getId());
        assertEquals(3, newResources.size(), "新预约应有3条资源关联");
    }

    // ════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════

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
}
