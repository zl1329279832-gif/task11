package com.clinic.appointment;

import com.clinic.appointment.domain.dto.JointBookRequest;
import com.clinic.appointment.domain.dto.JointBookingResult;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.ResourceStatus;
import com.clinic.appointment.domain.enums.ResourceType;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.MultiResourceBookingService;
import com.clinic.appointment.service.ResourceAvailabilityService;
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
 * 多资源联合预约并发测试
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConcurrentMultiResourceBookingTest {

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
    @Autowired private MultiResourceBookingService multiResourceBookingService;
    @Autowired private ResourceAvailabilityService resourceAvailabilityService;

    private Long testDeptId;
    private Long testDoctorId;
    private Long testScheduleId;

    @BeforeEach
    void setupTestData() {
        // 清理旧数据（按依赖顺序）
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

        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 科室
        Department dept = new Department();
        dept.setName("影像科");
        dept.setCode("IMG001");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        testDeptId = dept.getId();

        // 医生
        Doctor doctor = new Doctor();
        doctor.setName("李医生");
        doctor.setEmployeeNo("D100");
        doctor.setDepartmentId(testDeptId);
        doctor.setTitle("主任医师");
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        testDoctorId = doctor.getId();

        // 排班
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
        room.setName("CT检查室1");
        room.setCode("CT-R001");
        room.setDepartmentId(testDeptId);
        room.setLocation("A栋2层");
        room.setStatus("ACTIVE");
        examRoomMapper.insert(room);

        // 设备
        Equipment equip = new Equipment();
        equip.setName("CT扫描仪1");
        equip.setCode("CT-S001");
        equip.setEquipmentType("CT");
        equip.setDepartmentId(testDeptId);
        equip.setExamRoomId(room.getId());
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);

        // 护理人员
        NursingStaff nurse = new NursingStaff();
        nurse.setName("王护士");
        nurse.setEmployeeNo("N001");
        nurse.setDepartmentId(testDeptId);
        nurse.setQualification("RN");
        nurse.setStatus(1);
        nursingStaffMapper.insert(nurse);

        // 生成资源可用性窗口
        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");
    }

    @Test
    @Order(1)
    @DisplayName("并发联合预约同一资源组合：仅1人成功")
    void concurrentJointBookingOnlyOneSucceeds() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 只创建1个号源
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

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int patientId = 5000 + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    JointBookRequest req = new JointBookRequest();
                    req.setPatientId((long) patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(slot.getId());
                    req.setExamType("CT");
                    multiResourceBookingService.jointBook(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("患者{}联合预约失败: {}", patientId, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        startLatch.countDown();
        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        log.info("并发联合预约结果: success={}, fail={}", successCount.get(), failCount.get());

        assertEquals(1, successCount.get(), "应该只有1个联合预约成功");
        assertEquals(threadCount - 1, failCount.get(), "其余应该失败");

        // 验证号源状态
        ScheduleSlot freshSlot = slotMapper.selectById(slot.getId());
        assertEquals(SlotStatus.BOOKED.name(), freshSlot.getStatus());

        // 验证关联记录
        List<Appointment> appointments = appointmentMapper.findByDoctorAndDate(testDoctorId, tomorrow);
        assertEquals(1, appointments.size());
        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(
                appointments.get(0).getId());
        assertEquals(3, resources.size(), "应有3条资源关联记录（诊室+设备+护理）");

        // 验证资源窗口状态 - 从关联记录获取实际resourceId
        Long roomResourceId = resources.stream()
                .filter(r -> ResourceType.EXAM_ROOM.name().equals(r.getResourceType()))
                .findFirst().map(AppointmentResource::getResourceId).orElseThrow();
        List<ResourceAvailability> bookedResources = resourceAvailabilityMapper
                .findBookedFrom(ResourceType.EXAM_ROOM.name(), roomResourceId, tomorrow.minusDays(1));
        assertTrue(bookedResources.stream().anyMatch(r ->
                ResourceStatus.BOOKED.name().equals(r.getStatus())), "诊室窗口应被预约");
    }

    @Test
    @Order(2)
    @DisplayName("多设备并发联合预约：不同号源各自获取不同设备")
    void concurrentBookingDifferentEquipment() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 追加4台设备（加之前共5台）
        for (int i = 2; i <= 5; i++) {
            Equipment equip = new Equipment();
            equip.setName("CT扫描仪" + i);
            equip.setCode("CT-S00" + i);
            equip.setEquipmentType("CT");
            equip.setDepartmentId(testDeptId);
            equip.setStatus("ACTIVE");
            equipmentMapper.insert(equip);
        }
        // 重新生成资源窗口（包含新设备）
        resourceAvailabilityMapper.delete(null);
        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");

        // 追加检查室和护理
        for (int i = 2; i <= 5; i++) {
            ExamRoom room = new ExamRoom();
            room.setName("CT检查室" + i);
            room.setCode("CT-R00" + i);
            room.setDepartmentId(testDeptId);
            room.setStatus("ACTIVE");
            examRoomMapper.insert(room);
        }
        for (int i = 2; i <= 5; i++) {
            NursingStaff nurse = new NursingStaff();
            nurse.setName("护士" + i);
            nurse.setEmployeeNo("N00" + i);
            nurse.setDepartmentId(testDeptId);
            nurse.setStatus(1);
            nursingStaffMapper.insert(nurse);
        }
        resourceAvailabilityMapper.delete(null);
        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");

        // 创建5个号源（不同时间）
        for (int i = 1; i <= 5; i++) {
            ScheduleSlot s = new ScheduleSlot();
            s.setScheduleId(testScheduleId);
            s.setDoctorId(testDoctorId);
            s.setDepartmentId(testDeptId);
            s.setSlotDate(tomorrow);
            s.setSlotTime(LocalTime.of(8, 0).plusMinutes(i * 30L));
            s.setSlotNo(i);
            s.setStatus(SlotStatus.AVAILABLE.name());
            s.setIsExtra(0);
            s.setVersion(0);
            slotMapper.insert(s);
        }

        List<ScheduleSlot> slots = slotMapper.findAvailable(testDoctorId, tomorrow);
        int slotCount = slots.size();

        ExecutorService executor = Executors.newFixedThreadPool(slotCount);
        CountDownLatch latch = new CountDownLatch(slotCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < slotCount; i++) {
            final int patientId = 6000 + i;
            final Long slotId = slots.get(i).getId();
            executor.submit(() -> {
                try {
                    JointBookRequest req = new JointBookRequest();
                    req.setPatientId((long) patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(slotId);
                    req.setExamType("CT");
                    multiResourceBookingService.jointBook(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("患者{}联合预约失败: {}", patientId, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(slotCount, successCount.get(), "所有不同号源都应成功预约");
    }

    @Test
    @Order(3)
    @DisplayName("设备不足时联合预约失败")
    void jointBookingFailsWhenEquipmentExhausted() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 清理并只保留1台设备、1个诊室、1个护理
        equipmentMapper.delete(null);
        examRoomMapper.delete(null);
        nursingStaffMapper.delete(null);
        resourceAvailabilityMapper.delete(null);

        Equipment equip = new Equipment();
        equip.setName("唯一CT");
        equip.setCode("CT-ONLY");
        equip.setEquipmentType("CT");
        equip.setDepartmentId(testDeptId);
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);

        ExamRoom room = new ExamRoom();
        room.setName("唯一检查室");
        room.setCode("R-ONLY");
        room.setDepartmentId(testDeptId);
        room.setStatus("ACTIVE");
        examRoomMapper.insert(room);

        NursingStaff nurse = new NursingStaff();
        nurse.setName("唯一护士");
        nurse.setEmployeeNo("N-ONLY");
        nurse.setDepartmentId(testDeptId);
        nurse.setStatus(1);
        nursingStaffMapper.insert(nurse);

        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");

        // 创建2个号源
        ScheduleSlot slot1 = new ScheduleSlot();
        slot1.setScheduleId(testScheduleId);
        slot1.setDoctorId(testDoctorId);
        slot1.setDepartmentId(testDeptId);
        slot1.setSlotDate(tomorrow);
        slot1.setSlotTime(LocalTime.of(9, 0));
        slot1.setSlotNo(1);
        slot1.setStatus(SlotStatus.AVAILABLE.name());
        slot1.setIsExtra(0);
        slot1.setVersion(0);
        slotMapper.insert(slot1);

        ScheduleSlot slot2 = new ScheduleSlot();
        slot2.setScheduleId(testScheduleId);
        slot2.setDoctorId(testDoctorId);
        slot2.setDepartmentId(testDeptId);
        slot2.setSlotDate(tomorrow);
        slot2.setSlotTime(LocalTime.of(9, 0));
        slot2.setSlotNo(2);
        slot2.setStatus(SlotStatus.AVAILABLE.name());
        slot2.setIsExtra(0);
        slot2.setVersion(0);
        slotMapper.insert(slot2);

        // 第一次预约应成功
        JointBookRequest req1 = new JointBookRequest();
        req1.setPatientId(7001L);
        req1.setPatientName("患者7001");
        req1.setSlotId(slot1.getId());
        req1.setExamType("CT");
        JointBookingResult result1 = multiResourceBookingService.jointBook(req1);
        assertNotNull(result1.getAppointment());

        // 第二次预约应失败（设备已被占用）
        JointBookRequest req2 = new JointBookRequest();
        req2.setPatientId(7002L);
        req2.setPatientName("患者7002");
        req2.setSlotId(slot2.getId());
        req2.setExamType("CT");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> multiResourceBookingService.jointBook(req2));
        assertEquals("RESOURCE_UNAVAILABLE", ex.getCode());
    }
}
