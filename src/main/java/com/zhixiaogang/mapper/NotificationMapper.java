package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus Mapper：notifications 表基础 CRUD。 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
