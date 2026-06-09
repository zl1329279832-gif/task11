package com.clinic.appointment;

import com.clinic.appointment.domain.dto.BookRequest;
import com.clinic.appointment.domain.entity.*;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JointBookingConcurrencyTest {

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
    private Long roomSlotId, equipSlotId, nursingSlotId;
    private final LocalDate targetDate = LocalDate.now().plusDays(4);

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
        dept.setCode("YXCC_" + System.nanoTime());
        dept.setStatus(1);
        departmentMapper.insert(dept);
        deptId = dept.getId();

        Doctor doctor = new Doctor();
        doctor.setName("李医生");
        doctor.setEmployeeNo("DCC_" + System.nanoTime());
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

        // 资源
        Resource room = new Resource();
        room.setName("MRI诊室");
        room.setCode("ROOM_MRI_" + System.nanoTime());
        room.setType("ROOM");
        room.setCapacity(1);
        room.setStatus(1);
        resourceMapper.insert(room);

        Resource equip = new Resource();
        equip.setName("MRI设备");
        equip.setCode("EQUIP_MRI_" + System.nanoTime());
        equip.setType("EQUIPMENT");
        equip.setCapacity(1);
        equip.setStatus(1);
        resourceMapper.insert(equip);

        Resource nursing = new Resource();
        nursing.setName("护理组");
        nursing.setCode("NURSE_CC_" + System.nanoTime());
        nursing.setType("NURSING");
        nursing.setCapacity(1);
        nursing.setStatus(1);
        resourceMapper.insert(nursing);

        roomSlotId = createResourceSlot(room.getId(), "ROOM", targetDate,
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1).getId();
        equipSlotId = createResourceSlot(equip.getId(), "EQUIPMENT", targetDate,
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1).getId();
        nursingSlotId = createResourceSlot(nursing.getId(), "NURSING", targetDate,
                LocalTime.of(8, 0), LocalTime.of(12, 0), 1).getId();

        ExamType examType = new ExamType();
        examType.setName("MRI检查");
        examType.setCode("MRI_SCAN");
        examType.setNeedRoom(1);
        examType.setNeedEquipment(1);
        examType.setNeedNursing(1);
        examType.setStatus(1);
        examTypeMapper.insert(examType);
    }

    @Test
    @Order(1)
    @DisplayName("并发联合预约：10线程竞争同一号源+同一设备，仅1人成功")
    void concurrentJointBookingOnlyOneSucceeds() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long patientId = 5000L + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    BookRequest req = new BookRequest();
                    req.setPatientId(patientId);
                    req.setPatientName("并发患者" + patientId);
                    req.setSlotId(slotId);
                    req.setExamTypeCode("MRI_SCAN");
                    appointmentService.book(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // 只有1人成功
        assertEquals(1, successCount.get());
        assertEquals(9, failCount.get());

        // 验证号源状态
        ScheduleSlot finalSlot = slotMapper.selectById(slotId);
        assertEquals("BOOKED", finalSlot.getStatus());

        // 验证资源状态一致
        ResourceSlot roomAfter = resourceSlotMapper.selectById(roomSlotId);
        assertEquals(1, roomAfter.getBookedCount());
        ResourceSlot equipAfter = resourceSlotMapper.selectById(equipSlotId);
        assertEquals(1, equipAfter.getBookedCount());

        // 验证只有1条appointment_resource
        List<Appointment> appts = appointmentMapper.findByDoctorAndDate(doctorId, targetDate);
        assertEquals(1, appts.size());
        List<AppointmentResource> arList = appointmentResourceMapper.findByAppointment(appts.get(0).getId());
        assertEquals(3, arList.size());
    }

    @Test
    @Order(2)
    @DisplayName("并发联合预约不同号源：各自成功无冲突")
    void concurrentJointBookingDifferentSlots() throws Exception {
        // 创建4个额外号源
        for (int i = 2; i <= 5; i++) {
            ScheduleSlot s = new ScheduleSlot();
            s.setScheduleId(scheduleId);
            s.setDoctorId(doctorId);
            s.setDepartmentId(deptId);
            s.setSlotDate(targetDate);
            s.setSlotTime(LocalTime.of(9, i * 10));
            s.setSlotNo(i);
            s.setStatus("AVAILABLE");
            s.setIsExtra(0);
            s.setVersion(0);
            slotMapper.insert(s);
        }

        // 扩容资源到5
        ResourceSlot rs = resourceSlotMapper.selectById(roomSlotId);
        rs.setCapacity(5);
        resourceSlotMapper.updateById(rs);
        rs = resourceSlotMapper.selectById(equipSlotId);
        rs.setCapacity(5);
        resourceSlotMapper.updateById(rs);
        rs = resourceSlotMapper.selectById(nursingSlotId);
        rs.setCapacity(5);
        resourceSlotMapper.updateById(rs);

        List<ScheduleSlot> allSlots = slotMapper.findAvailable(doctorId, targetDate);
        assertEquals(5, allSlots.size());

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long patientId = 6000L + i;
            final Long targetSlotId = allSlots.get(i).getId();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    BookRequest req = new BookRequest();
                    req.setPatientId(patientId);
                    req.setPatientName("患者" + patientId);
                    req.setSlotId(targetSlotId);
                    req.setExamTypeCode("MRI_SCAN");
                    appointmentService.book(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // unexpected failure
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(5, successCount.get());

        // 验证资源booked_count=5
        ResourceSlot roomAfter = resourceSlotMapper.selectById(roomSlotId);
        assertEquals(5, roomAfter.getBookedCount());
    }

    @Test
    @Order(3)
    @DisplayName("联合预约与普通预约并发：互斥占用医生号源")
    void mixedJointAndSingleBookingConcurrency() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long patientId = 7000L + i;
            final boolean isJoint = i % 2 == 0; // 偶数走联合预约，奇数走普通预约
            executor.submit(() -> {
                try {
                    startLatch.await();
                    BookRequest req = new BookRequest();
                    req.setPatientId(patientId);
                    req.setPatientName("混合患者" + patientId);
                    req.setSlotId(slotId);
                    if (isJoint) {
                        req.setExamTypeCode("MRI_SCAN");
                    }
                    appointmentService.book(req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // expected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // 只有1人成功（无论联合还是普通）
        assertEquals(1, successCount.get());

        ScheduleSlot finalSlot = slotMapper.selectById(slotId);
        assertEquals("BOOKED", finalSlot.getStatus());
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
