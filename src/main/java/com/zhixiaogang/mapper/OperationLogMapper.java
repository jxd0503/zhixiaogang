package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus Mapper：operation_log 表基础 CRUD。 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
