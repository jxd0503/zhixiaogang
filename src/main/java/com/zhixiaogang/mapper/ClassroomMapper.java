package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.Classroom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** MyBatis-Plus Mapper：classroom 表基础 CRUD（含行锁查询）。 */
@Mapper
public interface ClassroomMapper extends BaseMapper<Classroom> {
    List<Map<String, Object>> getAvailableClassrooms();
    List<Map<String, Object>> getClassroomsByBuilding(@Param("building") String building);

    /** P0-1 行锁查询教室（事务内串行化同一教室的并发预约） */
    @Select("SELECT * FROM classroom WHERE id = #{id} FOR UPDATE")
    Classroom selectByIdForUpdate(@Param("id") Long id);

    /** 教室实际存在的楼栋列表，供前端筛选下拉动态渲染，避免硬编码 */
    @Select("SELECT DISTINCT building FROM classroom WHERE building IS NOT NULL AND building <> '' ORDER BY building")
    List<String> listBuildings();
}

