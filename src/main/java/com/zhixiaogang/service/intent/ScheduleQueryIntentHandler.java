package com.zhixiaogang.service.intent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.zhixiaogang.entity.SemesterConfig;
import com.zhixiaogang.service.ScheduleService;
import com.zhixiaogang.service.SemesterConfigService;
import com.zhixiaogang.service.TeacherCourseService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static com.zhixiaogang.service.intent.IntentSupport.*;

/********** 课表查询意图：学生课表 / 教师授课安排的取数与格式化 **********/
@Service
public class ScheduleQueryIntentHandler {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private TeacherCourseService teacherCourseService;

    @Autowired
    private SemesterConfigService semesterConfigService;

    /** 指定日期所在教学周：以管理端学期配置（起始日期 + 总周数）为准，周一为一周起点；配置缺失时回退第 1 周 */
    private int weekOf(LocalDate date) {
        SemesterConfig config = semesterConfigService.getCurrentSemester();
        if (config == null || config.getStartDate() == null || config.getWeeks() == null) {
            return 1;
        }
        LocalDate startMonday = config.getStartDate().with(DayOfWeek.MONDAY);
        LocalDate targetMonday = date.with(DayOfWeek.MONDAY);
        int week = (int) ChronoUnit.WEEKS.between(startMonday, targetMonday) + 1;
        if (week < 1) return 1;
        return Math.min(week, config.getWeeks());
    }

    /** 学期总周数：以学期配置为准，配置缺失时按 20 周 */
    private int totalWeeks() {
        SemesterConfig config = semesterConfigService.getCurrentSemester();
        return config != null && config.getWeeks() != null ? config.getWeeks() : 20;
    }

