package com.clinic.appointment;

import com.clinic.appointment.domain.dto.JointBookRequest;
import com.clinic.appointment.domain.dto.JointBookingResult;
import com.clinic.appointment.domain.dto.JointRescheduleRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联合预约改约冲突测试
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JointRescheduleConflictTest {

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
    private LocalDate tomorrow;

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

        tomorrow = LocalDate.now().plusDays(1);

        Department dept = new Department();
        dept.setName("影像科");
        dept.setCode("IMG-RESCH");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        testDeptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("改约医生");
        doctor.setEmployeeNo("D-RESCH");
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

        // 资源
        ExamRoom room = new ExamRoom();
        room.setName("改约检查室");
        room.setCode("R-RESCH");
        room.setDepartmentId(testDeptId);
        room.setStatus("ACTIVE");
        examRoomMapper.insert(room);

        Equipment equip = new Equipment();
        equip.setName("改约CT");
        equip.setCode("E-RESCH");
        equip.setEquipmentType("CT");
        equip.setDepartmentId(testDeptId);
        equip.setStatus("ACTIVE");
        equipmentMapper.insert(equip);

        NursingStaff nurse = new NursingStaff();
        nurse.setName("改约护士");
        nurse.setEmployeeNo("N-RESCH");
        nurse.setDepartmentId(testDeptId);
        nurse.setStatus(1);
        nursingStaffMapper.insert(nurse);

        resourceAvailabilityService.generateAvailability(testDeptId, tomorrow, "MORNING");
    }

    @Test
    @Order(1)
    @DisplayName("改约到新时间无可用设备：应失败，原预约不变")
    void rescheduleFailsWhenNewEquipmentUnavailable() {
        // 创建2个号源
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(10, 0));

        // 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(8001L);
        bookReq.setPatientName("患者8001");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);
        assertNotNull(result.getAppointment());

        // 手动将slot2时间对应的设备窗口标记为BOOKED（模拟设备被占用）
        List<ResourceAvailability> equipWindows = resourceAvailabilityMapper.findAvailableEquipmentInDept(
                testDeptId, "CT", tomorrow, LocalTime.of(10, 0));
        for (ResourceAvailability w : equipWindows) {
            w.setStatus(ResourceStatus.BOOKED.name());
            resourceAvailabilityMapper.updateById(w);
        }

        // 改约应失败
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(result.getAppointment().getId());
        reschReq.setNewSlotId(slot2.getId());
        reschReq.setReason("测试改约");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> multiResourceBookingService.jointReschedule(reschReq));
        assertEquals("RESOURCE_UNAVAILABLE", ex.getCode());

        // 验证原预约不变
        Appointment original = appointmentMapper.selectById(result.getAppointment().getId());
        assertEquals(AppointmentStatus.CONFIRMED.name(), original.getStatus());
        assertEquals(slot1.getId(), original.getSlotId());
    }

    @Test
    @Order(2)
    @DisplayName("正常联合改约：释放旧资源，获取新资源")
    void rescheduleSuccessReleasesOldAcquiresNew() {
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(10, 30));

        // 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(8002L);
        bookReq.setPatientName("患者8002");
        bookReq.setSlotId(slot1.getId());
        bookReq.setExamType("CT");
        JointBookingResult result = multiResourceBookingService.jointBook(bookReq);

        // 改约到slot2
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(result.getAppointment().getId());
        reschReq.setNewSlotId(slot2.getId());
        reschReq.setReason("时间调整");
        JointBookingResult newResult = multiResourceBookingService.jointReschedule(reschReq);

        assertNotNull(newResult.getAppointment());
        assertEquals(slot2.getId(), newResult.getAppointment().getSlotId());

        // 旧预约状态
        Appointment oldAppt = appointmentMapper.selectById(result.getAppointment().getId());
        assertEquals(AppointmentStatus.RESCHEDULED.name(), oldAppt.getStatus());

        // 旧号源应释放
        ScheduleSlot freshSlot1 = slotMapper.selectById(slot1.getId());
        assertEquals(SlotStatus.AVAILABLE.name(), freshSlot1.getStatus());

        // 新号源应被占用
        ScheduleSlot freshSlot2 = slotMapper.selectById(slot2.getId());
        assertEquals(SlotStatus.BOOKED.name(), freshSlot2.getStatus());

        // 新预约应有3条资源关联
        List<AppointmentResource> newResources = appointmentResourceMapper
                .findByAppointment(newResult.getAppointment().getId());
        assertEquals(3, newResources.size());
    }

    @Test
    @Order(3)
    @DisplayName("改约部分失败（新slot可用但设备被外部锁定）：原子回滚")
    void rescheduleAtomicRollbackOnPartialFailure() {
        ScheduleSlot slot1 = createSlot(1, LocalTime.of(9, 0));
        ScheduleSlot slot2 = createSlot(2, LocalTime.of(11, 0));

        // 预约slot1
        JointBookRequest bookReq = new JointBookRequest();
        bookReq.setPatientId(8003L);
        bookReq.setPatientName("患者8003");
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

        // 改约应失败
        JointRescheduleRequest reschReq = new JointRescheduleRequest();
        reschReq.setAppointmentId(originalApptId);
        reschReq.setNewSlotId(slot2.getId());
        assertThrows(BusinessException.class,
                () -> multiResourceBookingService.jointReschedule(reschReq));

        // 验证原预约完整无变化
        Appointment original = appointmentMapper.selectById(originalApptId);
        assertEquals(AppointmentStatus.CONFIRMED.name(), original.getStatus());
        assertEquals(slot1.getId(), original.getSlotId());

        // 原号源仍BOOKED
        ScheduleSlot freshSlot1 = slotMapper.selectById(slot1.getId());
        assertEquals(SlotStatus.BOOKED.name(), freshSlot1.getStatus());

        // 原资源关联仍在
        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(originalApptId);
        assertEquals(3, resources.size());
    }

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
