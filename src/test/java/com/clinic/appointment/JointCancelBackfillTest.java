package com.clinic.appointment;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.CancelRequest;
import com.clinic.appointment.domain.dto.WaitlistRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.WaitlistService;
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
public class JointCancelBackfillTest {

    @Autowired private AppointmentService appointmentService;
    @Autowired private WaitlistService waitlistService;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private ScheduleSlotMapper slotMapper;
    @Autowired private DoctorScheduleMapper scheduleMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private WaitlistMapper waitlistMapper;
    @Autowired private ResourceMapper resourceMapper;
    @Autowired private ResourceSlotMapper resourceSlotMapper;
    @Autowired private ExamTypeMapper examTypeMapper;
    @Autowired private AppointmentResourceMapper appointmentResourceMapper;

    private Long deptId, doctorId, scheduleId, slotId;
    private Long roomSlotId, equipSlotId, nursingSlotId;
    private final LocalDate targetDate = LocalDate.now().plusDays(6);

    @BeforeEach
    void setup() {
        appointmentResourceMapper.delete(null);
        waitlistMapper.delete(null);
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
        dept.setCode("YXCB_" + System.nanoTime());
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("赵医生");
        doctor.setEmployeeNo("DCB_" + System.nanoTime());
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

        slotId = createSlot(1, LocalTime.of(9, 0));

        // 资源
        Resource room = new Resource();
        room.setName("检查室C");
        room.setCode("ROOM_C_" + System.nanoTime());
        room.setType("ROOM");
        room.setCapacity(1);
        room.setStatus(1);
        resourceMapper.insert(room);

        Resource equip = new Resource();
        equip.setName("X光设备");
        equip.setCode("EQUIP_XR_" + System.nanoTime());
        equip.setType("EQUIPMENT");
        equip.setCapacity(1);
        equip.setStatus(1);
        resourceMapper.insert(equip);

        Resource nursing = new Resource();
        nursing.setName("护理组C");
        nursing.setCode("NURSE_C_" + System.nanoTime());
        nursing.setType("NURSING");
        nursing.setCapacity(1);
        nursing.setStatus(1);
        resourceMapper.insert(nursing);

        roomSlotId = createResourceSlot(room.getId(), "ROOM",
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1).getId();
        equipSlotId = createResourceSlot(equip.getId(), "EQUIPMENT",
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1).getId();
        nursingSlotId = createResourceSlot(nursing.getId(), "NURSING",
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1).getId();

        ExamType examType = new ExamType();
        examType.setName("X光检查");
        examType.setCode("XRAY");
        examType.setNeedRoom(1);
        examType.setNeedEquipment(1);
        examType.setNeedNursing(1);
        examType.setStatus(1);
        examTypeMapper.insert(examType);
    }

    @Test
    @Order(1)
    @DisplayName("取消联合预约：释放号源和所有资源")
    void cancelJointAppointmentReleasesAllResources() {
        // 预约
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(9001L);
        bookReq.setPatientName("取消测试患者");
        bookReq.setSlotId(slotId);
        bookReq.setExamTypeCode("XRAY");
        Appointment appt = appointmentService.book(bookReq);

        // 验证资源已预订
        assertEquals(1, resourceSlotMapper.selectById(roomSlotId).getBookedCount());
        assertEquals(1, resourceSlotMapper.selectById(equipSlotId).getBookedCount());
        assertEquals(1, resourceSlotMapper.selectById(nursingSlotId).getBookedCount());

        // 取消
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(appt.getId());
        cancelReq.setReason("测试取消");
        Appointment cancelled = appointmentService.cancel(cancelReq);

        assertEquals("CANCELLED", cancelled.getStatus());

        // 验证号源释放
        ScheduleSlot slot = slotMapper.selectById(slotId);
        assertEquals("AVAILABLE", slot.getStatus());

        // 验证资源释放（booked_count=0）
        assertEquals(0, resourceSlotMapper.selectById(roomSlotId).getBookedCount());
        assertEquals(0, resourceSlotMapper.selectById(equipSlotId).getBookedCount());
        assertEquals(0, resourceSlotMapper.selectById(nursingSlotId).getBookedCount());

        // 验证资源关联状态
        List<AppointmentResource> arList =
                appointmentResourceMapper.findByAppointment(appt.getId());
        assertTrue(arList.isEmpty()); // findByAppointment只查BOOKED的
    }

