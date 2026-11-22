package com.zhixiaogang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.ClassInfo;
import com.zhixiaogang.entity.ClassMember;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ClassInfoMapper;
import com.zhixiaogang.mapper.ClassMemberMapper;
import com.zhixiaogang.mapper.UserMapper;
import com.zhixiaogang.service.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学生端班级：班级列表、加入/退出状态机与我的班级。
 * 返回结果中的 logAction/logDetail 仅供 Controller 记录操作日志，返回前会被移除。
 */
@Service
public class StudentClassService {

    @Autowired
    private ClassInfoMapper classInfoMapper;

    @Autowired
    private ClassMemberMapper classMemberMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private NotificationService notificationService;

    /** 可加入的班级列表（分页，标注当前学生在该班的状态） */
    public Map<String, Object> listClasses(String studentId, Integer page, Integer size, String keyword) {
        Map<String, Object> result = new HashMap<>();

        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 8 : Math.min(size, 100);
        String kw = keyword == null || keyword.trim().isEmpty() ? null : keyword.trim();

        LambdaQueryWrapper<ClassInfo> query = new LambdaQueryWrapper<>();
        if (kw != null) {
            List<String> teacherIdsByKw = userMapper.selectList(new LambdaQueryWrapper<User>()
                            .like(User::getName, kw))
                    .stream().map(User::getStudentId).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            query.and(w -> {
                w.like(ClassInfo::getClassName, kw)
                        .or().like(ClassInfo::getDepartment, kw)
                        .or().like(ClassInfo::getGrade, kw);
                if (!teacherIdsByKw.isEmpty()) {
                    w.or().in(ClassInfo::getTeacherId, teacherIdsByKw);
                }
            });
        }
        Long total = classInfoMapper.selectCount(query);
        List<ClassInfo> classes = classInfoMapper.selectList(query
                .orderByAsc(ClassInfo::getId)
                .last("LIMIT " + s + " OFFSET " + ((long) (p - 1) * s)));

        // 批量取教师姓名与本人入班状态，避免逐班查询造成 N+1
        List<String> teacherIds = classes.stream()
                .map(ClassInfo::getTeacherId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> teacherNames = new HashMap<>();
        if (!teacherIds.isEmpty()) {
            for (User teacher : userMapper.selectList(
                    new LambdaQueryWrapper<User>().in(User::getStudentId, teacherIds))) {
                teacherNames.put(teacher.getStudentId(), teacher.getName());
            }
        }
        Map<Long, String> myStatusMap = new HashMap<>();
        if (!classes.isEmpty()) {
            for (ClassMember cm : classMemberMapper.selectList(new LambdaQueryWrapper<ClassMember>()
                    .eq(ClassMember::getStudentId, studentId)
                    .in(ClassMember::getClassId, classes.stream().map(ClassInfo::getId).collect(Collectors.toList())))) {
                myStatusMap.put(cm.getClassId(), cm.getStatus());
            }
        }

        List<Map<String, Object>> data = new ArrayList<>();
        for (ClassInfo ci : classes) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", ci.getId());
            item.put("className", ci.getClassName());
            item.put("grade", ci.getGrade());
            item.put("department", ci.getDepartment());
            item.put("teacherId", ci.getTeacherId());
            item.put("teacherName", ci.getTeacherId() == null ? null : teacherNames.get(ci.getTeacherId()));
            item.put("myStatus", myStatusMap.get(ci.getId()));

            data.add(item);
        }

        result.put("success", true);
        result.put("message", "查询成功");
        result.put("data", data);
        result.put("total", total == null ? 0 : total);
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    /**
     * 提交加入申请。
     * 并发安全：事务内对 (class_id, student_id) 行加锁，双击/并发时后者得到"已申请或已加入"而非 500。
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> joinClass(User user, long classId) {
        Map<String, Object> result = new HashMap<>();
        if (classInfoMapper.selectById(classId) == null) {
            result.put("success", false);
            result.put("message", "班级不存在或已被删除");
            return result;
        }

        // 先普通查询：存在记录时才加行锁重读（避免对不存在记录的间隙锁在并发 insert 时互等成死锁）
        ClassMember existing = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getClassId, classId)
                .eq(ClassMember::getStudentId, user.getStudentId()));
        if (existing != null) {
            existing = classMemberMapper.selectOneForUpdate(classId, user.getStudentId());
        }
        if (existing != null) {
            if ("withdrawn".equals(existing.getStatus()) || "withdraw_pending".equals(existing.getStatus())
                    || "rejected".equals(existing.getStatus())) {
                // 已退班 / 退班审核中 / 入班被拒的学生可以重新申请（复用原记录，保持幂等去重口径）
                existing.setStatus("pending");
                classMemberMapper.updateById(existing);
                notificationService.onClassApplicationSubmitted(classId, user.getName(), false, existing.getId());
                result.put("success", true);
                result.put("message", "申请已提交，等待教师审核");
                result.put("logAction", "apply");
                result.put("logDetail", "申请加入班级 #" + classId + "：" + user.getName() + "（重新申请）");
                return result;
            }
            result.put("success", false);
            result.put("message", "已申请或已加入");
            result.put("logAction", "apply");
            result.put("logDetail", "申请加入班级 #" + classId);
            return result;
        }

        // 每人只能加入一个班级：已有生效中的加入/待审核/退班待审核记录时禁止再申请其他班级
        ClassMember active = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getStudentId, user.getStudentId())
                .in(ClassMember::getStatus, "approved", "pending", "withdraw_pending")
                .last("LIMIT 1"));
        if (active != null) {
            result.put("success", false);
            if ("withdraw_pending".equals(active.getStatus())) {
                result.put("message", "您的退班申请正在审核中，待教师审核通过后方可申请其他班级");
            } else if ("pending".equals(active.getStatus())) {
                result.put("message", "您已有待审核的加入申请，请先取消后再申请其他班级");
            } else {
                result.put("message", "每人只能加入一个班级，请先退出当前班级后再申请其他班级");
            }
            result.put("logAction", "apply");
            result.put("logDetail", "申请加入班级 #" + classId);
            return result;
        }

        ClassMember cm = new ClassMember();
        cm.setClassId(classId);
        cm.setStudentId(user.getStudentId());
        cm.setStatus("pending");
        try {
            classMemberMapper.insert(cm);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发双击：另一请求已插入同班记录（uk_class_student 兜底），按已申请处理而非 500
            result.put("success", false);
            result.put("message", "已申请或已加入");
            result.put("logAction", "apply");
            result.put("logDetail", "申请加入班级 #" + classId);
            return result;
        }
        notificationService.onClassApplicationSubmitted(classId, user.getName(), false, cm.getId());

        result.put("success", true);
        result.put("message", "申请已提交，等待教师审核");
        result.put("logAction", "apply");
        result.put("logDetail", "申请加入班级 #" + classId + "：" + user.getName());
        return result;
    }

    /** 取消加入申请（仅待审核状态可取消） */
    public Map<String, Object> cancelJoin(String studentId, String studentName, Long classId) {
        Map<String, Object> result = new HashMap<>();

        ClassMember cm = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getClassId, classId)
                .eq(ClassMember::getStudentId, studentId));
        if (cm == null || !"pending".equals(cm.getStatus())) {
            result.put("success", false);
            result.put("message", "没有待审核的加入申请");
            result.put("logAction", "cancel");
            result.put("logDetail", "取消加入班级 #" + classId + " 的申请");
            return result;
        }

