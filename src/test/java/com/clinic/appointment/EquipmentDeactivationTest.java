package com.clinic.appointment;

import com.clinic.appointment.domain.dto.EquipmentDeactivateRequest;
import com.clinic.appointment.domain.dto.JointBookRequest;
import com.clinic.appointment.domain.dto.JointBookingResult;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.ResourceStatus;
import com.clinic.appointment.domain.enums.ResourceType;
import com.clinic.appointment.domain.enums.SlotStatus;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设备停用测试
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EquipmentDeactivationTest {

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

        Department dept = new Department();
        dept.setName("影像科-停用");
        dept.setCode("IMG-DEACT");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        testDeptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("停用医生");
        doctor.setEmployeeNo("D-DEACT");
        doctor.setDepartmentId(testDeptId);
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        testDoctorId = doctor.getId();

        // 检查室
        ExamRoom room = new ExamRoom();
        room.setName("停用检查室");
        room.setCode("R-DEACT");
        room.setDepartmentId(testDeptId);
        room.setStatus("ACTIVE");
        examRoomMapper.insert(room);

        // 护理人员
        NursingStaff nurse = new NursingStaff();
        nurse.setName("停用护士");
        nurse.setEmployeeNo("N-DEACT");
        nurse.setDepartmentId(testDeptId);
        nurse.setStatus(1);
        nursingStaffMapper.insert(nurse);
    }

    @Test
    @Order(1)
    @DisplayName("设备停用封锁未来可用窗口")
    void deactivateBlocksFutureAvailability() {
        // 设备
        Equipment equip = new Equipment();
        equip.setName("CT-封锁测试");
        equip.setCode("E-BLOCK");
        equip.setEquipmentType("CT");
        equip.setDepartmentId(testDeptId);
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);

        // 为7天生成资源窗口
        LocalDate baseDate = LocalDate.now().plusDays(1);
        for (int i = 0; i < 7; i++) {
            resourceAvailabilityService.generateAvailability(testDeptId, baseDate.plusDays(i), "MORNING");
        }

        // 从day3开始停用
        EquipmentDeactivateRequest req = new EquipmentDeactivateRequest();
        req.setEquipmentId(equip.getId());
        req.setEffectiveDate(baseDate.plusDays(2));
        req.setReason("设备维护");

        Map<String, Object> result = resourceAvailabilityService.deactivateEquipment(req);
        assertTrue((int) result.get("blockedWindows") > 0, "应有窗口被封锁");

        // 验证day1和day2的窗口仍AVAILABLE
        List<ResourceAvailability> day1 = resourceAvailabilityMapper.findAvailableWindow(
                ResourceType.EQUIPMENT.name(), equip.getId(), baseDate, LocalTime.of(9, 0));
        assertFalse(day1.isEmpty(), "day1窗口应仍可用");

        List<ResourceAvailability> day2 = resourceAvailabilityMapper.findAvailableWindow(
                ResourceType.EQUIPMENT.name(), equip.getId(), baseDate.plusDays(1), LocalTime.of(9, 0));
        assertFalse(day2.isEmpty(), "day2窗口应仍可用");

        // 验证day3+的窗口被BLOCKED
        List<ResourceAvailability> day3 = resourceAvailabilityMapper.findAvailableWindow(
                ResourceType.EQUIPMENT.name(), equip.getId(), baseDate.plusDays(2), LocalTime.of(9, 0));
        assertTrue(day3.isEmpty(), "day3窗口应被封锁（不再是AVAILABLE）");

        // 验证设备状态
        Equipment updatedEquip = equipmentMapper.selectById(equip.getId());
        assertEquals("INACTIVE", updatedEquip.getStatus());
    }

    @Test
    @Order(2)
    @DisplayName("设备停用取消受影响预约")
    void deactivateCancelsAffectedAppointments() {
        LocalDate day5 = LocalDate.now().plusDays(5);

        Equipment equip = new Equipment();
        equip.setName("CT-取消测试");
        equip.setCode("E-CANCEL");
        equip.setEquipmentType("CT");
        equip.setDepartmentId(testDeptId);
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);

        // 生成5天的窗口
        LocalDate baseDate = LocalDate.now().plusDays(1);
        for (int i = 0; i < 5; i++) {
            resourceAvailabilityService.generateAvailability(testDeptId, baseDate.plusDays(i), "MORNING");
        }

        // 排班和号源在day5
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctorId(testDoctorId);
        schedule.setDepartmentId(testDeptId);
        schedule.setScheduleDate(day5);
        schedule.setTimePeriod("MORNING");
        schedule.setTotalSlots(2);
        schedule.setBookedSlots(0);
        schedule.setExtraSlots(0);
        schedule.setStatus("NORMAL");
        scheduleMapper.insert(schedule);

        ScheduleSlot slot = new ScheduleSlot();
        slot.setScheduleId(schedule.getId());
        slot.setDoctorId(testDoctorId);
        slot.setDepartmentId(testDeptId);
        slot.setSlotDate(day5);
        slot.setSlotTime(LocalTime.of(9, 0));
        slot.setSlotNo(1);
        slot.setStatus(SlotStatus.AVAILABLE.name());
        slot.setIsExtra(0);
        slot.setVersion(0);
        slotMapper.insert(slot);

        // 在day5创建联合预约
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(9001L);
        bookReq.setPatientName("患者9001");
        bookReq.setSlotId(slot.getId());
        bookReq.setExamType("CT");
        JointBookingResult bookResult = multiResourceBookingService.jointBook(bookReq);
        assertNotNull(bookResult.getAppointment());

        // 从day3开始停用设备
        EquipmentDeactivateRequest deactivateReq = new EquipmentDeactivateRequest();
        deactivateReq.setEquipmentId(equip.getId());
        deactivateReq.setEffectiveDate(baseDate.plusDays(2));
        deactivateReq.setReason("设备维修");
        Map<String, Object> result = resourceAvailabilityService.deactivateEquipment(deactivateReq);

        assertTrue((int) result.get("cancelledAppointments") >= 1, "应有预约被取消");

        // 验证预约被取消
        Appointment appt = appointmentMapper.selectById(bookResult.getAppointment().getId());
        assertEquals(AppointmentStatus.CANCELLED.name(), appt.getStatus());
    }

    @Test
    @Order(3)
    @DisplayName("设备停用后新预约使用替代设备")
    void afterDeactivationNewBookingUsesAlternative() {
        LocalDate testDate = LocalDate.now().plusDays(1);

        // 2台CT设备
        Equipment equip1 = new Equipment();
        equip1.setName("CT-A");
        equip1.setCode("E-A");
        equip1.setEquipmentType("CT");
        equip1.setDepartmentId(testDeptId);
        equip1.setStatus("ACTIVE");
        equipmentMapper.insert(equip1);

        Equipment equip2 = new Equipment();
        equip2.setName("CT-B");
        equip2.setCode("E-B");
        equip2.setEquipmentType("CT");
        equip2.setDepartmentId(testDeptId);
        equip2.setStatus("ACTIVE");
        equipmentMapper.insert(equip2);

        resourceAvailabilityService.generateAvailability(testDeptId, testDate, "MORNING");

        // 停用equip1
        EquipmentDeactivateRequest deactivateReq = new EquipmentDeactivateRequest();
        deactivateReq.setEquipmentId(equip1.getId());
        deactivateReq.setEffectiveDate(testDate);
        resourceAvailabilityService.deactivateEquipment(deactivateReq);

        // 创建排班和号源
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctorId(testDoctorId);
        schedule.setDepartmentId(testDeptId);
        schedule.setScheduleDate(testDate);
        schedule.setTimePeriod("MORNING");
        schedule.setTotalSlots(2);
        schedule.setBookedSlots(0);
        schedule.setExtraSlots(0);
        schedule.setStatus("NORMAL");
        scheduleMapper.insert(schedule);

        ScheduleSlot slot = new ScheduleSlot();
        slot.setScheduleId(schedule.getId());
        slot.setDoctorId(testDoctorId);
        slot.setDepartmentId(testDeptId);
        slot.setSlotDate(testDate);
        slot.setSlotTime(LocalTime.of(9, 0));
        slot.setSlotNo(1);
        slot.setStatus(SlotStatus.AVAILABLE.name());
        slot.setIsExtra(0);
        slot.setVersion(0);
        slotMapper.insert(slot);

        // 新预约应使用equip2
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(9003L);
        bookReq.setPatientName("患者9003");
        bookReq.setSlotId(slot.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);

        assertNotNull(result.getAppointment());

        // 验证使用的是equip2
        List<AppointmentResource> resources = appointmentResourceMapper
                .findByAppointment(result.getAppointment().getId());
        AppointmentResource equipResource = resources.stream()
                .filter(r -> ResourceType.EQUIPMENT.name().equals(r.getResourceType()))
                .findFirst().orElse(null);
        assertNotNull(equipResource);
        assertEquals(equip2.getId(), equipResource.getResourceId(), "应使用替代设备CT-B");
    }
}
