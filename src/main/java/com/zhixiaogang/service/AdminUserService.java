package com.zhixiaogang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 管理端用户管理：列表查询、资料修改与删除。 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /** 系统保留账号，不允许删除 */
    private static final List<String> RESERVED_ACCOUNTS = List.of("admin", "guyue");

    @Autowired
    private UserMapper userMapper;

    /** 用户列表（排除系统保留账号），分页返回；keyword 匹配学号或姓名，其余条件精确匹配且可组合 */
    public Map<String, Object> listUsers(String keyword, String department, String grade, String major,
                                         String role, Integer page, Integer size) {
        Map<String, Object> result = new HashMap<>();

        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 100);

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(User::getStudentId, "admin").ne(User::getStudentId, "guyue");
        String kw = trimToNull(keyword);
        if (kw != null) {
            wrapper.and(w -> w.like(User::getStudentId, kw).or().like(User::getName, kw));
        }
        if (trimToNull(department) != null) wrapper.eq(User::getDepartment, department.trim());
        if (trimToNull(grade) != null) wrapper.likeRight(User::getGrade, grade.trim());
        if (trimToNull(major) != null) wrapper.eq(User::getMajor, major.trim());
        if (trimToNull(role) != null) wrapper.eq(User::getRole, role.trim());

        Long total = userMapper.selectCount(wrapper);
        List<User> users = userMapper.selectList(wrapper
                .orderByAsc(User::getId)
                .last("LIMIT " + s + " OFFSET " + ((long) (p - 1) * s)));

        result.put("success", true);
        result.put("users", users);
        result.put("page", p);
        result.put("size", s);
        result.put("total", total == null ? 0 : total);
        return result;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 删除用户；系统保留账号直接拒绝 */
    public Map<String, Object> deleteUser(String studentId) {
        Map<String, Object> result = new HashMap<>();

        if (RESERVED_ACCOUNTS.contains(studentId)) {
            result.put("success", false);
            result.put("message", "管理员账号不能删除");
            return result;
        }

        try {
            int deleted = userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getStudentId, studentId));
            if (deleted == 0) {
                result.put("success", false);
                result.put("message", "用户不存在");
                return result;
            }
            result.put("success", true);
            result.put("message", "删除成功");
        } catch (Exception e) {
            log.error("删除用户失败: studentId={}", studentId, e);
            result.put("success", false);
            result.put("message", "删除失败，请稍后重试");
        }
        return result;
    }

    /** 修改用户资料；仅更新请求中出现的字段 */
    public Map<String, Object> updateUser(String studentId, Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getStudentId, studentId));
        if (user == null) {
            result.put("success", false);
            result.put("message", "用户不存在");
            return result;
        }

        if (body.get("name") != null) user.setName((String) body.get("name"));
        if (body.get("phone") != null) user.setPhone((String) body.get("phone"));
        if (body.get("email") != null) user.setEmail((String) body.get("email"));
        if (body.get("department") != null) user.setDepartment((String) body.get("department"));
        if (body.get("major") != null) user.setMajor((String) body.get("major"));
        if (body.get("grade") != null) user.setGrade((String) body.get("grade"));
        if (body.get("status") != null) user.setStatus(Integer.valueOf(body.get("status").toString()));

        try {
            userMapper.updateById(user);
            result.put("success", true);
            result.put("message", "修改成功");
        } catch (Exception e) {
            log.error("修改用户失败: studentId={}", studentId, e);
            result.put("success", false);
            result.put("message", "修改失败，请稍后重试");
        }
        // 供 Controller 记录操作日志用，返回前移除
        result.put("_userName", user.getName());
        return result;
    }
}
