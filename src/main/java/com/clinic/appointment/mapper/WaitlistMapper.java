package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.Waitlist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface WaitlistMapper extends BaseMapper<Waitlist> {

    /**
     * 查找指定医生、日期的候补队列（按ID升序 = 严格FIFO进入顺序）
     */
    @Select("SELECT * FROM waitlist WHERE doctor_id = #{doctorId} " +
            "AND target_date = #{targetDate} AND status = 'WAITING' " +
            "ORDER BY id ASC")
    List<Waitlist> findWaiting(@Param("doctorId") Long doctorId,
                                @Param("targetDate") LocalDate targetDate);

    /**
     * 查找指定科室、日期的候补队列（按ID升序 = 严格FIFO）
     */
    @Select("SELECT * FROM waitlist WHERE department_id = #{deptId} " +
            "AND target_date = #{targetDate} AND status = 'WAITING' " +
            "ORDER BY id ASC")
    List<Waitlist> findWaitingByDept(@Param("deptId") Long deptId,
                                      @Param("targetDate") LocalDate targetDate);
}
