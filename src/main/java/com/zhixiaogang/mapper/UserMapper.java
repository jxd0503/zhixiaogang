package com.zhixiaogang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhixiaogang.entity.User;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus Mapper：user 表基础 CRUD。 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
