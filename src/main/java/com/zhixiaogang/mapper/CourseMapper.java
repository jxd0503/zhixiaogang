package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.Course;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus Mapper：course 表基础 CRUD。 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {
}
