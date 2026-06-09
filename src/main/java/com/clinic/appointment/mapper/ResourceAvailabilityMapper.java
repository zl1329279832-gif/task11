package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.ResourceAvailability;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface ResourceAvailabilityMapper extends BaseMapper<ResourceAvailability> {

    /**
     * CAS占用资源窗口：AVAILABLE → BOOKED
     */
    @Update("UPDATE resource_availability SET status = 'BOOKED', appointment_id = #{appointmentId}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 'AVAILABLE' AND version = #{version}")
    int casBook(@Param("id") Long id,
                @Param("appointmentId") Long appointmentId,
                @Param("version") Integer version);

    /**
     * CAS释放资源窗口：BOOKED → AVAILABLE
     */
    @Update("UPDATE resource_availability SET status = 'AVAILABLE', appointment_id = NULL, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 'BOOKED' AND version = #{version}")
    int casRelease(@Param("id") Long id, @Param("version") Integer version);

    /**
     * CAS封锁资源窗口：AVAILABLE/BOOKED → BLOCKED
     */
    @Update("UPDATE resource_availability SET status = 'BLOCKED', appointment_id = NULL, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status IN ('AVAILABLE','BOOKED') AND version = #{version}")
    int casBlock(@Param("id") Long id, @Param("version") Integer version);

    /**
     * 查找指定资源在目标时间覆盖的可用窗口
     */
    @Select("SELECT * FROM resource_availability " +
            "WHERE resource_type = #{resourceType} AND resource_id = #{resourceId} " +
            "AND avail_date = #{availDate} AND start_time <= #{targetTime} " +
            "AND end_time > #{targetTime} AND status = 'AVAILABLE' ORDER BY start_time")
    List<ResourceAvailability> findAvailableWindow(@Param("resourceType") String resourceType,
                                                    @Param("resourceId") Long resourceId,
                                                    @Param("availDate") LocalDate availDate,
                                                    @Param("targetTime") LocalTime targetTime);

    /**
     * 查找科室中指定类型的可用设备
     */
    @Select("SELECT ra.* FROM resource_availability ra " +
            "INNER JOIN equipment e ON ra.resource_id = e.id " +
            "WHERE ra.resource_type = 'EQUIPMENT' AND e.department_id = #{deptId} " +
            "AND e.equipment_type = #{equipType} AND e.status = 'ACTIVE' " +
            "AND ra.avail_date = #{availDate} AND ra.start_time <= #{targetTime} " +
            "AND ra.end_time > #{targetTime} AND ra.status = 'AVAILABLE' ORDER BY ra.resource_id")
    List<ResourceAvailability> findAvailableEquipmentInDept(@Param("deptId") Long deptId,
                                                             @Param("equipType") String equipType,
                                                             @Param("availDate") LocalDate availDate,
                                                             @Param("targetTime") LocalTime targetTime);

    /**
     * 查找科室中可用的诊室
     */
    @Select("SELECT ra.* FROM resource_availability ra " +
            "INNER JOIN exam_room r ON ra.resource_id = r.id " +
            "WHERE ra.resource_type = 'EXAM_ROOM' AND r.department_id = #{deptId} AND r.status = 'ACTIVE' " +
            "AND ra.avail_date = #{availDate} AND ra.start_time <= #{targetTime} " +
            "AND ra.end_time > #{targetTime} AND ra.status = 'AVAILABLE' ORDER BY ra.resource_id")
    List<ResourceAvailability> findAvailableRoomInDept(@Param("deptId") Long deptId,
                                                        @Param("availDate") LocalDate availDate,
                                                        @Param("targetTime") LocalTime targetTime);

    /**
     * 查找科室中可用的护理人员
     */
    @Select("SELECT ra.* FROM resource_availability ra " +
            "INNER JOIN nursing_staff n ON ra.resource_id = n.id " +
            "WHERE ra.resource_type = 'NURSING_STAFF' AND n.department_id = #{deptId} AND n.status = 1 " +
            "AND ra.avail_date = #{availDate} AND ra.start_time <= #{targetTime} " +
            "AND ra.end_time > #{targetTime} AND ra.status = 'AVAILABLE' ORDER BY ra.resource_id")
    List<ResourceAvailability> findAvailableNursingInDept(@Param("deptId") Long deptId,
                                                           @Param("availDate") LocalDate availDate,
                                                           @Param("targetTime") LocalTime targetTime);

    /**
     * 批量封锁资源（设备停用时）
     */
    @Update("UPDATE resource_availability SET status = 'BLOCKED', update_time = NOW(), " +
            "version = version + 1 WHERE resource_type = #{resourceType} " +
            "AND resource_id = #{resourceId} AND avail_date >= #{fromDate} AND status = 'AVAILABLE'")
    int batchBlock(@Param("resourceType") String resourceType,
                   @Param("resourceId") Long resourceId,
                   @Param("fromDate") LocalDate fromDate);

    /**
     * 查找从指定日期起已预约的资源窗口（设备停用影响分析）
     */
    @Select("SELECT * FROM resource_availability " +
            "WHERE resource_type = #{resourceType} AND resource_id = #{resourceId} " +
            "AND avail_date >= #{fromDate} AND status = 'BOOKED'")
    List<ResourceAvailability> findBookedFrom(@Param("resourceType") String resourceType,
                                               @Param("resourceId") Long resourceId,
                                               @Param("fromDate") LocalDate fromDate);
}
