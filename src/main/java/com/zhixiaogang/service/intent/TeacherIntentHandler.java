package com.zhixiaogang.service.intent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.zhixiaogang.service.TeacherClassService;
import com.zhixiaogang.service.TeacherCourseService;

import static com.zhixiaogang.service.intent.IntentSupport.*;

/********** 教师端意图：授课安排查询 / 班级学生名单 **********/
@Service
public class TeacherIntentHandler {

    @Autowired
    private TeacherCourseService teacherCourseService;

    @Autowired
    private TeacherClassService teacherClassService;

    /** 教师授课查询：teacher_id 过滤（姓名兼容历史数据），支持"今天/周X"过滤 */
    @SuppressWarnings("unchecked")
    private String handleTeacherCourses(String userName, String teacherId, String message) {
        Map<String, Object> result = teacherCourseService.getCourses(userName, teacherId);
        if (!(Boolean) result.get("success")) {
            return userName + "老师，查询授课安排失败：" + result.get("message");
        }
        List<Map<String, Object>> courses = (List<Map<String, Object>>) result.get("courses");
        if (courses.isEmpty()) {
            return userName + "老师，暂无授课安排。课表由管理员在管理端统一维护与导入，如发现课表缺失请联系管理员。";
        }

        String lower = message.toLowerCase();
        Integer filterDay = null;
        if (lower.contains("今天")) {
            filterDay = LocalDate.now().getDayOfWeek().getValue();
        } else if (lower.contains("明天")) {
            filterDay = LocalDate.now().plusDays(1).getDayOfWeek().getValue();
        } else {
            filterDay = extractWeekDay(message);
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> c : courses) {
            Object wd = c.get("weekDay");
            if (filterDay == null || (wd instanceof Number && ((Number) wd).intValue() == filterDay)) {
                filtered.add(c);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (filterDay != null) {
            sb.append(userName).append("老师，").append(getDayName(filterDay));
            if (filtered.isEmpty()) {
                sb.append("您没有课程安排。");
                return sb.toString();
            }
            sb.append("您的课程安排：\n");
        } else {
            sb.append(userName).append("老师，您的授课安排：\n");
        }
        for (Map<String, Object> c : filtered) {
            sb.append("• ").append(getDayName(((Number) c.get("weekDay")).intValue()));
            sb.append(" 第").append(c.get("startPeriod")).append("-").append(c.get("endPeriod")).append("节");
            sb.append(" ").append(c.get("courseName"));
            if (c.get("className") != null) sb.append("（").append(c.get("className")).append("）");
            if (c.get("classroomNo") != null) sb.append(" @").append(c.get("classroomNo"));
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 教师班级学生名单 */
    @SuppressWarnings("unchecked")
    String handleTeacherStudents(String userName, String teacherId) {
        Map<String, Object> classesResult = teacherClassService.getMyClasses(teacherId);
        if (!(Boolean) classesResult.get("success")) {
            return userName + "老师，查询班级失败：" + classesResult.get("message");
        }
        List<Map<String, Object>> classes = (List<Map<String, Object>>) classesResult.get("data");
        if (classes.isEmpty()) {
            return userName + "老师，您暂无管理的班级。";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> cls : classes) {
            Long classId = ((Number) cls.get("id")).longValue();
            sb.append("【").append(cls.get("className")).append("】\n");
            Map<String, Object> studentsResult = teacherClassService.getStudents(classId, teacherId);
            if ((Boolean) studentsResult.get("success")) {
                List<Map<String, Object>> students = (List<Map<String, Object>>) studentsResult.get("data");
                if (students.isEmpty()) {
                    sb.append("  暂无学生\n");
                } else {
                    for (Map<String, Object> s : students) {
                        sb.append("• ").append(s.get("studentId")).append(" ").append(s.get("name"));
                        if (s.get("major") != null) sb.append("（").append(s.get("major")).append("）");
                        sb.append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }
}
