package com.clinic.appointment;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.RescheduleRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AppointmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JointRescheduleTest {

    @Autowired private AppointmentService appointmentService;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private ScheduleSlotMapper slotMapper;
    @Autowired private DoctorScheduleMapper scheduleMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private ResourceMapper resourceMapper;
    @Autowired private ResourceSlotMapper resourceSlotMapper;
    @Autowired private ExamTypeMapper examTypeMapper;
    @Autowired private AppointmentResourceMapper appointmentResourceMapper;

    private Long deptId, doctorId, scheduleId;
    private Long slotId1, slotId2;
    private Long roomSlotId, equipSlotId, nursingSlotId;
    private Long roomSlotId2, equipSlotId2, nursingSlotId2;
    private final LocalDate targetDate = LocalDate.now().plusDays(5);

    @BeforeEach
    void setup() {
        appointmentResourceMapper.delete(null);
        appointmentMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);
        resourceSlotMapper.delete(null);
        resourceMapper.delete(null);
        examTypeMapper.delete(null);

        Department dept = new Department();
        dept.setName("影像科");
        dept.setCode("YXRS_" + System.nanoTime());
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("王医生");
        doctor.setEmployeeNo("DRS_" + System.nanoTime());
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

        // 两个号源
        slotId1 = createSlot(1, LocalTime.of(9, 0));
        slotId2 = createSlot(2, LocalTime.of(9, 30));

        // 资源
        Resource room = new Resource();
        room.setName("检查室A");
        room.setCode("ROOM_A_" + System.nanoTime());
        room.setType("ROOM");
        room.setCapacity(1);
        room.setStatus(1);
        resourceMapper.insert(room);

        Resource equip = new Resource();
        equip.setName("超声设备");
        equip.setCode("EQUIP_US_" + System.nanoTime());
        equip.setType("EQUIPMENT");
        equip.setCapacity(1);
        equip.setStatus(1);
        resourceMapper.insert(equip);

        Resource nursing = new Resource();
        nursing.setName("护理组B");
        nursing.setCode("NURSE_B_" + System.nanoTime());
        nursing.setType("NURSING");
        nursing.setCapacity(1);
        nursing.setStatus(1);
        resourceMapper.insert(nursing);

        // 为09:00时段创建资源时段
        roomSlotId = createResourceSlot(room.getId(), "ROOM",
                LocalTime.of(8, 0), LocalTime.of(9, 15), 1).getId();
        equipSlotId = createResourceSlot(equip.getId(), "EQUIPMENT",
                LocalTime.of(8, 0), LocalTime.of(9, 15), 1).getId();
        nursingSlotId = createResourceSlot(nursing.getId(), "NURSING",
                LocalTime.of(8, 0), LocalTime.of(9, 15), 1).getId();

        // 为09:30时段创建资源时段
        roomSlotId2 = createResourceSlot(room.getId(), "ROOM",
                LocalTime.of(9, 15), LocalTime.of(10, 0), 1).getId();
        equipSlotId2 = createResourceSlot(equip.getId(), "EQUIPMENT",
                LocalTime.of(9, 15), LocalTime.of(10, 0), 1).getId();
        nursingSlotId2 = createResourceSlot(nursing.getId(), "NURSING",
                LocalTime.of(9, 15), LocalTime.of(10, 0), 1).getId();

        // 检查类型
        ExamType examType = new ExamType();
        examType.setName("超声检查");
        examType.setCode("ULTRASOUND");
        examType.setNeedRoom(1);
        examType.setNeedEquipment(1);
        examType.setNeedNursing(1);
        examType.setStatus(1);
        examTypeMapper.insert(examType);
    }

    @Test
    @Order(1)
    @DisplayName("联合预约改签：旧资源释放、新资源预订")
    void jointRescheduleRecalculatesResources() {
        // 先预约第一个号源
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(8001L);
        bookReq.setPatientName("改签患者");
        bookReq.setSlotId(slotId1);
        bookReq.setExamTypeCode("ULTRASOUND");
        Appointment original = appointmentService.book(bookReq);

        // 验证旧资源已预订
        assertEquals(1, resourceSlotMapper.selectById(roomSlotId).getBookedCount());
        assertEquals(1, resourceSlotMapper.selectById(equipSlotId).getBookedCount());

        // 改签到第二个号源
        RescheduleRequest resReq = new RescheduleRequest();
        resReq.setAppointmentId(original.getId());
        resReq.setNewSlotId(slotId2);
        Appointment newAppt = appointmentService.reschedule(resReq);

        assertNotNull(newAppt.getId());
        assertEquals("CONFIRMED", newAppt.getStatus());
        assertEquals("JOINT", newAppt.getBookingType());
        assertEquals("ULTRASOUND", newAppt.getExamTypeCode());
        assertEquals(original.getId(), newAppt.getOriginalId());

        // 验证旧预约状态
        Appointment oldAppt = appointmentMapper.selectById(original.getId());
        assertEquals("RESCHEDULED", oldAppt.getStatus());

        // 验证旧号源释放
        ScheduleSlot oldSlot = slotMapper.selectById(slotId1);
        assertEquals("AVAILABLE", oldSlot.getStatus());

        // 验证新号源占用
        ScheduleSlot newSlot = slotMapper.selectById(slotId2);
        assertEquals("BOOKED", newSlot.getStatus());

        // 验证旧资源释放（booked_count=0）
        assertEquals(0, resourceSlotMapper.selectById(roomSlotId).getBookedCount());
        assertEquals(0, resourceSlotMapper.selectById(equipSlotId).getBookedCount());
        assertEquals(0, resourceSlotMapper.selectById(nursingSlotId).getBookedCount());

        // 验证新资源预订（booked_count=1）
        assertEquals(1, resourceSlotMapper.selectById(roomSlotId2).getBookedCount());
        assertEquals(1, resourceSlotMapper.selectById(equipSlotId2).getBookedCount());
        assertEquals(1, resourceSlotMapper.selectById(nursingSlotId2).getBookedCount());

        // 验证新预约有资源关联
        List<AppointmentResource> newResources =
                appointmentResourceMapper.findByAppointment(newAppt.getId());
        assertEquals(3, newResources.size());

        // 验证旧预约的资源关联已释放
        List<AppointmentResource> oldResources =
                appointmentResourceMapper.findByAppointment(original.getId());
        assertTrue(oldResources.isEmpty()); // findByAppointment只查BOOKED的
    }

    @Test
    @Order(2)
    @DisplayName("改签冲突：新资源不可用时失败，旧预约不受影响")
    void rescheduleConflictOnNewResources() {
        // 先预约第一个号源
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(8002L);
        bookReq.setPatientName("冲突患者");
        bookReq.setSlotId(slotId1);
        bookReq.setExamTypeCode("ULTRASOUND");
        Appointment original = appointmentService.book(bookReq);

        // 停用新时段的设备资源
        ResourceSlot es2 = resourceSlotMapper.selectById(equipSlotId2);
        resourceSlotMapper.casDisable(es2.getId(), es2.getVersion());

        // 尝试改签到第二个号源（设备不可用）
        RescheduleRequest resReq = new RescheduleRequest();
        resReq.setAppointmentId(original.getId());
        resReq.setNewSlotId(slotId2);

        assertThrows(BusinessException.class, () -> appointmentService.reschedule(resReq));

        // 验证旧预约不受影响
        Appointment oldAppt = appointmentMapper.selectById(original.getId());
        assertEquals("CONFIRMED", oldAppt.getStatus());

        // 验证旧号源仍然占用
        ScheduleSlot oldSlot = slotMapper.selectById(slotId1);
        assertEquals("BOOKED", oldSlot.getStatus());

        // 验证旧资源仍然预订
        assertEquals(1, resourceSlotMapper.selectById(roomSlotId).getBookedCount());
        assertEquals(1, resourceSlotMapper.selectById(equipSlotId).getBookedCount());
    }

    private Long createSlot(int slotNo, LocalTime time) {
        ScheduleSlot slot = new ScheduleSlot();
        slot.setScheduleId(scheduleId);
        slot.setDoctorId(doctorId);
        slot.setDepartmentId(deptId);
        slot.setSlotDate(targetDate);
        slot.setSlotTime(time);
        slot.setSlotNo(slotNo);
        slot.setStatus("AVAILABLE");
        slot.setIsExtra(0);
        slot.setVersion(0);
        slotMapper.insert(slot);
        return slot.getId();
    }

    private ResourceSlot createResourceSlot(Long resourceId, String type,
                                             LocalTime start, LocalTime end, int capacity) {
        ResourceSlot rs = new ResourceSlot();
        rs.setResourceId(resourceId);
        rs.setResourceType(type);
        rs.setSlotDate(targetDate);
        rs.setStartTime(start);
        rs.setEndTime(end);
        rs.setCapacity(capacity);
        rs.setBookedCount(0);
        rs.setStatus("AVAILABLE");
        rs.setVersion(0);
        resourceSlotMapper.insert(rs);
        return rs;
    }
}
