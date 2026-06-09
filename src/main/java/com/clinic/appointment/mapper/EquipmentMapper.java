package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.Equipment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EquipmentMapper extends BaseMapper<Equipment> {

    @Select("SELECT * FROM equipment WHERE department_id = #{deptId} " +
            "AND equipment_type = #{type} AND status = 'ACTIVE'")
    List<Equipment> findActiveByDeptAndType(@Param("deptId") Long deptId,
                                             @Param("type") String equipmentType);

    @Select("SELECT * FROM equipment WHERE department_id = #{deptId} AND status = 'ACTIVE'")
    List<Equipment> findActiveByDepartment(@Param("deptId") Long deptId);
}
