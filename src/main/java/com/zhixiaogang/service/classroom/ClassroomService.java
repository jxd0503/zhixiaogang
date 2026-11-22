package com.zhixiaogang.service.classroom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ClassroomReservationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/********** 教室基础域（对外门面）：教室信息查询、节次时段换算、按时段查空教室；占用与预约委托给对应服务 **********/
@Service
public class ClassroomService {

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private ClassroomReservationMapper reservationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ClassroomOccupancyService classroomOccupancyService;

    @Autowired
    private ClassroomReservationService classroomReservationService;

    /**
     * 教师端：查询指定时段（星期 × 节次）未被 schedule 占用且 status='available' 的教室
     * weekDay、startPeriod、endPeriod 均可空（默认全部时段）
     * startPeriod/endPeriod 为节次段行索引（1=1-2节, 2=3-4节, 3=5-6节, 4=7-8节, 5=9-10节），与现有预约约定一致
     */
    public Map<String, Object> getEmptyClassrooms(Integer weekDay, Integer startPeriod, Integer endPeriod) {
        return classroomOccupancyService.getEmptyClassrooms(weekDay, startPeriod, endPeriod);
    }

    /**
     * 管理端：单个教室一周占用明细。
     * 口径与空教室查询一致：只统计 schedule 表中 classroom_id 等于该教室的条目；
     * 无教室课程（classroom_id IS NULL，体育课等）不参与占用。
     * 叠加已批准预约（classroom_reservation status='approved'）作为额外占用来源，
     * 通过 source 字段标注来源（schedule=课表 / reservation=预约）与预约人。
     */
    public Map<String, Object> getClassroomOccupancy(Long classroomId, Integer week) {
        return classroomOccupancyService.getClassroomOccupancy(classroomId, week);
    }

    /**
     * 管理端：全部教室一周占用概览（schedule 来源条目 + 占用节次数）。
     * 无课表占用的教室返回空数组，不报错。
     */
    public Map<String, Object> getAllClassroomsOccupancy(Integer week) {
        return classroomOccupancyService.getAllClassroomsOccupancy(week);
    }

