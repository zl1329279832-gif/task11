package com.clinic.appointment;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.CancelRequest;
import com.clinic.appointment.domain.dto.WaitlistRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AppointmentService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 候补补位测试
 *
 * 场景：
 * 1. 号源约满 → 患者加入候补 → 有人取消 → 候补自动补位
 * 2. 多人候补 → 取消一个号源 → 只有队列第一个人补位成功
 * 3. 候补过期 → 自动清理
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WaitlistBackfillTest {

    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DoctorScheduleMapper scheduleMapper;
    @Autowired private ScheduleSlotMapper slotMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private WaitlistMapper waitlistMapper;
    @Autowired private AppointmentService appointmentService;
    @Autowired private WaitlistService waitlistService;

    private Long deptId;
    private Long doctorId;
    private LocalDate targetDate;

    @BeforeEach
    void setup() {
        // 清理
        waitlistMapper.delete(null);
        appointmentMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);

        targetDate = LocalDate.now().plusDays(2);

        // 科室
        Department dept = new Department();
        dept.setName("眼科");
        dept.setCode("YANKE");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        // 医生
        Doctor doctor = new Doctor();
        doctor.setName("刘医生");
        doctor.setEmployeeNo("D020");
        doctor.setDepartmentId(deptId);
        doctor.setStatus(1);
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();

        // 排班（只有2个号源，模拟紧俏场景）
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctorId(doctorId);
        schedule.setDepartmentId(deptId);
        schedule.setScheduleDate(targetDate);
        schedule.setTimePeriod("MORNING");
        schedule.setTotalSlots(2);
        schedule.setBookedSlots(0);
        schedule.setExtraSlots(0);
        schedule.setStatus("NORMAL");
        scheduleMapper.insert(schedule);

        for (int i = 1; i <= 2; i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setScheduleId(schedule.getId());
            slot.setDoctorId(doctorId);
            slot.setDepartmentId(deptId);
            slot.setSlotDate(targetDate);
            slot.setSlotTime(LocalTime.of(9, 0).plusMinutes(i * 15L));
            slot.setSlotNo(i);
            slot.setStatus(SlotStatus.AVAILABLE.name());
            slot.setIsExtra(0);
            slot.setVersion(0);
            slotMapper.insert(slot);
        }
    }

    @Test
    @Order(1)
    @DisplayName("候补补位：取消预约后自动补位第一个候补患者")
    void backfillOnCancel() {
        // 1. 两个号源全部约满
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        assertEquals(2, slots.size(), "应有2个可用号源");

        Appointment appt1 = bookSlot(slots.get(0).getId(), 4001L, "患者4001");
        Appointment appt2 = bookSlot(slots.get(1).getId(), 4002L, "患者4002");

        // 验证号源全部占满
        List<ScheduleSlot> available = slotMapper.findAvailable(doctorId, targetDate);
        assertEquals(0, available.size(), "号源应全部约满");

        // 2. 3个患者加入候补
        Waitlist w1 = joinWaitlist(4003L, "患者4003");
        Waitlist w2 = joinWaitlist(4004L, "患者4004");
        Waitlist w3 = joinWaitlist(4005L, "患者4005");

        assertEquals("WAITING", w1.getStatus());
        assertEquals("WAITING", w2.getStatus());
        assertEquals("WAITING", w3.getStatus());

        // 3. 患者4001取消预约 → 释放1个号源 → 自动补位患者4003
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setAppointmentId(appt1.getId());
        cancelReq.setReason("临时有事");
        appointmentService.cancel(cancelReq);

        // 验证候补补位结果
        Waitlist updatedW1 = waitlistMapper.selectById(w1.getId());
        Waitlist updatedW2 = waitlistMapper.selectById(w2.getId());
        Waitlist updatedW3 = waitlistMapper.selectById(w3.getId());

        assertEquals("FULFILLED", updatedW1.getStatus(),
                "候补第1人应补位成功");
        assertNotNull(updatedW1.getAppointmentId(), "应有预约ID");
        assertEquals("WAITING", updatedW2.getStatus(),
                "候补第2人仍等待（只释放了1个号源）");
        assertEquals("WAITING", updatedW3.getStatus(),
                "候补第3人仍等待");

        // 验证新预约已创建
        Appointment backfilled = appointmentMapper.selectById(updatedW1.getAppointmentId());
        assertNotNull(backfilled, "补位预约应存在");
        assertEquals(AppointmentStatus.CONFIRMED.name(), backfilled.getStatus());
        assertEquals(4003L, backfilled.getPatientId(), "补位患者应为4003");
        assertEquals(doctorId, backfilled.getDoctorId());
        assertEquals(targetDate, backfilled.getSlotDate());

        log.info("候补补位成功: 患者4003补到患者4001取消的号源");
    }

    @Test
    @Order(2)
    @DisplayName("多次取消 → 按候补顺序依次补位")
    void sequentialBackfill() {
        // 约满
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt1 = bookSlot(slots.get(0).getId(), 5001L, "患者5001");
        Appointment appt2 = bookSlot(slots.get(1).getId(), 5002L, "患者5002");

        // 加入候补
        Waitlist w1 = joinWaitlist(5003L, "患者5003");
        Waitlist w2 = joinWaitlist(5004L, "患者5004");

        // 取消第一个 → 补位w1
        appointmentService.cancel(new CancelRequest() {{ setAppointmentId(appt1.getId()); setReason("取消"); }});

        Waitlist uw1 = waitlistMapper.selectById(w1.getId());
        assertEquals("FULFILLED", uw1.getStatus(), "w1应补位成功");

        // 取消第二个 → 补位w2
        appointmentService.cancel(new CancelRequest() {{ setAppointmentId(appt2.getId()); setReason("取消"); }});

        Waitlist uw2 = waitlistMapper.selectById(w2.getId());
        assertEquals("FULFILLED", uw2.getStatus(), "w2应补位成功");

        log.info("顺序补位成功: w1和w2依次补位");
    }

    @Test
    @Order(3)
    @DisplayName("候补去重：同一患者不能重复加入")
    void duplicateWaitlistRejected() {
        joinWaitlist(6001L, "患者6001");

        assertThrows(Exception.class, () -> joinWaitlist(6001L, "患者6001"),
                "重复加入候补应抛异常");
    }

    @Test
    @Order(4)
    @DisplayName("手动触发补位：有可用号源+有候补时直接补位")
    void manualTriggerBackfill() {
        // 约满
        List<ScheduleSlot> slots = slotMapper.findAvailable(doctorId, targetDate);
        Appointment appt1 = bookSlot(slots.get(0).getId(), 7001L, "患者7001");
        bookSlot(slots.get(1).getId(), 7002L, "患者7002");

        // 加入候补
        Waitlist w1 = joinWaitlist(7003L, "患者7003");

        // 手动释放号源（模拟管理后台操作）
        ScheduleSlot slot = slotMapper.selectById(appt1.getSlotId());
        slotMapper.casRelease(slot.getId(), slot.getVersion());
        // 更新预约状态
        appt1.setStatus("CANCELLED");
        appointmentMapper.updateById(appt1);

        // 手动触发补位
        waitlistService.triggerBackfill(doctorId, targetDate, null);

        // 验证
        Waitlist uw1 = waitlistMapper.selectById(w1.getId());
        assertEquals("FULFILLED", uw1.getStatus(), "手动触发后应补位成功");

        log.info("手动触发补位成功");
    }

    // ── Helper ──────────────────────────────────

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
