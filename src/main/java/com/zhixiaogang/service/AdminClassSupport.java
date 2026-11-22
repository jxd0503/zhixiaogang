package com.zhixiaogang.service;

import com.zhixiaogang.service.notification.NotificationService;
import com.zhixiaogang.util.PasswordUtils;
import com.zhixiaogang.util.WeekRangeParser;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/********** 班级管理共享工具：字段解析、班级/教师/课程查询与自动创建、文案格式化（静态方法，无状态） **********/
final class AdminClassSupport {

    private AdminClassSupport() {
    }

    /** 无教室课程在列表中的展示值 */
    static final String NO_CLASSROOM_DISPLAY = "无教室";

    private static final String[] WEEKDAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 判定是否为无教室课程：教室编号为空，或为约定值（无教室/室外/操场/体育场等） */
    static boolean isNoClassroom(String classroomNo) {
        if (classroomNo == null) return true;
        String s = classroomNo.trim();
        if (s.isEmpty()) return true;
        return s.equals("无教室") || s.equals("室外") || s.equals("操场") || s.equals("体育场")
                || s.equals("室外（体育课）") || s.equals("室外(体育课)");
    }

    static String weekDayName(Object weekDay) {
        if (weekDay == null) return "";
        int d;
        if (weekDay instanceof Number) d = ((Number) weekDay).intValue();
        else try { d = Integer.parseInt(weekDay.toString()); } catch (Exception e) { return String.valueOf(weekDay); }
        if (d >= 1 && d <= 7) return WEEKDAY_NAMES[d - 1];
        return "周" + d;
    }

    /** 周次归一化：自然语言与数字写法统一转为标准数字格式，保证落库无中文 */
    static String normalizeWeekRange(String raw) {
        if (raw == null) return "";
        return WeekRangeParser.normalizeOrFallback(raw);
    }

    /** 课程代码：UUID 生成的随机代码，避免按课程名 hashCode 取值导致的碰撞（course_code 有唯一索引） */
    static String generateCourseCode(String courseName) {
        return "AI" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    static String getStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().trim();
    }

    static Integer getInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            String s = v.toString().trim();
            if (s.isEmpty()) return null;
            return (int) Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    static Integer parseInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            String s = value.toString().trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    static Object emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    static long safeCount(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /** 当前学期名称（semester_config 最新一条）；未配置时返回空串，表示不按学期过滤 */
    static String currentSemesterName(JdbcTemplate jdbcTemplate) {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT semester_name FROM semester_config ORDER BY id DESC LIMIT 1", String.class);
            return names.isEmpty() || names.get(0) == null ? "" : names.get(0).trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    static Map<String, Object> fail(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    static Long queryId(JdbcTemplate jdbcTemplate, String sql, String arg) {
        try {
            List<Long> list = jdbcTemplate.queryForList(sql, Long.class, arg);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    static String queryString(JdbcTemplate jdbcTemplate, String sql, Object arg) {
        try {
            List<String> list = jdbcTemplate.queryForList(sql, String.class, arg);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 班级基础信息（id / class_name / grade / department / teacher_id / teacher_name） */
    static Map<String, Object> queryClass(JdbcTemplate jdbcTemplate, Long classId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT ci.id, ci.class_name, ci.grade, ci.department, ci.teacher_id, u.name AS teacher_name " +
                            "FROM class_info ci LEFT JOIN user u ON ci.teacher_id = u.student_id WHERE ci.id = ?",
                    classId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成下一个全局唯一教师工号（在现有最大 tXXX 工号上递增，无则从 t001 起） */
    static String nextTeacherId(JdbcTemplate jdbcTemplate) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(student_id, 2) AS UNSIGNED)), 0) FROM user " +
                        "WHERE role = 'teacher' AND student_id REGEXP '^t[0-9]+$'", Integer.class);
        return String.format("t%03d", (max == null ? 0 : max) + 1);
    }

    /**
     * 教师账号解析：在 role=teacher 账号中按姓名精确匹配；匹配不到自动创建账号并回填工号。
     * 自动创建：工号全局唯一（现有最大 t 工号递增）、随机初始口令（加盐哈希入库，明文通过
     * initialPassword 返回并仅展示一次）、角色 teacher、院系取班级院系（空则「未分配」）。
     * cache 用于批量导入同一教师只创建一次。
     * 返回 {teacherId, created, initialPassword}；失败返回 {error}。
     */
    static Map<String, Object> resolveTeacherAccount(JdbcTemplate jdbcTemplate, String teacherName, String department,
                                                     Map<String, Map<String, Object>> cache,
                                                     StringBuilder errorOut) {
        Map<String, Object> out = new HashMap<>();
        String name = teacherName == null ? "" : teacherName.trim();
        if (name.isEmpty()) {
            out.put("teacherId", null);
            out.put("created", false);
            return out;
        }
        if (cache != null && cache.containsKey(name)) {
            return cache.get(name);
        }
        try {
            String existing = queryString(jdbcTemplate,
                    "SELECT student_id FROM user WHERE name = ? AND role = 'teacher' LIMIT 1", name);
            if (existing != null) {
                out.put("teacherId", existing);
                out.put("created", false);
            } else {
                String newId = nextTeacherId(jdbcTemplate);
                String rawPassword = PasswordUtils.generateRandomPassword();
                String pwd = PasswordUtils.encode(rawPassword);
                String dept = (department == null || department.trim().isEmpty()) ? "未分配" : department.trim();
                jdbcTemplate.update(
                        "INSERT INTO user (student_id, name, password, role, department, status) VALUES (?, ?, ?, 'teacher', ?, 1)",
                        newId, name, pwd, dept);
                out.put("teacherId", newId);
                out.put("created", true);
                out.put("initialPassword", rawPassword);
            }
        } catch (Exception e) {
            String msg = "教师「" + name + "」账号创建失败：" + rootMessage(e);
            out.put("error", msg);
            if (errorOut != null) errorOut.append(msg);
        }
        if (cache != null) {
            cache.put(name, out);
        }
        return out;
    }

    static String buildImageInstruction() {
        return "请识别这张课表图片，输出 JSON，字段为 class_name、course_name、classroom_no、week_day、"
                + "start_period、end_period、week_range、teacher_name。"
                + "week_day 为数字 1 到 7（1 表示周一），start_period 与 end_period 为数字节次，"
                + "week_range 为上课周次（如 1-16）。"
                + "若课程为体育课等无固定教室的课程（如操场、室外、体育馆上课），classroom_no 输出空字符串。"
                + "只输出 JSON 数组本身，不要输出任何解释文字，"
                + "不要输出 markdown 代码块。";
    }

    /** 由写库入参组装课表发布通知条目 */
    static NotificationService.SchedulePub buildSchedulePub(Map<String, Object> m) {
        NotificationService.SchedulePub p = new NotificationService.SchedulePub();
        p.classId = (Long) m.get("targetClassId");
        p.className = (String) m.get("className");
        p.courseName = (String) m.get("courseName");
        p.weekDay = (Integer) m.get("weekDay");
        p.startPeriod = (Integer) m.get("startPeriod");
        p.endPeriod = (Integer) m.get("endPeriod");
        p.classroomNo = (String) m.get("classroomNo");
        p.teacherId = (String) m.get("teacherId");
        return p;
    }

    /** 批量列表工具：按字段名取列值并转为字符串列表（供成员批量处理复用） */
    static List<String> distinctTrimmed(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids != null) {
            for (String sid : ids) {
                if (sid == null || sid.trim().isEmpty()) continue;
                String v = sid.trim();
                if (!out.contains(v)) out.add(v);
            }
        }
        return out;
    }
}
