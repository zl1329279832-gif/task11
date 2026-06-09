package com.clinic.appointment;

import com.clinic.appointment.domain.dto.BookRequest;
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
public class JointBookingTest {

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

    private Long deptId, doctorId, scheduleId, slotId;
    private Long roomResourceId, equipResourceId, nursingResourceId;
    private Long roomSlotId, equipSlotId, nursingSlotId;
    private final LocalDate targetDate = LocalDate.now().plusDays(3);

    @BeforeEach
    void setup() {
        // 清理所有表
        appointmentResourceMapper.delete(null);
        appointmentMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);
        resourceSlotMapper.delete(null);
        resourceMapper.delete(null);
        examTypeMapper.delete(null);

        // 创建科室和医生
        Department dept = new Department();
        dept.setName("影像科");
        dept.setCode("YINGXIANG_" + System.nanoTime());
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("张医生");
        doctor.setEmployeeNo("DJ_" + System.nanoTime());
        doctor.setDepartmentId(deptId);
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();

        // 创建排班和号源
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

        ScheduleSlot slot = new ScheduleSlot();
        slot.setScheduleId(scheduleId);
        slot.setDoctorId(doctorId);
        slot.setDepartmentId(deptId);
        slot.setSlotDate(targetDate);
        slot.setSlotTime(LocalTime.of(9, 0));
        slot.setSlotNo(1);
        slot.setStatus("AVAILABLE");
        slot.setIsExtra(0);
        slot.setVersion(0);
        slotMapper.insert(slot);
        slotId = slot.getId();

        // 创建资源：诊室、设备、护理
        Resource room = new Resource();
        room.setName("CT诊室1");
        room.setCode("ROOM_CT1_" + System.nanoTime());
        room.setType("ROOM");
        room.setDepartmentId(deptId);
        room.setCapacity(1);
        room.setStatus(1);
        resourceMapper.insert(room);
        roomResourceId = room.getId();

        Resource equip = new Resource();
        equip.setName("CT设备1");
        equip.setCode("EQUIP_CT1_" + System.nanoTime());
        equip.setType("EQUIPMENT");
        equip.setDepartmentId(deptId);
        equip.setCapacity(1);
        equip.setStatus(1);
        resourceMapper.insert(equip);
        equipResourceId = equip.getId();

        Resource nursing = new Resource();
        nursing.setName("护理组1");
        nursing.setCode("NURSE_1_" + System.nanoTime());
        nursing.setType("NURSING");
        nursing.setDepartmentId(deptId);
        nursing.setCapacity(1);
        nursing.setStatus(1);
        resourceMapper.insert(nursing);
        nursingResourceId = nursing.getId();

        // 创建资源时段（覆盖09:00的时间窗口）
        ResourceSlot roomSlot = createResourceSlot(roomResourceId, "ROOM", targetDate,
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1);
        roomSlotId = roomSlot.getId();

        ResourceSlot equipSlot = createResourceSlot(equipResourceId, "EQUIPMENT", targetDate,
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1);
        equipSlotId = equipSlot.getId();

        ResourceSlot nursingSlot = createResourceSlot(nursingResourceId, "NURSING", targetDate,
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1);
        nursingSlotId = nursingSlot.getId();

