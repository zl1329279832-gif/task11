package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.Resource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {

    @Select("SELECT * FROM resource WHERE code = #{code} AND status = 1")
    Resource findByCode(@Param("code") String code);

    @Select("SELECT * FROM resource WHERE type = #{type} AND status = 1")
    List<Resource> findActiveByType(@Param("type") String type);

    @Select("SELECT * FROM resource WHERE type = #{type} AND department_id = #{deptId} AND status = 1")
    List<Resource> findActiveByTypeAndDept(@Param("type") String type, @Param("deptId") Long deptId);
}