    public Map<String, Object> getAvailableClassrooms() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> classrooms = classroomMapper.getAvailableClassrooms();
        result.put("success", true);
        result.put("data", classrooms);
        return result;
    }

    /** 教室实际存在的楼栋列表（数据驱动筛选项）。 */
    public List<String> getBuildings() {
        return classroomMapper.listBuildings();
    }

    public Map<String, Object> getClassroomsByBuilding(String building) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> classrooms = classroomMapper.getClassroomsByBuilding(building);
        
        if (classrooms.isEmpty()) {
            result.put("success", false);
            result.put("message", building + "楼暂无可用教室");
            return result;
        }
        
        result.put("success", true);
        result.put("data", classrooms);
        return result;
    }

    public Map<String, Object> getClassroomById(Long id) {
        Map<String, Object> result = new HashMap<>();
        Classroom classroom = classroomMapper.selectById(id);
        
        if (classroom == null) {
            result.put("success", false);
            result.put("message", "教室不存在");
            return result;
        }
        
        Map<String, Object> classroomMap = new HashMap<>();
        classroomMap.put("id", classroom.getId());
        classroomMap.put("classroom_no", classroom.getClassroomNo());
        classroomMap.put("building", classroom.getBuilding());
        classroomMap.put("floor", classroom.getFloor());
        classroomMap.put("capacity", classroom.getCapacity());
        classroomMap.put("room_type", classroom.getRoomType());
        classroomMap.put("status", classroom.getStatus());
        classroomMap.put("description", classroom.getDescription());
        
        result.put("success", true);
        result.put("data", java.util.Collections.singletonList(classroomMap));
        return result;
    }

    /**
     * P0-1 并发安全的教室预约（实现见 ClassroomReservationService，事务边界随方法迁移，保持不变）
     */
    public Map<String, Object> reserveClassroom(Long classroomId, String studentId, String studentName,
                                                String reason, LocalDate reservationDate,
                                                Integer startPeriod, Integer endPeriod) {
        return classroomReservationService.reserveClassroom(classroomId, studentId, studentName,
                reason, reservationDate, startPeriod, endPeriod);
    }

    /** P1-4 查询某学生的全部预约（按日期倒序） */
    public List<ClassroomReservation> getReservationsByStudent(String studentId) {
        return classroomReservationService.getReservationsByStudent(studentId);
    }

    /** 学生端「我的预约」：分页返回本人预约（含教室编号） */
    public Map<String, Object> getStudentReservations(String studentId, Integer page, Integer size) {
        return classroomReservationService.getStudentReservations(studentId, page, size);
    }

    /** 学生取消本人待审核预约：pending → cancelled（原子条件更新，仅本人可操作） */
    public Map<String, Object> cancelPendingReservation(Long id, String studentId) {
        return classroomReservationService.cancelPendingReservation(id, studentId);
    }

    /**
     * P1-3 具体教室指定日期的占用情况：返回当天排课（schedule）与已批准预约（reservation）。
     * period 为节次段（1-5），为空表示查全天。
     */
    public Map<String, Object> getClassroomDayStatus(String classroomNo, LocalDate date, Integer period) {
        Map<String, Object> result = new HashMap<>();
        Classroom classroom = classroomMapper.selectOne(new LambdaQueryWrapper<Classroom>()
                .eq(Classroom::getClassroomNo, classroomNo.toUpperCase()));
        if (classroom == null) {
            result.put("success", false);
            result.put("message", "未找到教室 " + classroomNo);
            return result;
        }
        int weekDay = date.getDayOfWeek().getValue();

        List<Object> args = new java.util.ArrayList<>();
        StringBuilder scheduleSql = new StringBuilder(
                "SELECT s.start_period, s.end_period, c.course_name, s.teacher_name " +
                "FROM schedule s LEFT JOIN course c ON s.course_id = c.id " +
                "WHERE s.classroom_id = ? AND s.week_day = ?");
        args.add(classroom.getId());
        args.add(weekDay);
        if (period != null) {
            int actualStart = (period - 1) * 2 + 1;
            int actualEnd = period * 2;
            scheduleSql.append(" AND s.start_period < ? AND s.end_period > ?");
            args.add(actualEnd);
            args.add(actualStart);
        }
        scheduleSql.append(" ORDER BY s.start_period");
        List<Map<String, Object>> schedules = jdbcTemplate.queryForList(scheduleSql.toString(), args.toArray());

        List<ClassroomReservation> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ClassroomReservation>()
                        .eq(ClassroomReservation::getClassroomId, classroom.getId())
                        .eq(ClassroomReservation::getReservationDate, date)
                        .eq(ClassroomReservation::getStatus, "approved")
                        .orderByAsc(ClassroomReservation::getStartPeriod));

        result.put("success", true);
        result.put("classroom", classroom);
        result.put("date", date);
        result.put("weekDay", weekDay);
        result.put("schedules", schedules);
        result.put("reservations", reservations);
        return result;
    }

    public String formatClassroomResponse(List<Map<String, Object>> classrooms) {
        if (classrooms.isEmpty()) {
            return "暂无可用教室";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("以下是可用的教室：\n");
        
        for (Map<String, Object> classroom : classrooms) {
            String classroomNo = (String) classroom.get("classroom_no");
            String building = (String) classroom.get("building");
            Integer capacity = (Integer) classroom.get("capacity");
            Object roomType = classroom.get("room_type");
            
            sb.append("• ").append(classroomNo);
            sb.append(" (").append(building).append("楼，").append(capacity).append("人)");
            if (roomType != null && !roomType.toString().isEmpty()) sb.append(" ").append(roomType);
            sb.append("\n");
        }
        
        return sb.toString();
    }

    static final Map<Integer, LocalTime> PERIOD_START_TIMES = Map.of(
            1, LocalTime.of(8, 30),
            2, LocalTime.of(10, 25),
            3, LocalTime.of(14, 0),
            4, LocalTime.of(15, 55),
            5, LocalTime.of(18, 30)
    );

    static final Map<Integer, LocalTime> PERIOD_END_TIMES = Map.of(
            1, LocalTime.of(10, 10),
            2, LocalTime.of(12, 0),
            3, LocalTime.of(15, 40),
            4, LocalTime.of(17, 30),
            5, LocalTime.of(20, 15)
    );

    static final Map<Integer, String> PERIOD_NAMES = Map.of(
            1, "1-2节 (8:30-10:10)",
            2, "3-4节 (10:25-12:00)",
            3, "5-6节 (14:00-15:40)",
            4, "7-8节 (15:55-17:30)",
            5, "9-10节 (18:30-20:15)"
    );

    public int getCurrentPeriod() {
        LocalTime now = LocalTime.now();
        
        for (Map.Entry<Integer, LocalTime> entry : PERIOD_START_TIMES.entrySet()) {
            LocalTime endTime = PERIOD_END_TIMES.get(entry.getKey());
            if (!now.isBefore(entry.getValue()) && !now.isAfter(endTime)) {
                return entry.getKey();
            }
        }
        
        return 0;
    }

    public int getNextPeriod() {
        LocalTime now = LocalTime.now();
        
        for (Map.Entry<Integer, LocalTime> entry : PERIOD_START_TIMES.entrySet()) {
            if (now.isBefore(entry.getValue())) {
                return entry.getKey();
            }
        }
        
        return 0;
    }

    public Map<String, Object> getAvailableClassroomsForPeriod(int period, String building) {
        Map<String, Object> result = new HashMap<>();
        
        if (period <= 0 || period > 5) {
            result.put("success", false);
            result.put("message", "当前时间段没有可用教室");
            return result;
        }
        
        LocalDate today = LocalDate.now();
        int weekDay = today.getDayOfWeek().getValue();
        
        List<Classroom> allClassrooms;
        if (building != null && !building.isEmpty()) {
            allClassrooms = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>()
                    .eq(Classroom::getBuilding, building.toUpperCase())
                    .eq(Classroom::getStatus, "available"));
        } else {
            allClassrooms = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>()
                    .eq(Classroom::getStatus, "available"));
        }
        
        List<ClassroomReservation> reservations = reservationMapper.selectList(new LambdaQueryWrapper<ClassroomReservation>()
                .eq(ClassroomReservation::getReservationDate, today)
                .eq(ClassroomReservation::getStatus, "approved"));
        // 查排课占用
        int actualStart = (period - 1) * 2 + 1;
        int actualEnd = period * 2;
        String schedSql = "SELECT s.classroom_id, c.course_name FROM schedule s " +
                "LEFT JOIN course c ON s.course_id = c.id " +
                "WHERE s.classroom_id IS NOT NULL AND s.week_day = ? AND s.start_period < ? AND s.end_period > ?";
        Map<Long, String> schedMap = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(schedSql, weekDay, actualEnd, actualStart)) {
            Long cid = ((Number) r.get("classroom_id")).longValue();
            String cn = r.get("course_name") == null ? "" : r.get("course_name").toString();
            schedMap.putIfAbsent(cid, cn);
        }

        List<Map<String, Object>> free = new java.util.ArrayList<>();
        List<Map<String, Object>> occupied = new java.util.ArrayList<>();
        for (Classroom c : allClassrooms) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("classroom_no", c.getClassroomNo());
            map.put("building", c.getBuilding());
            map.put("capacity", c.getCapacity());
            map.put("room_type", c.getRoomType());
            // 判占用：排课优先，其次预约
            String reason = null;
            if (schedMap.containsKey(c.getId())) {
                String cn = schedMap.get(c.getId());
                reason = (cn != null && !cn.isEmpty()) ? "已被《" + cn + "》占用" : "已有排课";
            } else {
                for (ClassroomReservation r : reservations) {
                    if (r.getClassroomId().equals(c.getId()) &&
                        r.getStartPeriod() <= period &&
                        r.getEndPeriod() >= period) {
                        reason = "已有预约";
                        break;
                    }
                }
            }
            if (reason != null) {
                map.put("available", false);
                map.put("occupiedReason", reason);
                occupied.add(map);
            } else {
                map.put("available", true);
                free.add(map);
            }
        }
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        all.addAll(free);
        all.addAll(occupied);

        result.put("success", true);
        result.put("data", all);
        result.put("period", period);
        result.put("periodName", PERIOD_NAMES.get(period));
        result.put("weekDay", weekDay);

        return result;
    }

    /**
     * 学生端：查询指定日期 + 节次段的空教室
     * period 为空时不过滤时段（返回所有可用教室）
     * period 为节次段索引（1=1-2节, 2=3-4节, 3=5-6节, 4=7-8节, 5=9-10节）
     * 指定 period 时同时排除课表(schedule)占用与已批准预约(reservation)占用
     */
    public Map<String, Object> getAvailableClassroomsByDatePeriod(LocalDate date, Integer period, String building) {
        Map<String, Object> result = new HashMap<>();
        if (period != null && (period < 1 || period > 5)) {
            result.put("success", false);
            result.put("message", "无效的节次");
            return result;
        }
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT c.id, c.classroom_no, c.building, c.floor, c.capacity, c.room_type " +
                    "FROM classroom c WHERE c.status = 'available'");
            List<Object> args = new java.util.ArrayList<>();
            if (building != null && !building.isEmpty()) {
                sql.append(" AND c.building = ?");
                args.add(building.toUpperCase());
            }
            sql.append(" ORDER BY c.building, c.floor, c.classroom_no");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());

            // 有 period 时查占用来源，给每条教室标注 available + occupiedReason，空闲在前占用在后
            if (period != null) {
                int weekDay = date.getDayOfWeek().getValue();
                int actualStart = (period - 1) * 2 + 1;
                int actualEnd = period * 2;
                Map<Long, String> schedMap = new java.util.HashMap<>();
                Map<Long, String> adjMap = new java.util.HashMap<>();
                Map<Long, String> resvMap = new java.util.HashMap<>();

                // 已批准调课：目标时段占用目标教室；原时段从课表占用中剔除
                java.util.Set<Long> hiddenScheduleIds = new java.util.HashSet<>();
                classroomOccupancyService.collectAdjustmentOccupancy(weekDay, actualStart, actualEnd, adjMap, hiddenScheduleIds);

                // 排课占用
                String schedSql = "SELECT s.id, s.classroom_id, c.course_name FROM schedule s " +
                        "LEFT JOIN course c ON s.course_id = c.id " +
                        "WHERE s.classroom_id IS NOT NULL AND s.week_day = ? AND s.start_period < ? AND s.end_period > ?";
                for (Map<String, Object> r : jdbcTemplate.queryForList(schedSql, weekDay, actualEnd, actualStart)) {
                    Object sid = r.get("id");
                    if (sid instanceof Number && hiddenScheduleIds.contains(((Number) sid).longValue())) {
                        continue;
                    }
                    Long cid = ((Number) r.get("classroom_id")).longValue();
                    String cn = r.get("course_name") == null ? "" : r.get("course_name").toString();
                    schedMap.putIfAbsent(cid, cn);
                }
                // 预约占用（按具体日期 + 节次段）
                classroomOccupancyService.collectReservationOccupancy(weekDay, period, period, date, resvMap);
                // 标注 + 排序：空闲在前，占用在后
                List<Map<String, Object>> free = new java.util.ArrayList<>();
                List<Map<String, Object>> occupied = new java.util.ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Long cid = ((Number) row.get("id")).longValue();
                    String reason = null;
                    if (schedMap.containsKey(cid)) {
                        String cn = schedMap.get(cid);
                        reason = (cn != null && !cn.isEmpty()) ? "已被《" + cn + "》占用" : "已有排课";
                    } else if (adjMap.containsKey(cid)) {
                        reason = adjMap.get(cid);
                    } else if (resvMap.containsKey(cid)) {
                        reason = resvMap.get(cid);
                    }
                    if (reason != null) {
                        row.put("available", false);
                        row.put("occupiedReason", reason);
                        occupied.add(row);
                    } else {
                        row.put("available", true);
                        free.add(row);
                    }
                }
                rows = new java.util.ArrayList<>();
                rows.addAll(free);
                rows.addAll(occupied);
            }
            result.put("success", true);
            result.put("data", rows);
            result.put("date", date.toString());
            result.put("period", period);
            result.put("periodName", period != null ? PERIOD_NAMES.get(period) : null);
            result.put("weekDay", date.getDayOfWeek().getValue());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询失败：" + e.getMessage());
        }
        return result;
    }

    public String formatClassroomResponseForNextPeriod(List<Map<String, Object>> classrooms, int period) {
        List<Map<String, Object>> free = classrooms.stream()
                .filter(c -> !Boolean.FALSE.equals(c.get("available")))
                .toList();
        if (free.isEmpty()) {
            return "当前时间段(" + PERIOD_NAMES.get(period) + ")没有空教室";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前时间 ").append(LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                .append("，下一个节次为 ").append(PERIOD_NAMES.get(period)).append("\n");
        sb.append("以下是该节次的空教室：\n");

        for (Map<String, Object> classroom : free) {
            String classroomNo = (String) classroom.get("classroom_no");
            String building = (String) classroom.get("building");
            Integer capacity = (Integer) classroom.get("capacity");
            Object roomType = classroom.get("room_type");
            
            sb.append("• ").append(classroomNo);
            sb.append(" (").append(building).append("楼，").append(capacity).append("人)");
            if (roomType != null && !roomType.toString().isEmpty()) sb.append(" ").append(roomType);
            sb.append("\n");
        }
        
        return sb.toString();
    }
}
