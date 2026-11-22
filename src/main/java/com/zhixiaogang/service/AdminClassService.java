package com.zhixiaogang.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.zhixiaogang.service.AdminClassSupport.*;

/**
 * 管理端班级管理服务（对外门面）。
 * 班级 CRUD / 成员管理 / 教师账号回填在本类；课表维护委托 {@link AdminClassScheduleService}，
 * AI 文字/识图导入委托 {@link AdminClassImportService}。对外 public 方法签名保持不变。
 */
@Service
public class AdminClassService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminClassScheduleService adminClassScheduleService;

    @Autowired
    private AdminClassImportService adminClassImportService;

    // ==================== 班级管理 ====================

    /** 班级列表（含学生数、课表条目数统计），分页返回 */
    public Map<String, Object> listClasses(Integer page, Integer size) {
        Map<String, Object> result = new HashMap<>();
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 100);
        try {
            Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM class_info", Integer.class);
            String sql = "SELECT ci.id, ci.class_name, ci.grade, ci.department, ci.teacher_id, " +
                    "u.name AS teacher_name, " +
                    "(SELECT COUNT(*) FROM class_member cm WHERE cm.class_id = ci.id " +
                    "  AND cm.status IN ('approved','withdraw_pending')) AS student_count, " +
                    "(SELECT COUNT(*) FROM schedule s WHERE s.class_id = ci.id) AS schedule_count " +
                    "FROM class_info ci LEFT JOIN user u ON ci.teacher_id = u.student_id " +
                    "ORDER BY ci.id LIMIT ? OFFSET ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, s, (long) (p - 1) * s);
            List<Map<String, Object>> classes = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("className", row.get("class_name"));
                item.put("grade", row.get("grade"));
                item.put("department", row.get("department"));
                item.put("teacherId", row.get("teacher_id"));
                item.put("teacherName", row.get("teacher_name"));
                item.put("studentCount", safeCount(row.get("student_count")));
                item.put("scheduleCount", safeCount(row.get("schedule_count")));
                classes.add(item);
            }
            result.put("success", true);
            result.put("classes", classes);
            result.put("page", p);
            result.put("size", s);
            result.put("total", total == null ? 0 : total);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "班级列表加载失败：" + e.getMessage());
        }
        return result;
    }

    /** 添加班级：班级名必填且唯一，管理教师须为教师账号 */
    public Map<String, Object> addClass(String className, String grade, String department, String teacherId) {
        Map<String, Object> check = validateClassName(null, className);
        if (check != null) return check;
        Map<String, Object> teacherCheck = validateTeacherId(teacherId);
        if (teacherCheck != null) return teacherCheck;

        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO class_info (class_name, grade, department, teacher_id) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, className.trim());
                ps.setObject(2, emptyToNull(grade));
                ps.setObject(3, emptyToNull(department));
                ps.setObject(4, emptyToNull(teacherId));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            Long newId = key == null ? null : key.longValue();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "班级添加成功");
            result.put("classId", newId);
            return result;
        } catch (Exception e) {
            return fail("班级添加失败：" + rootMessage(e));
        }
    }

    /** 修改班级 */
    public Map<String, Object> updateClass(Long id, String className, String grade, String department, String teacherId) {
        Map<String, Object> check = validateClassName(id, className);
        if (check != null) return check;
        Map<String, Object> teacherCheck = validateTeacherId(teacherId);
        if (teacherCheck != null) return teacherCheck;

        try {
            int rows = jdbcTemplate.update(
                    "UPDATE class_info SET class_name = ?, grade = ?, department = ?, teacher_id = ? WHERE id = ?",
                    className.trim(), emptyToNull(grade), emptyToNull(department), emptyToNull(teacherId), id);
            if (rows == 0) {
                return fail("班级不存在");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "班级修改成功");
            return result;
        } catch (Exception e) {
            return fail("班级修改失败：" + rootMessage(e));
        }
    }

    /** 删除班级：联动删除 class_member 与 schedule 关联行，再删班级（事务保证一致） */
    @Transactional
    public Map<String, Object> deleteClass(Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM class_info WHERE id = ?", Integer.class, id);
            if (count == null || count == 0) {
                return fail("班级不存在");
            }
            int scheduleRows = jdbcTemplate.update("DELETE FROM schedule WHERE class_id = ?", id);
            int memberRows = jdbcTemplate.update("DELETE FROM class_member WHERE class_id = ?", id);
            jdbcTemplate.update("DELETE FROM class_info WHERE id = ?", id);
            result.put("success", true);
            result.put("message", "班级已删除（联动清理课表 " + scheduleRows + " 条、成员关系 " + memberRows + " 条）");
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("班级删除失败：" + rootMessage(e));
        }
    }

    /** 班级详情：班级信息 + 课表 + 学生（含状态，供前端按状态筛选） */
    public Map<String, Object> getClassDetail(Long classId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> cls = queryClass(jdbcTemplate, classId);
            if (cls == null) {
                return fail("班级不存在");
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", cls.get("id"));
            detail.put("className", cls.get("class_name"));
            detail.put("grade", cls.get("grade"));
            detail.put("department", cls.get("department"));
            detail.put("teacherId", cls.get("teacher_id"));
            detail.put("teacherName", cls.get("teacher_name"));

            Map<String, Object> scheduleData = listSchedule(classId);
            detail.put("schedule", scheduleData.get("schedule"));

            // 已退出（withdrawn）的成员不再展示，仅保留在班 / 申请中 / 退班待审核
            String studentSql = "SELECT cm.id, cm.student_id, cm.status, u.name, u.department, u.major " +
                    "FROM class_member cm JOIN user u ON cm.student_id = u.student_id " +
                    "WHERE cm.class_id = ? AND cm.status <> 'withdrawn' ORDER BY cm.status, cm.student_id";
            List<Map<String, Object>> students = jdbcTemplate.queryForList(studentSql, classId);
            detail.put("students", students);

            result.put("success", true);
            result.put("classInfo", detail);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "班级详情加载失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 可添加学生列表：全部在读学生 + 本班状态 / 其他班归属。
     * 遵循「每人只能加入一个班级」：已在本班、已申请本班、退班审核中、已在其他班的学生标记为不可选。
     */
    public Map<String, Object> listAddableStudents(Long classId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> cls = queryClass(jdbcTemplate, classId);
            if (cls == null) {
                return fail("班级不存在");
            }
            String sql = "SELECT u.student_id, u.name, u.department, u.major, u.grade, " +
                    "t.status AS this_status, o.class_name AS other_class_name " +
                    "FROM user u " +
                    "LEFT JOIN class_member t ON t.student_id = u.student_id AND t.class_id = ? " +
                    "LEFT JOIN (SELECT cm.student_id, ci.class_name FROM class_member cm " +
                    "           JOIN class_info ci ON ci.id = cm.class_id " +
                    "           WHERE cm.class_id <> ? AND cm.status IN ('approved','pending','withdraw_pending')) o " +
                    "       ON o.student_id = u.student_id " +
                    "WHERE u.role = 'student' AND u.status = 1 " +
                    "ORDER BY u.student_id";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, classId, classId);
            List<Map<String, Object>> students = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String thisStatus = getStr(row, "this_status");
                String otherClass = getStr(row, "other_class_name");
                String reason = null;
                boolean eligible = false;
                if ("approved".equals(thisStatus)) {
                    reason = "已在本班";
                } else if ("pending".equals(thisStatus)) {
                    reason = "已申请本班，待审核";
                } else if ("withdraw_pending".equals(thisStatus)) {
                    reason = "退班审核中";
                } else if (!otherClass.isEmpty()) {
                    reason = "已在" + otherClass;
                } else {
                    eligible = true;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("studentId", row.get("student_id"));
                item.put("name", row.get("name"));
                item.put("department", row.get("department"));
                item.put("major", row.get("major"));
                item.put("grade", row.get("grade"));
                item.put("eligible", eligible);
                item.put("reason", reason);
                students.add(item);
            }
            result.put("success", true);
            result.put("className", cls.get("class_name"));
            result.put("students", students);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "可添加学生加载失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 批量将学生直接加入班级（管理端免审核）。
     * 已退班 / 入班被拒的历史记录复用并恢复为已加入；已在本班或已在其他班的学生跳过并在结果中说明。
     */
    public Map<String, Object> addMembers(Long classId, List<String> studentIds) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> cls = queryClass(jdbcTemplate, classId);
            if (cls == null) {
                return fail("班级不存在");
            }
            List<String> ids = distinctTrimmed(studentIds);
            if (ids.isEmpty()) {
                return fail("请先选择要添加的学生");
            }

            List<String> added = new ArrayList<>();
            List<Map<String, Object>> skipped = new ArrayList<>();
            for (String sid : ids) {
                List<Map<String, Object>> found = jdbcTemplate.queryForList(
                        "SELECT student_id, name FROM user WHERE student_id = ? AND role = 'student' AND status = 1", sid);
                if (found.isEmpty()) {
                    skipped.add(skipItem(sid, null, "学生不存在或已停用"));
                    continue;
                }
                String name = getStr(found.get(0), "name");

                List<Map<String, Object>> mine = jdbcTemplate.queryForList(
                        "SELECT id, status FROM class_member WHERE class_id = ? AND student_id = ?", classId, sid);
                if (!mine.isEmpty()) {
                    String status = getStr(mine.get(0), "status");
                    if ("withdrawn".equals(status) || "rejected".equals(status)) {
                        jdbcTemplate.update("UPDATE class_member SET status = 'approved', update_time = NOW() WHERE id = ?",
                                mine.get(0).get("id"));
                        added.add(name);
                    } else if ("approved".equals(status)) {
                        skipped.add(skipItem(sid, name, "已在本班"));
                    } else if ("pending".equals(status)) {
                        skipped.add(skipItem(sid, name, "已申请本班，待审核"));
                    } else {
                        skipped.add(skipItem(sid, name, "退班审核中"));
                    }
                    continue;
                }

                List<Map<String, Object>> other = jdbcTemplate.queryForList(
                        "SELECT ci.class_name FROM class_member cm JOIN class_info ci ON ci.id = cm.class_id " +
                        "WHERE cm.student_id = ? AND cm.status IN ('approved','pending','withdraw_pending') LIMIT 1", sid);
                if (!other.isEmpty()) {
                    skipped.add(skipItem(sid, name, "已在" + getStr(other.get(0), "class_name")));
                    continue;
                }

                try {
                    jdbcTemplate.update(
                            "INSERT INTO class_member (class_id, student_id, status) VALUES (?, ?, 'approved')", classId, sid);
                    added.add(name);
                } catch (Exception e) {
                    skipped.add(skipItem(sid, name, "写入失败：" + rootMessage(e)));
                }
            }

            result.put("success", !added.isEmpty());
            result.put("addedCount", added.size());
            result.put("addedNames", added);
            result.put("skipped", skipped);
            result.put("message", added.isEmpty()
                    ? "没有学生被添加"
                    : "已添加 " + added.size() + " 名学生" + (skipped.isEmpty() ? "" : "，" + skipped.size() + " 名未添加"));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "添加学生失败：" + rootMessage(e));
        }
        return result;
    }

    private Map<String, Object> skipItem(String studentId, String name, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("studentId", studentId);
        item.put("name", name);
        item.put("reason", reason);
        return item;
    }

    // ==================== 课表管理（班级详情内，委托课表域） ====================

    /** 班级课表列表：按星期、节次排序；week 为空时返回全量 */
    public Map<String, Object> listSchedule(Long classId) {
        return adminClassScheduleService.listSchedule(classId);
    }

    /** 班级课表列表：指定周次时只返回该周可见行（含生效调课的换周逻辑） */
    public Map<String, Object> listSchedule(Long classId, Integer week) {
        return adminClassScheduleService.listSchedule(classId, week);
    }

    /** 手动添加课表条目（课程不存在自动创建；同班同星期节次重叠且周次有交集时拒绝保存） */
    public Map<String, Object> addSchedule(Long classId, Map<String, Object> form) {
        return adminClassScheduleService.addSchedule(classId, form);
    }

    /** 修改课表条目（排除自身后按周次交集做冲突校验） */
    public Map<String, Object> updateSchedule(Long classId, Long sid, Map<String, Object> form) {
        return adminClassScheduleService.updateSchedule(classId, sid, form);
    }

    /** 删除课表条目 */
    public Map<String, Object> deleteSchedule(Long classId, Long sid) {
        return adminClassScheduleService.deleteSchedule(classId, sid);
    }

    // ==================== AI 导入（文字 / 识图，委托导入域） ====================

    /** AI 文字导入：课表文本 → AI 解析 → 结构化预览（含逐条校验结果，不入库） */
    public Map<String, Object> importPreviewText(Long classId, String text, String defaultSemester) {
        return adminClassImportService.importPreviewText(classId, text, defaultSemester);
    }

    /** AI 识图导入：先判定当前生效模型是否支持识图（multimodal_models），支持则调多模态接口识别。 */
    public Map<String, Object> importPreviewImage(Long classId, String base64Image, String mimeType, String defaultSemester) {
        return adminClassImportService.importPreviewImage(classId, base64Image, mimeType, defaultSemester);
    }

    /** 预览确认：批量入库（逐条校验，失败项不入库并返回原因） */
    @Transactional
    public Map<String, Object> importConfirm(Long classId, List<Map<String, Object>> items, String defaultSemester) {
        return adminClassImportService.importConfirm(classId, items, defaultSemester);
    }

    /**
     * AI 自动排课确认入库：条目自带班级名（className），逐条解析目标班级后写入 schedule。
     * 复用与 importConfirm 相同的写库路径（课程 / 教室 / 教师自动创建与回填）。
     */
    @Transactional
    public Map<String, Object> importConfirmMulti(List<Map<String, Object>> items, String defaultSemester) {
        return adminClassImportService.importConfirmMulti(items, defaultSemester);
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> validateClassName(Long excludeId, String className) {
        if (className == null || className.trim().isEmpty()) {
            return fail("班级名称不能为空");
        }
        if (className.trim().length() > 100) {
            return fail("班级名称过长（最多 100 字符）");
        }
        Integer exists;
        if (excludeId == null) {
            exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM class_info WHERE class_name = ?", Integer.class, className.trim());
        } else {
            exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM class_info WHERE class_name = ? AND id <> ?", Integer.class,
                    className.trim(), excludeId);
        }
        if (exists != null && exists > 0) {
            return fail("班级名称「" + className.trim() + "」已存在");
        }
        return null;
    }

    private Map<String, Object> validateTeacherId(String teacherId) {
        if (teacherId == null || teacherId.trim().isEmpty()) {
            return null; // 管理教师可选
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE student_id = ? AND role = 'teacher'", Integer.class, teacherId.trim());
        if (exists == null || exists == 0) {
            return fail("管理教师「" + teacherId.trim() + "」不存在或不是教师账号");
        }
        return null;
    }

    /** 一次性数据回填：对 teacher_id 为空但 teacher_name 非空的课表执行账号解析与回填（幂等，可重复执行） */
    @Transactional
    public Map<String, Object> backfillTeacherIds() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id, s.teacher_name, ci.department " +
                        "FROM schedule s LEFT JOIN class_info ci ON s.class_id = ci.id " +
                        "WHERE (s.teacher_id IS NULL OR s.teacher_id = '') " +
                        "AND s.teacher_name IS NOT NULL AND s.teacher_name <> ''");
        int updated = 0;
        List<String> createdTeachers = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, Map<String, Object>> cache = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String name = row.get("teacher_name") != null ? row.get("teacher_name").toString().trim() : "";
            if (name.isEmpty()) continue;
            String dept = row.get("department") != null ? row.get("department").toString() : "";
            Map<String, Object> tr = resolveTeacherAccount(jdbcTemplate, name, dept, cache, null);
            if (tr.get("error") != null) {
                failures.add("课表#" + row.get("id") + "（" + name + "）：" + tr.get("error"));
                continue;
            }
            jdbcTemplate.update("UPDATE schedule SET teacher_id = ? WHERE id = ?",
                    tr.get("teacherId"), row.get("id"));
            updated++;
            if (Boolean.TRUE.equals(tr.get("created"))) {
                String tag = name + "(" + tr.get("teacherId") + "，初始口令 " + tr.get("initialPassword") + ")";
                if (!createdTeachers.contains(tag)) {
                    createdTeachers.add(tag);
                }
            }
        }
        result.put("success", true);
        result.put("updated", updated);
        result.put("total", rows.size());
        result.put("createdTeachers", createdTeachers);
        result.put("failures", failures);
        result.put("message", "回填完成：处理 " + rows.size() + " 条，更新 " + updated + " 条"
                + (createdTeachers.isEmpty() ? "" : "；新建教师账号：" + String.join("、", createdTeachers)
                        + "（初始口令仅展示一次，请转告教师本人）")
                + (failures.isEmpty() ? "" : "；失败 " + failures.size() + " 条"));
        return result;
    }
}
