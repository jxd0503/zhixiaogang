package com.zhixiaogang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.UserMapper;
import com.zhixiaogang.util.PasswordUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/** 用户注册、登录校验、资料维护与账号状态处理。 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    public Map<String, Object> login(String studentId, String password) {
        Map<String, Object> result = new HashMap<>();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getStudentId, studentId));
        
        if (user == null) {
            result.put("success", false);
            result.put("message", "账号不存在");
            return result;
        }
        
        if (user.getStatus() != null && user.getStatus() == 0) {
            result.put("success", false);
            result.put("message", "账号已被禁用");
            return result;
        }
        
        if (!PasswordUtils.matches(password, user.getPassword())) {
            result.put("success", false);
            result.put("message", "密码错误");
            return result;
        }

        // 历史 MD5 口令校验通过后自动升级为加盐哈希，老账号无需额外操作
        if (PasswordUtils.needsUpgrade(user.getPassword())) {
            user.setPassword(PasswordUtils.encode(password));
            userMapper.updateById(user);
        }
        
        result.put("success", true);
        result.put("message", "登录成功");
        result.put("user", user);
        return result;
    }

    /**
     * 局部更新姓名/邮箱：只更新这两个字段，避免把会话中的旧 status/role/password 回写数据库。
     * 返回更新后的最新用户；用户不存在或更新失败返回 null。
     */
    public User updateProfile(Long userId, String name, String email) {
        if (userId == null || name == null) {
            return null;
        }
        User patch = new User();
        patch.setId(userId);
        patch.setName(name);
        if (email != null) {
            patch.setEmail(email);
        }
        try {
            if (userMapper.updateById(patch) <= 0) {
                return null;
            }
            return userMapper.selectById(userId);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> register(String studentId, String phone, String password, 
                                          String department, String major) {
        Map<String, Object> result = new HashMap<>();

        // 与注册页前端校验保持一致：密码至少 6 位，手机号必须为大陆 11 位号段
        if (password == null || password.length() < 6) {
            result.put("success", false);
            result.put("message", "密码长度不能少于6位");
            return result;
        }
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            result.put("success", false);
            result.put("message", "请输入正确的手机号");
            return result;
        }

        if ("admin".equals(studentId) || "guyue".equals(studentId)) {
            result.put("success", false);
            result.put("message", "该账号已被系统保留");
            return result;
        }
        
        if (studentId.length() >= 4) {
            try {
                int year = Integer.parseInt(studentId.substring(0, 4));
                int currentYear = java.time.Year.now().getValue();
                if (year > currentYear) {
                    result.put("success", false);
                    result.put("message", "学号年份不能超过当前年份（当前年份：" + currentYear + "）");
                    return result;
                }
            } catch (NumberFormatException e) {
                result.put("success", false);
                result.put("message", "学号格式不正确，前四位应为年份数字");
                return result;
            }
        } else {
            result.put("success", false);
            result.put("message", "学号长度至少为4位");
            return result;
        }
        
        User existingUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getStudentId, studentId));
        if (existingUser != null) {
            result.put("success", false);
            result.put("message", "学号已被注册");
            return result;
        }
        
        User existingPhone = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone));
        if (existingPhone != null) {
            result.put("success", false);
            result.put("message", "手机号已被注册");
            return result;
        }
        
        User user = new User();
        user.setStudentId(studentId);
        user.setPhone(phone);
        user.setPassword(PasswordUtils.encode(password));
        user.setName(studentId);
        user.setRole("student");
        user.setStatus(1);
        user.setDepartment(department);
        user.setMajor(major);
        if (studentId.length() >= 4) {
            user.setGrade(studentId.substring(0, 4));
        }
        
        try {
            userMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发注册同学号/同手机号：唯一键兜底，给友好提示而非 500
            String em = String.valueOf(e.getMessage());
            result.put("success", false);
            result.put("message", em != null && em.toLowerCase().contains("phone") ? "手机号已被注册" : "学号已被注册");
            return result;
        }
        
        result.put("success", true);
        result.put("message", "注册成功");
        return result;
    }

    public User getUserById(Long id) {
        return userMapper.selectById(id);
    }

    public User getUserByStudentId(String studentId) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getStudentId, studentId));
    }

    public boolean updateUser(User user) {
        return userMapper.updateById(user) > 0;
    }
}