    @Test
    @Order(2)
    @DisplayName("设备停用阻止候补补位")
    void equipmentDisabledBlocksBackfill() throws Exception {
        // 预约
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(9002L);
        bookReq.setPatientName("原预约患者");
        bookReq.setSlotId(slotId);
        bookReq.setExamTypeCode("XRAY");
        Appointment appt = appointmentService.book(bookReq);

        // 候补患者加入
        WaitlistRequest waitReq = new WaitlistRequest();
        waitReq.setPatientId(9003L);
        waitReq.setPatientName("候补患者");
        waitReq.setDoctorId(doctorId);
        waitReq.setDepartmentId(deptId);
        waitReq.setTargetDate(targetDate);
        waitReq.setExamTypeCode("XRAY");
        Waitlist waiter = waitlistService.join(waitReq);

        // 停用设备资源
        ResourceSlot es = resourceSlotMapper.selectById(equipSlotId);
        resourceSlotMapper.casDisable(es.getId(), es.getVersion());

        // 取消预约（触发候补补位）
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(appt.getId());
        cancelReq.setReason("测试取消触发补位");
        appointmentService.cancel(cancelReq);

        // 等待afterCommit回调
        Thread.sleep(3000);

        // 验证候补仍为WAITING（因为设备不可用，补位跳过）
        Waitlist refreshed = waitlistMapper.selectById(waiter.getId());
        assertEquals("WAITING", refreshed.getStatus());

        // 验证号源释放但无人预订
        ScheduleSlot slot = slotMapper.selectById(slotId);
        assertEquals("AVAILABLE", slot.getStatus());
    }

    @Test
    @Order(3)
    @DisplayName("联合预约候补补位成功")
    void jointWaitlistBackfillSuccess() throws Exception {
        // 预约
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(9004L);
        bookReq.setPatientName("原预约患者B");
        bookReq.setSlotId(slotId);
        bookReq.setExamTypeCode("XRAY");
        Appointment appt = appointmentService.book(bookReq);

        // 候补患者加入（带examTypeCode）
        WaitlistRequest waitReq = new WaitlistRequest();
        waitReq.setPatientId(9005L);
        waitReq.setPatientName("候补患者B");
        waitReq.setDoctorId(doctorId);
        waitReq.setDepartmentId(deptId);
        waitReq.setTargetDate(targetDate);
        waitReq.setExamTypeCode("XRAY");
        Waitlist waiter = waitlistService.join(waitReq);

        // 取消预约（触发候补补位）
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(appt.getId());
        cancelReq.setReason("测试候补补位");
        appointmentService.cancel(cancelReq);

        // 等待afterCommit回调
        Thread.sleep(3000);

        // 验证候补补位成功
        Waitlist refreshed = waitlistMapper.selectById(waiter.getId());
        assertEquals("FULFILLED", refreshed.getStatus());
        assertNotNull(refreshed.getAppointmentId());

        // 验证新预约
        Appointment backfillAppt = appointmentMapper.selectById(refreshed.getAppointmentId());
        assertNotNull(backfillAppt);
        assertEquals("CONFIRMED", backfillAppt.getStatus());
        assertEquals("JOINT", backfillAppt.getBookingType());
        assertEquals("XRAY", backfillAppt.getExamTypeCode());
        assertEquals(9005L, backfillAppt.getPatientId());

        // 验证资源已重新预订
        List<AppointmentResource> resources =
                appointmentResourceMapper.findByAppointment(backfillAppt.getId());
        assertEquals(3, resources.size());

        assertEquals(1, resourceSlotMapper.selectById(roomSlotId).getBookedCount());
        assertEquals(1, resourceSlotMapper.selectById(nursingSlotId).getBookedCount());
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
