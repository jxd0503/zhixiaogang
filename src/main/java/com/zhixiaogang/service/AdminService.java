package com.zhixiaogang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ClassroomReservationMapper;
import com.zhixiaogang.mapper.ScheduleAdjustmentMapper;
import com.zhixiaogang.mapper.UserMapper;
import com.zhixiaogang.util.PasswordUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理端入口业务：管理员登录校验（含失败锁定与历史口令升级）与首页概览统计。
 * 结果的 _auditRole/_auditDetail/_auditResult 仅供 Controller 记录操作日志，返回前会被移除。
 */
@Service
public class AdminService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private ClassroomReservationMapper reservationMapper;

    @Autowired
    private ScheduleAdjustmentMapper scheduleAdjustmentMapper;

    @Autowired
    private LoginAttemptService loginAttemptService;

    public Map<String, Object> login(String username, String password) {
        Map<String, Object> result = new HashMap<>();

        String attemptKey = "admin:" + username;
        if (username != null && loginAttemptService.isLocked(attemptKey)) {
            result.put("success", false);
            result.put("message", "登录失败次数过多，请稍后再试");
            return result;
        }

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getStudentId, username));

        if (user == null) {
            loginAttemptService.recordFailure(attemptKey);
            audit(result, "admin", "管理端登录", "failed: 账号或密码错误");
            result.put("success", false);
            result.put("message", "账号或密码错误");
            return result;
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            loginAttemptService.recordFailure(attemptKey);
            audit(result, user.getRole(), "管理端登录", "failed: 账号已被禁用");
            result.put("success", false);
            result.put("message", "账号已被禁用");
            return result;
        }

        if (!"admin".equals(user.getRole())) {
            loginAttemptService.recordFailure(attemptKey);
            audit(result, user.getRole(), "管理端登录（角色不符）", "failed: 该账号不是管理员账号");
            result.put("success", false);
            result.put("message", "该账号不是管理员账号，请使用学生端登录");
            return result;
        }

        if (!PasswordUtils.matches(password, user.getPassword())) {
            loginAttemptService.recordFailure(attemptKey);
            audit(result, user.getRole(), "管理端登录", "failed: 账号或密码错误");
            result.put("success", false);
            result.put("message", "账号或密码错误");
            return result;
        }

        // 历史 MD5 口令校验通过后自动升级为加盐哈希
        if (PasswordUtils.needsUpgrade(user.getPassword())) {
            user.setPassword(PasswordUtils.encode(password));
            userMapper.updateById(user);
        }

        loginAttemptService.reset(attemptKey);
        result.put("success", true);
        result.put("message", "登录成功");
        result.put("user", user);
        return result;
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> result = new HashMap<>();

        long totalUsers = userMapper.selectCount(null);
        long totalClassrooms = classroomMapper.selectCount(null);
        long pendingReservations = reservationMapper.selectCount(
                new LambdaQueryWrapper<ClassroomReservation>().eq(ClassroomReservation::getStatus, "pending"));
        long pendingAdjustments = scheduleAdjustmentMapper.selectCount(
                new LambdaQueryWrapper<ScheduleAdjustment>().eq(ScheduleAdjustment::getStatus, "pending"));

        result.put("success", true);
        result.put("totalUsers", totalUsers);
        result.put("totalClassrooms", totalClassrooms);
        result.put("pendingReservations", pendingReservations);
        result.put("pendingAdjustments", pendingAdjustments);
        return result;
    }

    private void audit(Map<String, Object> result, String role, String detail, String auditResult) {
        result.put("_auditRole", role);
        result.put("_auditDetail", detail);
        result.put("_auditResult", auditResult);
    }
}