        // 创建检查类型：CT扫描，需要诊室+设备+护理
        ExamType examType = new ExamType();
        examType.setName("CT扫描");
        examType.setCode("CT_SCAN");
        examType.setNeedRoom(1);
        examType.setNeedEquipment(1);
        examType.setNeedNursing(1);
        examType.setPatientDailyLimit(2);
        examType.setStatus(1);
        examTypeMapper.insert(examType);
    }

    @Test
    @Order(1)
    @DisplayName("联合预约成功：同时锁定医生号源+诊室+设备+护理")
    void jointBookingSuccess() {
        BookRequest req = new BookRequest();
        req.setPatientId(1001L);
        req.setPatientName("测试患者");
        req.setSlotId(slotId);
        req.setExamTypeCode("CT_SCAN");

        Appointment appt = appointmentService.book(req);

        assertNotNull(appt.getId());
        assertEquals("CONFIRMED", appt.getStatus());
        assertEquals("JOINT", appt.getBookingType());
        assertEquals("CT_SCAN", appt.getExamTypeCode());

        // 验证号源已被占用
        ScheduleSlot bookedSlot = slotMapper.selectById(slotId);
        assertEquals("BOOKED", bookedSlot.getStatus());

        // 验证资源已被预订
        List<AppointmentResource> resources = appointmentResourceMapper.findByAppointment(appt.getId());
        assertEquals(3, resources.size());

        // 验证各资源时段的booked_count
        ResourceSlot rs = resourceSlotMapper.selectById(roomSlotId);
        assertEquals(1, rs.getBookedCount());
        rs = resourceSlotMapper.selectById(equipSlotId);
        assertEquals(1, rs.getBookedCount());
        rs = resourceSlotMapper.selectById(nursingSlotId);
        assertEquals(1, rs.getBookedCount());
    }

    @Test
    @Order(2)
    @DisplayName("联合预约部分资源不可用时完整回滚")
    void jointBookingPartialFailureRollback() {
        // 先把设备资源时段标记为不可用
        ResourceSlot es = resourceSlotMapper.selectById(equipSlotId);
        resourceSlotMapper.casDisable(es.getId(), es.getVersion());

        BookRequest req = new BookRequest();
        req.setPatientId(1002L);
        req.setPatientName("测试患者2");
        req.setSlotId(slotId);
        req.setExamTypeCode("CT_SCAN");

        // 应该抛出设备不可用异常
        assertThrows(BusinessException.class, () -> appointmentService.book(req));

        // 验证号源未被占用（事务回滚）
        ScheduleSlot slot = slotMapper.selectById(slotId);
        assertEquals("AVAILABLE", slot.getStatus());
        assertEquals(0, slot.getVersion().intValue());

        // 验证诊室资源未被预订
        ResourceSlot room = resourceSlotMapper.selectById(roomSlotId);
        assertEquals(0, room.getBookedCount());

        // 验证没有预约记录
        List<Appointment> appts = appointmentMapper.findByDoctorAndDate(doctorId, targetDate);
        assertTrue(appts.isEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("诊室容量限制：capacity=2时允许两个预约")
    void roomCapacityRespected() {
        // 更新诊室资源时段容量为2
        ResourceSlot rs = resourceSlotMapper.selectById(roomSlotId);
        rs.setCapacity(2);
        resourceSlotMapper.updateById(rs);

        // 同时更新设备和护理容量为2
        rs = resourceSlotMapper.selectById(equipSlotId);
        rs.setCapacity(2);
        resourceSlotMapper.updateById(rs);
        rs = resourceSlotMapper.selectById(nursingSlotId);
        rs.setCapacity(2);
        resourceSlotMapper.updateById(rs);

        // 创建第二个号源
        ScheduleSlot slot2 = new ScheduleSlot();
        slot2.setScheduleId(scheduleId);
        slot2.setDoctorId(doctorId);
        slot2.setDepartmentId(deptId);
        slot2.setSlotDate(targetDate);
        slot2.setSlotTime(LocalTime.of(9, 10));
        slot2.setSlotNo(2);
        slot2.setStatus("AVAILABLE");
        slot2.setIsExtra(0);
        slot2.setVersion(0);
        slotMapper.insert(slot2);

        // 第一个预约
        BookRequest req1 = new BookRequest();
        req1.setPatientId(2001L);
        req1.setPatientName("患者A");
        req1.setSlotId(slotId);
        req1.setExamTypeCode("CT_SCAN");
        Appointment a1 = appointmentService.book(req1);
        assertNotNull(a1.getId());

        // 第二个预约（不同号源，相同资源）
        BookRequest req2 = new BookRequest();
        req2.setPatientId(2002L);
        req2.setPatientName("患者B");
        req2.setSlotId(slot2.getId());
        req2.setExamTypeCode("CT_SCAN");
        Appointment a2 = appointmentService.book(req2);
        assertNotNull(a2.getId());

        // 验证资源booked_count=2
        ResourceSlot roomAfter = resourceSlotMapper.selectById(roomSlotId);
        assertEquals(2, roomAfter.getBookedCount());
    }

    @Test
    @Order(4)
    @DisplayName("患者每日检查次数限制")
    void patientDailyLimit() {
        // examType的patientDailyLimit=2，先预约一次成功
        BookRequest req1 = new BookRequest();
        req1.setPatientId(3001L);
        req1.setPatientName("患者C");
        req1.setSlotId(slotId);
        req1.setExamTypeCode("CT_SCAN");
        appointmentService.book(req1);

        // 创建第二个号源+扩容资源
        ScheduleSlot slot2 = new ScheduleSlot();
        slot2.setScheduleId(scheduleId);
        slot2.setDoctorId(doctorId);
        slot2.setDepartmentId(deptId);
        slot2.setSlotDate(targetDate);
        slot2.setSlotTime(LocalTime.of(9, 10));
        slot2.setSlotNo(2);
        slot2.setStatus("AVAILABLE");
        slot2.setIsExtra(0);
        slot2.setVersion(0);
        slotMapper.insert(slot2);

        // 扩容资源（同时恢复状态，因casBook会将状态设为FULL）
        ResourceSlot rs = resourceSlotMapper.selectById(roomSlotId);
        rs.setCapacity(3);
        rs.setStatus("AVAILABLE");
        resourceSlotMapper.updateById(rs);
        rs = resourceSlotMapper.selectById(equipSlotId);
        rs.setCapacity(3);
        rs.setStatus("AVAILABLE");
        resourceSlotMapper.updateById(rs);
        rs = resourceSlotMapper.selectById(nursingSlotId);
        rs.setCapacity(3);
        rs.setStatus("AVAILABLE");
        resourceSlotMapper.updateById(rs);

        // 第二次预约成功（limit=2，当前count=1）
        BookRequest req2 = new BookRequest();
        req2.setPatientId(3001L);
        req2.setPatientName("患者C");
        req2.setSlotId(slot2.getId());
        req2.setExamTypeCode("CT_SCAN");
        appointmentService.book(req2);

        // 创建第三个号源
        ScheduleSlot slot3 = new ScheduleSlot();
        slot3.setScheduleId(scheduleId);
        slot3.setDoctorId(doctorId);
        slot3.setDepartmentId(deptId);
        slot3.setSlotDate(targetDate);
        slot3.setSlotTime(LocalTime.of(9, 20));
        slot3.setSlotNo(3);
        slot3.setStatus("AVAILABLE");
        slot3.setIsExtra(0);
        slot3.setVersion(0);
        slotMapper.insert(slot3);

        // 第三次预约应该失败（limit=2，当前count=2）
        BookRequest req3 = new BookRequest();
        req3.setPatientId(3001L);
        req3.setPatientName("患者C");
        req3.setSlotId(slot3.getId());
        req3.setExamTypeCode("CT_SCAN");

        BusinessException ex = assertThrows(BusinessException.class, () -> appointmentService.book(req3));
        assertEquals("PATIENT_EXAM_LIMIT", ex.getCode());
    }

    private ResourceSlot createResourceSlot(Long resourceId, String type, LocalDate date,
                                             LocalTime start, LocalTime end, int capacity) {
        ResourceSlot rs = new ResourceSlot();
        rs.setResourceId(resourceId);
        rs.setResourceType(type);
        rs.setSlotDate(date);
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