        classMemberMapper.deleteById(cm.getId());
        result.put("success", true);
        result.put("message", "已取消申请");
        result.put("logAction", "cancel");
        result.put("logDetail", "取消加入班级 #" + classId + " 的申请：" + studentName);
        return result;
    }

    /** 提交退班申请 */
    public Map<String, Object> withdraw(String studentId, String studentName, Long classId) {
        Map<String, Object> result = new HashMap<>();

        ClassMember cm = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getClassId, classId)
                .eq(ClassMember::getStudentId, studentId));
        if (cm == null || !"approved".equals(cm.getStatus())) {
            result.put("success", false);
            result.put("message", "未加入该班级，无法退班");
            result.put("logAction", "apply");
            result.put("logDetail", "申请退出班级 #" + classId);
            return result;
        }

        cm.setStatus("withdraw_pending");
        classMemberMapper.updateById(cm);
        notificationService.onClassApplicationSubmitted(classId, studentName, true, cm.getId());

        result.put("success", true);
        result.put("message", "退班申请已提交，等待教师审核");
        result.put("logAction", "apply");
        result.put("logDetail", "申请退出班级 #" + classId + "：" + studentName);
        return result;
    }

    /** 取消退班申请（仅退班待审核状态可取消，状态恢复为已加入） */
    public Map<String, Object> cancelWithdraw(String studentId, String studentName, Long classId) {
        Map<String, Object> result = new HashMap<>();

        ClassMember cm = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getClassId, classId)
                .eq(ClassMember::getStudentId, studentId));
        if (cm == null || !"withdraw_pending".equals(cm.getStatus())) {
            result.put("success", false);
            result.put("message", "没有待审核的退班申请");
            result.put("logAction", "cancel");
            result.put("logDetail", "取消退出班级 #" + classId + " 的申请");
            return result;
        }

        cm.setStatus("approved");
        classMemberMapper.updateById(cm);
        result.put("success", true);
        result.put("message", "已取消退班申请");
        result.put("logAction", "cancel");
        result.put("logDetail", "取消退出班级 #" + classId + " 的申请：" + studentName);
        return result;
    }

    /** 我的班级信息（当前学生 status=approved/withdraw_pending 的班级） */
    public Map<String, Object> getMyClass(String studentId) {
        Map<String, Object> result = new HashMap<>();

        ClassMember cm = classMemberMapper.selectOne(new LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getStudentId, studentId)
                .in(ClassMember::getStatus, "approved", "withdraw_pending"));
        if (cm == null) {
            result.put("success", true);
            result.put("message", "未加入班级");
            result.put("data", null);
            return result;
        }

        ClassInfo ci = classInfoMapper.selectById(cm.getClassId());
        if (ci == null) {
            result.put("success", true);
            result.put("message", "未加入班级");
            result.put("data", null);
            return result;
        }

        User teacher = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getStudentId, ci.getTeacherId()));

        Map<String, Object> data = new HashMap<>();
        data.put("classId", ci.getId());
        data.put("className", ci.getClassName());
        data.put("grade", ci.getGrade());
        data.put("department", ci.getDepartment());
        data.put("teacherId", ci.getTeacherId());
        data.put("teacherName", teacher != null ? teacher.getName() : null);
        data.put("withdrawPending", "withdraw_pending".equals(cm.getStatus()));

        result.put("success", true);
        result.put("message", "查询成功");
        result.put("data", data);
        return result;
    }
}
