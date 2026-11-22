package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.ScheduleAdjustment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis-Plus Mapper：schedule_adjustment 表基础 CRUD。 */
@Mapper
public interface ScheduleAdjustmentMapper extends BaseMapper<ScheduleAdjustment> {

    /** 事务内对调课记录行加排它锁，串行化同一申请的并发 approve/reject/cancel/withdraw */
    @Select("SELECT * FROM schedule_adjustment WHERE id = #{id} FOR UPDATE")
    ScheduleAdjustment selectByIdForUpdate(@Param("id") Long id);
}