    /** 教师授课安排数据：基于 teacherCourseService.getCourses（teacher_id 过滤，姓名兼容历史数据） */
    @SuppressWarnings("unchecked")
    String retrieveTeacherScheduleData(String teacherId, String userName, String message) {
        StringBuilder data = new StringBuilder();
        data.append("【教师授课安排】\n");

        Map<String, Object> result = teacherCourseService.getCourses(userName, teacherId);
        if (!(Boolean) result.get("success")) {
            data.append("暂无授课数据：").append(result.get("message")).append("\n");
            return data.toString();
        }
        List<Map<String, Object>> courses = (List<Map<String, Object>>) result.get("courses");
        if (courses.isEmpty()) {
            data.append("该教师暂无授课安排。课表由管理员在管理端统一维护与导入，如发现课表缺失请联系管理员。\n");
            return data.toString();
        }

        List<Map<String, Object>> sorted = new ArrayList<>(courses);
        sorted.sort(java.util.Comparator.comparingInt(c ->
                ((Number) c.get("weekDay")).intValue() * 100 + ((Number) c.get("startPeriod")).intValue()));

        java.time.LocalDate today = java.time.LocalDate.now();
        int todayDay = today.getDayOfWeek().getValue();
        java.time.LocalTime now = java.time.LocalTime.now();
        data.append("今天是").append(getDayName(todayDay)).append("（").append(today).append("），当前时间 ")
            .append(now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))).append("。\n");

        // 「下一节课」推算：先找今天尚未开始的节次，再找本周后续天，最后回绕到下周第一天
        Map<String, Object> next = null;
        for (Map<String, Object> c : sorted) {
            int wd = ((Number) c.get("weekDay")).intValue();
            if (wd > todayDay) { next = c; break; }
        }
        if (next == null) {
            for (Map<String, Object> c : sorted) {
                int wd = ((Number) c.get("weekDay")).intValue();
                int sp = ((Number) c.get("startPeriod")).intValue();
                if (wd == todayDay && sp > currentEndPeriod(now)) { next = c; break; }
            }
        }
        if (next == null && !sorted.isEmpty()) {
            next = sorted.get(0);
        }
        if (next != null && (message.contains("下节课") || message.contains("下一节") || message.contains("下一堂"))) {
            data.append("根据当前时间推算，下一节课是：").append(teacherCourseLine(next)).append("\n");
        }

        data.append("全部授课安排：\n");
        for (Map<String, Object> c : sorted) {
            data.append("  ").append(teacherCourseLine(c)).append("\n");
        }
        return data.toString();
    }

    /** 当前时间所处的节次段结束节（用于判断某节课是否已开始） */
    private int currentEndPeriod(java.time.LocalTime now) {
        if (!now.isBefore(java.time.LocalTime.of(18, 30))) return 10; // 晚课后
        if (!now.isBefore(java.time.LocalTime.of(15, 50))) return 8;  // 7-8节进行中
        if (!now.isBefore(java.time.LocalTime.of(14, 0))) return 6;   // 5-6节进行中
        if (!now.isBefore(java.time.LocalTime.of(10, 20))) return 4;  // 3-4节进行中
        return 2; // 1-2节及更早
    }

    private String teacherCourseLine(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder();
        sb.append(getDayName(((Number) c.get("weekDay")).intValue()));
        sb.append(" 第").append(c.get("startPeriod")).append("-").append(c.get("endPeriod")).append("节");
        sb.append(" ").append(c.get("courseName"));
        if (c.get("className") != null) sb.append("（").append(c.get("className")).append("）");
        if (c.get("classroomNo") != null) sb.append(" @").append(c.get("classroomNo"));
        return sb.toString();
    }

    /**
     * 课表查询统一改用 scheduleService.loadSchedule(studentId, week)（班级课表，数据由管理员在管理端统一维护）。
     * loadSchedule 返回整周课表，需要按天过滤时使用 filterScheduleByDay。
     */
    String retrieveScheduleData(String studentId, String message) {
        StringBuilder data = new StringBuilder();
        data.append("【用户课表信息】\n");
        
        Integer week = extractWeek(message);
        Integer day = extractWeekDay(message);
        
        if (message.contains("明天") || message.contains("下节课")) {
            java.time.LocalDate tomorrow = java.time.LocalDate.now().plusDays(1);
            int dayOfWeek = tomorrow.getDayOfWeek().getValue();
            int currentWeek = weekOf(tomorrow);
            
            Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
            if ((Boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                java.util.List<?> scheduleList = (java.util.List<?>) result.get("schedule");
                java.util.List<java.util.Map<?, ?>> filteredList = filterScheduleByDay(scheduleList, dayOfWeek);
                if (!filteredList.isEmpty()) {
                    data.append("明天（" + getDayName(dayOfWeek) + "）课表：\n");
                    for (java.util.Map<?, ?> mapItem : filteredList) {
                        Integer period = (Integer) mapItem.get("period");
                        String courseName = (String) mapItem.get("courseName");
                        String location = (String) mapItem.get("location");
                        
                        data.append("  " + getPeriodName(period) + ": " + courseName);
                        if (location != null && !location.isEmpty()) {
                            data.append(" (" + location + ")");
                        }
                        data.append("\n");
                    }
                } else {
                    data.append("明天（" + getDayName(dayOfWeek) + "）没有课哦~\n");
                }
            }
        } else if (message.contains("本周")) {
            java.time.LocalDate now = java.time.LocalDate.now();
            int currentWeek = weekOf(now);
            
            Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
            if ((Boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                java.util.List<?> scheduleList = (java.util.List<?>) result.get("schedule");
                if (!scheduleList.isEmpty()) {
                    data.append("本周课表：\n");
                    java.util.Map<Integer, java.util.List<java.util.Map<?, ?>>> dayMap = new java.util.TreeMap<>();
                    for (Object item : scheduleList) {
                        if (item instanceof java.util.Map) {
                            java.util.Map<?, ?> mapItem = (java.util.Map<?, ?>) item;
                            Integer d = (Integer) mapItem.get("day");
                            if (d != null) {
                                if (!dayMap.containsKey(d)) {
                                    dayMap.put(d, new java.util.ArrayList<>());
                                }
                                dayMap.get(d).add((java.util.Map<?, ?>) item);
                            }
                        }
                    }
                    
                    for (java.util.Map.Entry<Integer, java.util.List<java.util.Map<?, ?>>> entry : dayMap.entrySet()) {
                        data.append("  " + getDayName(entry.getKey()) + "：\n");
                        for (java.util.Map<?, ?> mapItem : entry.getValue()) {
                            Integer period = (Integer) mapItem.get("period");
                            String courseName = (String) mapItem.get("courseName");
                            String location = (String) mapItem.get("location");
                            
                            data.append("    " + getPeriodName(period) + ": " + courseName);
                            if (location != null && !location.isEmpty()) {
                                data.append(" (" + location + ")");
                            }
                            data.append("\n");
                        }
                    }
                } else {
                    data.append("暂未查询到本周课表：学生尚未加入班级或班级暂无课程安排，请先加入班级后查看课表\n");
                }
            }
        } else if (week != null && day != null) {
            Map<String, Object> result = scheduleService.loadSchedule(studentId, week);
            if ((Boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                java.util.List<?> scheduleList = (java.util.List<?>) result.get("schedule");
                java.util.List<java.util.Map<?, ?>> filteredList = filterScheduleByDay(scheduleList, day);
                if (!filteredList.isEmpty()) {
                    data.append("第" + week + "周" + getDayName(day) + "课表：\n");
                    for (java.util.Map<?, ?> mapItem : filteredList) {
                        Integer period = (Integer) mapItem.get("period");
                        String courseName = (String) mapItem.get("courseName");
                        String location = (String) mapItem.get("location");
                        
                        data.append("  " + getPeriodName(period) + ": " + courseName);
                        if (location != null && !location.isEmpty()) {
                            data.append(" (" + location + ")");
                        }
                        data.append("\n");
                    }
                } else {
                    data.append("第" + week + "周" + getDayName(day) + "没有课\n");
                }
            }
        } else if (day != null) {
            // 只给星期（如"我这个星期三有什么课"）→ 按当前教学周取该天课表
            int currentWeek = weekOf(java.time.LocalDate.now());
            Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
            if ((Boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                java.util.List<?> scheduleList = (java.util.List<?>) result.get("schedule");
                java.util.List<java.util.Map<?, ?>> filteredList = filterScheduleByDay(scheduleList, day);
                if (!filteredList.isEmpty()) {
                    data.append("第" + currentWeek + "周" + getDayName(day) + "课表：\n");
                    for (java.util.Map<?, ?> mapItem : filteredList) {
                        Integer period = (Integer) mapItem.get("period");
                        String courseName = (String) mapItem.get("courseName");
                        String location = (String) mapItem.get("location");

                        data.append("  " + getPeriodName(period) + ": " + courseName);
                        if (location != null && !location.isEmpty()) {
                            data.append(" (" + location + ")");
                        }
                        data.append("\n");
                    }
                } else {
                    data.append("第" + currentWeek + "周" + getDayName(day) + "没有课\n");
                }
            }
        } else {
            java.time.LocalDate now = java.time.LocalDate.now();
            int currentWeek = weekOf(now);
            int today = now.getDayOfWeek().getValue();
            
            Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
            if ((Boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                java.util.List<?> scheduleList = (java.util.List<?>) result.get("schedule");
                java.util.List<java.util.Map<?, ?>> filteredList = filterScheduleByDay(scheduleList, today);
                if (!filteredList.isEmpty()) {
                    data.append("今天（" + getDayName(today) + "）课表：\n");
                    for (java.util.Map<?, ?> mapItem : filteredList) {
                        Integer period = (Integer) mapItem.get("period");
                        String courseName = (String) mapItem.get("courseName");
                        String location = (String) mapItem.get("location");
                        
                        data.append("  " + getPeriodName(period) + ": " + courseName);
                        if (location != null && !location.isEmpty()) {
                            data.append(" (" + location + ")");
                        }
                        data.append("\n");
                    }
                } else {
                    data.append("今天（" + getDayName(today) + "）没有课哦~\n");
                }
            }
        }
        
        return data.toString();
    }

    private String handleScheduleQuery(String userName, String studentId, String message) {
        // 解析周次和周几
        Integer week = extractWeek(message);
        Integer day = extractWeekDay(message);

        // 当前教学周以学期配置为准
        java.time.LocalDate now = java.time.LocalDate.now();
        int currentWeek = weekOf(now);

        // P5 课程统计："我有几节课""这周几节课"
        if (message.contains("几节课") || message.contains("多少节课")
                || (message.contains("几节") && day == null)) {
            Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
            return formatWeekCourseStats(userName, result, currentWeek);
        }

        // 相对日期课表查询："明天有课吗""今天有课""后天有什么课"
        // 参照 retrieveScheduleData 的"明天"写法，计算目标日期所在周次并按天过滤
        java.time.LocalDate relativeDate = null;
        String relativeLabel = null;
        if (message.contains("明天")) {
            relativeDate = now.plusDays(1);
            relativeLabel = "明天";
        } else if (message.contains("今天")) {
            relativeDate = now;
            relativeLabel = "今天";
        } else if (message.contains("后天")) {
            relativeDate = now.plusDays(2);
            relativeLabel = "后天";
        }
        if (relativeDate != null) {
            int dayOfWeek = relativeDate.getDayOfWeek().getValue(); // 1=周一, 7=周日
            int targetWeek = weekOf(relativeDate);
            Map<String, Object> result = scheduleService.loadSchedule(studentId, targetWeek);
            if (!(Boolean) result.get("success")) {
                return userName + "同学，查询课表失败：" + result.get("message");
            }
            @SuppressWarnings("unchecked")
            java.util.List<?> scheduleList = (java.util.List<?>) result.get("schedule");
            java.util.List<java.util.Map<?, ?>> filteredList = filterScheduleByDay(scheduleList, dayOfWeek);
            String dateLabel = relativeLabel + "（" + getDayName(dayOfWeek) + "）";
            return formatUserScheduleByDayResponse(userName, result, dateLabel, dayOfWeek);
        }

        // 如果是查询本周课表（带周几时只返回当天）
        if (message.contains("本周")) {
            Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
            if (day != null) {
                return formatUserScheduleByDayResponse(userName, result, "第" + currentWeek + "周" + getDayName(day), day);
            }
            return formatUserScheduleResponse(userName, result, "本周");
        }

        // 如果是查询下周课表（带周几时只返回当天）
        if (message.contains("下周")) {
            int nextWeek = Math.min(currentWeek + 1, totalWeeks());

            Map<String, Object> result = scheduleService.loadSchedule(studentId, nextWeek);
            if (day != null) {
                return formatUserScheduleByDayResponse(userName, result, "第" + nextWeek + "周" + getDayName(day), day);
            }
            return formatUserScheduleResponse(userName, result, "第" + nextWeek + "周");
        }
        
        // 如果指定了周次和周几
        if (week != null && day != null) {
            Map<String, Object> result = scheduleService.loadSchedule(studentId, week);
            String dayName = getDayName(day);
            return formatUserScheduleByDayResponse(userName, result, "第" + week + "周" + dayName, day);
        }
        
        // 如果只指定了周几，默认查询当前周
        if (day != null) {
            Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
            String dayName = getDayName(day);
            return formatUserScheduleByDayResponse(userName, result, "第" + currentWeek + "周" + dayName, day);
        }
        
        // 默认返回本周课表
        Map<String, Object> result = scheduleService.loadSchedule(studentId, currentWeek);
        return formatUserScheduleResponse(userName, result, "本周");
    }

    /**
     * 从 loadSchedule 返回结果中按天过滤课表列表（loadSchedule 返回整周数据）。
     * day 为 null 时返回全部。
     */
    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<?, ?>> filterScheduleByDay(List<?> scheduleList, Integer day) {
        java.util.List<java.util.Map<?, ?>> filtered = new java.util.ArrayList<>();
        if (day == null) {
            for (Object item : scheduleList) {
                if (item instanceof java.util.Map) {
                    filtered.add((java.util.Map<?, ?>) item);
                }
            }
            return filtered;
        }
        for (Object item : scheduleList) {
            if (item instanceof java.util.Map) {
                java.util.Map<?, ?> mapItem = (java.util.Map<?, ?>) item;
                Integer itemDay = (Integer) mapItem.get("day");
                if (day.equals(itemDay)) {
                    filtered.add(mapItem);
                }
            }
        }
        return filtered;
    }

    private String formatUserScheduleResponse(String userName, Map<String, Object> result, String weekLabel) {
        if (!(Boolean) result.get("success")) {
            return userName + "同学，查询课表失败：" + result.get("message");
        }
        
        @SuppressWarnings("unchecked")
        List<?> scheduleList = (List<?>) result.get("schedule");
        
        if (scheduleList.isEmpty()) {
            return userName + "同学，课表由管理员统一维护；暂未查询到您的" + weekLabel + "课表，请先加入班级后查看课表";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(userName + "同学，" + weekLabel + "课表如下：\n\n");
        
        java.util.Map<Integer, java.util.List<java.util.Map<?, ?>>> dayMap = new java.util.TreeMap<>();
        for (Object item : scheduleList) {
            if (item instanceof java.util.Map) {
                java.util.Map<?, ?> mapItem = (java.util.Map<?, ?>) item;
                Integer day = (Integer) mapItem.get("day");
                if (day != null) {
                    if (!dayMap.containsKey(day)) {
                        dayMap.put(day, new java.util.ArrayList<>());
                    }
                    dayMap.get(day).add((java.util.Map<?, ?>) item);
                }
            }
        }
        
        for (java.util.Map.Entry<Integer, java.util.List<java.util.Map<?, ?>>> entry : dayMap.entrySet()) {
            sb.append(getDayName(entry.getKey()) + "：\n");
            for (java.util.Map<?, ?> mapItem : entry.getValue()) {
                Integer period = (Integer) mapItem.get("period");
                String courseName = (String) mapItem.get("courseName");
                String location = (String) mapItem.get("location");
                
                String periodName = getPeriodName(period);
                sb.append("  " + periodName + ": " + courseName);
                if (location != null && !location.isEmpty()) {
                    sb.append(" (" + location + ")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }

    private String formatUserScheduleByDayResponse(String userName, Map<String, Object> result, String label, Integer day) {
        if (!(Boolean) result.get("success")) {
            return userName + "同学，查询课表失败：" + result.get("message");
        }

        @SuppressWarnings("unchecked")
        List<?> scheduleList = (List<?>) result.get("schedule");
        java.util.List<java.util.Map<?, ?>> filteredList = filterScheduleByDay(scheduleList, day);

        if (filteredList.isEmpty()) {
            // P8 区分：整周无课 / 未入班 vs 仅该日无课
            if (scheduleList != null && !scheduleList.isEmpty()) {
                return userName + "同学，" + label + "暂无课程安排";
            }
            return userName + "同学，课表由管理员统一维护；暂未查询到" + label + "的课表，请先加入班级后查看课表";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(userName + "同学，" + label + "课表如下：\n\n");

        for (java.util.Map<?, ?> mapItem : filteredList) {
            Integer period = (Integer) mapItem.get("period");
            String courseName = (String) mapItem.get("courseName");
            String location = (String) mapItem.get("location");

            String periodName = getPeriodName(period);
            sb.append(periodName + ": " + courseName);
            if (location != null && !location.isEmpty()) {
                sb.append(" (" + location + ")");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /** P5 本周课程统计：按天统计课程块数 */
    @SuppressWarnings("unchecked")
    private String formatWeekCourseStats(String userName, Map<String, Object> result, int week) {
        if (!(Boolean) result.get("success")) {
            return userName + "同学，查询课表失败：" + result.get("message");
        }
        List<?> scheduleList = (List<?>) result.get("schedule");
        if (scheduleList == null || scheduleList.isEmpty()) {
            return userName + "同学，课表由管理员统一维护；暂未查询到本周课表，请先加入班级后查看课表";
        }
        java.util.Map<Integer, Integer> dayCount = new java.util.TreeMap<>();
        int total = 0;
        for (Object item : scheduleList) {
            if (item instanceof java.util.Map) {
                java.util.Map<?, ?> mapItem = (java.util.Map<?, ?>) item;
                Integer d = (Integer) mapItem.get("day");
                if (d != null) {
                    dayCount.put(d, dayCount.getOrDefault(d, 0) + 1);
                    total++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(userName).append("同学，本周（第").append(week).append("周）共 ").append(total).append(" 节课：");
        for (java.util.Map.Entry<Integer, Integer> entry : dayCount.entrySet()) {
            sb.append(getDayName(entry.getKey())).append(" ").append(entry.getValue()).append(" 节、");
        }
        sb.setLength(sb.length() - 1);
        sb.append("。");
        return sb.toString();
    }
}
