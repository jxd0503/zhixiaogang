package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.ChatLog;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus Mapper：chat_log 表基础 CRUD。 */
@Mapper
public interface ChatLogMapper extends BaseMapper<ChatLog> {
}
