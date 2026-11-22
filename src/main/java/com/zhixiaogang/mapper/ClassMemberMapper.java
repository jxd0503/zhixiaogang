package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.ClassMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis-Plus Mapper：class_member 表基础 CRUD。 */
@Mapper
public interface ClassMemberMapper extends BaseMapper<ClassMember> {

    /** 行锁查询：入班/重申流程在事务内使用，串行化同一 (class_id, student_id) 的并发申请 */
    @Select("SELECT * FROM class_member WHERE class_id = #{classId} AND student_id = #{studentId} FOR UPDATE")
    ClassMember selectOneForUpdate(@Param("classId") Long classId, @Param("studentId") String studentId);
}
