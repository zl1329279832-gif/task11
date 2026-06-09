package com.clinic.appointment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.dto.SuspendRequest;
import com.clinic.appointment.domain.entity.*;
import com.clinic.appointment.domain.enums.AppointmentStatus;
import com.clinic.appointment.domain.enums.SlotStatus;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.DoctorSuspensionService;
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
 * 医生停诊迁移测试
 *
 * 场景：
 * 1. 医生A发布停诊 → 受影响预约自动迁移到同科室医生B
 * 2. 无可迁移医生时 → 降级为批量取消
 * 3. 迁移后验证：原预约RESCHEDULED，新预约CONFIRMED，新号源BOOKED
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SuspensionMigrationTest {

    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DoctorScheduleMapper scheduleMapper;
    @Autowired private ScheduleSlotMapper slotMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private AppointmentService appointmentService;
    @Autowired private DoctorSuspensionService suspensionService;

    private Long deptId;
    private Long doctorAId;
    private Long doctorBId;
    private LocalDate targetDate;

    @BeforeEach
    void setup() {
        // 清理
        appointmentMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);

        targetDate = LocalDate.now().plusDays(3);

        // 科室
        Department dept = new Department();
        dept.setName("骨科");
        dept.setCode("GUKE");
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        // 医生A（将被停诊）
        Doctor doctorA = new Doctor();
        doctorA.setName("李医生");
        doctorA.setEmployeeNo("D010");
        doctorA.setDepartmentId(deptId);
        doctorA.setStatus(1);
        doctorMapper.insert(doctorA);
        doctorAId = doctorA.getId();

        // 医生B（接收迁移）
        Doctor doctorB = new Doctor();
        doctorB.setName("王医生");
        doctorB.setEmployeeNo("D011");
        doctorB.setDepartmentId(deptId);
        doctorB.setStatus(1);
        doctorMapper.insert(doctorB);
        doctorBId = doctorB.getId();

        // 医生A排班+号源
        createScheduleAndSlots(doctorAId, deptId, targetDate);
        // 医生B排班+号源
        createScheduleAndSlots(doctorBId, deptId, targetDate);

        // 给医生A创建3个预约
        List<ScheduleSlot> slotsA = slotMapper.findAvailable(doctorAId, targetDate);
        for (int i = 0; i < 3; i++) {
            BookRequest req = new BookRequest();
            req.setPatientId((long) (3000 + i));
            req.setPatientName("患者" + (3000 + i));
            req.setSlotId(slotsA.get(i).getId());
            appointmentService.book(req);
        }
    }

    private void createScheduleAndSlots(Long doctorId, Long deptId, LocalDate date) {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setDoctorId(doctorId);
        schedule.setDepartmentId(deptId);
        schedule.setScheduleDate(date);
        schedule.setTimePeriod("MORNING");
        schedule.setTotalSlots(5);
        schedule.setBookedSlots(0);
        schedule.setExtraSlots(0);
        schedule.setStatus("NORMAL");
        scheduleMapper.insert(schedule);

        for (int i = 1; i <= 5; i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setScheduleId(schedule.getId());
            slot.setDoctorId(doctorId);
            slot.setDepartmentId(deptId);
            slot.setSlotDate(date);
            slot.setSlotTime(LocalTime.of(8, 30).plusMinutes(i * 10L));
            slot.setSlotNo(i);
            slot.setStatus(SlotStatus.AVAILABLE.name());
            slot.setIsExtra(0);
            slot.setVersion(0);
            slotMapper.insert(slot);
        }
    }

    @Test
    @Order(1)
    @DisplayName("停诊迁移模式：预约迁移到同科室其他医生")
    void suspendWithMigration() {
        SuspendRequest req = new SuspendRequest();
        req.setDoctorId(doctorAId);
        req.setStartDate(targetDate);
        req.setEndDate(targetDate);
        req.setReason("医生A外出进修");
        req.setMigrateType("MIGRATE");

        Map<String, Object> result = suspensionService.suspend(req);

        log.info("停诊迁移结果: {}", result);

        // 验证结果统计
        assertEquals(3, ((Number) result.get("migratedCount")).intValue(),
                "3个预约应全部迁移成功");
        assertEquals(0, ((Number) result.get("cancelledCount")).intValue(),
                "不应有取消");

        // 验证医生A的预约全部变为RESCHEDULED
        List<Appointment> oldAppts = appointmentMapper.findByDoctorAndDate(doctorAId, targetDate);
        for (Appointment a : oldAppts) {
            assertEquals(AppointmentStatus.RESCHEDULED.name(), a.getStatus(),
                    "原预约应为RESCHEDULED");
        }

        // 验证医生B新增了3个预约
        List<Appointment> newAppts = appointmentMapper.findByDoctorAndDate(doctorBId, targetDate);
        assertEquals(3, newAppts.size(), "医生B应有3个新预约");
        for (Appointment a : newAppts) {
            assertEquals(AppointmentStatus.CONFIRMED.name(), a.getStatus(),
                    "新预约应为CONFIRMED");
            assertEquals(doctorBId, a.getDoctorId(), "新预约医生应为B");
        }

        // 验证医生A的号源已释放为SUSPENDED状态
        LambdaQueryWrapper<ScheduleSlot> slotQw = new LambdaQueryWrapper<>();
        slotQw.eq(ScheduleSlot::getDoctorId, doctorAId)
              .eq(ScheduleSlot::getSlotDate, targetDate);
        List<ScheduleSlot> slotsA = slotMapper.selectList(slotQw);
        // 所有号源应为SUSPENDED状态
        for (ScheduleSlot s : slotsA) {
            assertEquals(SlotStatus.SUSPENDED.name(), s.getStatus(),
                    "停诊后号源应为SUSPENDED");
        }
    }

    @Test
    @Order(2)
    @DisplayName("停诊取消模式：无迁移直接批量取消")
    void suspendWithCancel() {
        // 重新设置（清理迁移产生的数据）
        setup();

        SuspendRequest req = new SuspendRequest();
        req.setDoctorId(doctorAId);
        req.setStartDate(targetDate);
        req.setEndDate(targetDate);
        req.setReason("医生A身体不适");
        req.setMigrateType("CANCEL");

        Map<String, Object> result = suspensionService.suspend(req);

        log.info("停诊取消结果: {}", result);

        assertEquals(3, ((Number) result.get("cancelledCount")).intValue(),
                "3个预约应全部取消");
        assertEquals(0, ((Number) result.get("migratedCount")).intValue(),
                "不应有迁移");

        // 验证医生A的预约全部CANCELLED
        List<Appointment> appts = appointmentMapper.findByDoctorAndDate(doctorAId, targetDate);
        for (Appointment a : appts) {
            assertEquals(AppointmentStatus.CANCELLED.name(), a.getStatus());
            assertTrue(a.getCancelReason().contains("停诊"), "取消原因应包含'停诊'");
        }
    }

    @Test
    @Order(3)
    @DisplayName("停诊迁移降级：同科室无其他医生时降级为取消")
    void suspendMigrateDegradedToCancel() {
        // 清理并只创建医生A（无替代医生）
        appointmentMapper.delete(null);
        slotMapper.delete(null);
        scheduleMapper.delete(null);
        doctorMapper.delete(null);
        departmentMapper.delete(null);

        Department dept = new Department();
        dept.setName("特殊科");
        dept.setCode("SPECIAL");
        dept.setStatus(1);
        departmentMapper.insert(dept);

        Doctor soloDoc = new Doctor();
        soloDoc.setName("赵医生");
        soloDoc.setEmployeeNo("D099");
        soloDoc.setDepartmentId(dept.getId());
        soloDoc.setStatus(1);
        doctorMapper.insert(soloDoc);

        createScheduleAndSlots(soloDoc.getId(), dept.getId(), targetDate);

        List<ScheduleSlot> slots = slotMapper.findAvailable(soloDoc.getId(), targetDate);
        BookRequest bookReq = new BookRequest();
        bookReq.setPatientId(9001L);
        bookReq.setPatientName("患者9001");
        bookReq.setSlotId(slots.get(0).getId());
        appointmentService.book(bookReq);

        // 发布停诊（迁移模式）
        SuspendRequest req = new SuspendRequest();
        req.setDoctorId(soloDoc.getId());
        req.setStartDate(targetDate);
        req.setEndDate(targetDate);
        req.setReason("外出");
        req.setMigrateType("MIGRATE");

        Map<String, Object> result = suspensionService.suspend(req);

        log.info("停诊降级结果: {}", result);

        // 应降级为取消
        assertEquals(1, ((Number) result.get("cancelledCount")).intValue(),
                "无可迁移医生时应降级取消");
    }
}
