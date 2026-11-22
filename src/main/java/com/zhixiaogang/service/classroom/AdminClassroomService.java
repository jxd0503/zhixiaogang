package com.zhixiaogang.service.classroom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.mapper.ClassroomMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端教室与课程库管理：教室增删改查与引用校验、课程删除。
 * 从 AdminClassroomController 下沉，控制器只负责路由与操作日志。
 * 返回 Map 中 {@code _logDetail} 为内部操作日志文案，控制器记录后需移除，不返回前端。
 */
@Service
public class AdminClassroomService {

    /** 内部操作日志文案键，控制器记录日志后移除 */
    public static final String LOG_DETAIL_KEY = "_logDetail";

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 教室列表（按教室号/楼栋关键字模糊匹配） */
    public Map<String, Object> listClassrooms(String keyword) {
        Map<String, Object> result = new HashMap<>();
        LambdaQueryWrapper<Classroom> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Classroom::getClassroomNo, keyword).or().like(Classroom::getBuilding, keyword));
        }
        List<Classroom> classrooms = classroomMapper.selectList(wrapper);
        result.put("success", true);
        result.put("classrooms", classrooms);
        return result;
    }

    /** 新增教室：{classroomNo, building, capacity?, roomType?, status?, description?} */
    public Map<String, Object> addClassroom(Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();

        String classroomNo = body.get("classroomNo") == null ? "" : String.valueOf(body.get("classroomNo")).trim();
        String building = body.get("building") == null ? "" : String.valueOf(body.get("building")).trim();
        result.put(LOG_DETAIL_KEY, "新增教室：" + building + "-" + classroomNo);
        if (classroomNo.isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写教室号");
            return result;
        }
        if (classroomNo.length() > 50) {
            result.put("success", false);
            result.put("message", "教室号不能超过 50 字");
            return result;
        }
        if (building.isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写楼栋");
            return result;
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM classroom WHERE classroom_no = ?", Integer.class, classroomNo);
        if (exists != null && exists > 0) {
            result.put("success", false);
            result.put("message", "教室「" + classroomNo + "」已存在");
            return result;
        }

        Classroom classroom = new Classroom();
        classroom.setClassroomNo(classroomNo);
        classroom.setBuilding(building);
        try {
            if (body.get("capacity") != null && !String.valueOf(body.get("capacity")).trim().isEmpty()) {
                int capacity = Integer.parseInt(String.valueOf(body.get("capacity")).trim());
                if (capacity < 0) {
                    result.put("success", false);
                    result.put("message", "容量不能为负数");
                    return result;
                }
                classroom.setCapacity(capacity);
            }
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "容量必须为数字");
            return result;
        }
        String roomType = body.get("roomType") == null ? "" : String.valueOf(body.get("roomType")).trim();
        if (roomType.length() > 30) {
            result.put("success", false);
            result.put("message", "教室类型不能超过 30 字");
            return result;
        }
        classroom.setRoomType(roomType.isEmpty() ? "普通教室" : roomType);
        String status = body.get("status") == null ? "available" : String.valueOf(body.get("status")).trim();
        classroom.setStatus(status.isEmpty() ? "available" : status);
        classroom.setDescription(body.get("description") == null ? null : String.valueOf(body.get("description")).trim());

        try {
            classroomMapper.insert(classroom);
            result.put("success", true);
            result.put("message", "教室添加成功");
            result.put("id", classroom.getId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "添加失败，请稍后重试");
        }
        return result;
    }

    /** 删除课程库课程：仅允许删除未被任何课表条目引用的课程，防止排课数据悬空 */
    public Map<String, Object> deleteCourse(Long id) {
        Map<String, Object> result = new HashMap<>();
        String courseName = null;
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT course_name FROM course WHERE id = ?", String.class, id);
            courseName = names.isEmpty() ? null : names.get(0);
        } catch (Exception ignored) {
        }
        result.put(LOG_DETAIL_KEY, "删除课程：" + (courseName != null ? courseName : "#" + id));
        try {
            Integer courseExists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM course WHERE id = ?", Integer.class, id);
            if (courseExists == null || courseExists == 0) {
                result.put("success", false);
                result.put("message", "课程不存在");
                return result;
            }
            Integer refs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schedule WHERE course_id = ?", Integer.class, id);
            if (refs != null && refs > 0) {
                result.put("success", false);
                result.put("message", "该课程已被 " + refs + " 条课表引用，不可删除");
                return result;
            }
            jdbcTemplate.update("DELETE FROM course WHERE id = ?", id);
            result.put("success", true);
            result.put("message", "课程已删除");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败，请稍后重试");
        }
        return result;
    }

    /** 删除教室：被课表或预约引用的教室禁止删除，避免外键约束异常与数据悬空 */
    public Map<String, Object> deleteClassroom(Long id) {
        Map<String, Object> result = new HashMap<>();
        Classroom target = classroomMapper.selectById(id);
        result.put(LOG_DETAIL_KEY, "删除教室：" + (target != null ? target.getBuilding() + "-" + target.getClassroomNo() : "#" + id));

        try {
            if (target == null) {
                result.put("success", false);
                result.put("message", "教室不存在");
                return result;
            }
            Integer schedRefs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schedule WHERE classroom_id = ?", Integer.class, id);
            Integer rsvRefs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM classroom_reservation WHERE classroom_id = ?", Integer.class, id);
            int s = schedRefs == null ? 0 : schedRefs;
            int r = rsvRefs == null ? 0 : rsvRefs;
            if (s > 0 || r > 0) {
                StringBuilder msg = new StringBuilder("该教室已被引用，不可删除（");
                if (s > 0) msg.append("课表 ").append(s).append(" 条");
                if (s > 0 && r > 0) msg.append("，");
                if (r > 0) msg.append("预约 ").append(r).append(" 条");
                msg.append("）");
                result.put("success", false);
                result.put("message", msg.toString());
                return result;
            }
            classroomMapper.deleteById(id);
            result.put("success", true);
            result.put("message", "删除成功");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败，请稍后重试");
        }
        return result;
    }

    /** 修改教室：按传入字段局部覆盖，容量需为非负数字 */
    public Map<String, Object> updateClassroom(Long id, Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();

        Classroom classroom = classroomMapper.selectById(id);
        if (classroom == null) {
            result.put("success", false);
            result.put("message", "教室不存在");
            result.put(LOG_DETAIL_KEY, "修改教室 #" + id);
            return result;
        }

        if (body.get("classroomNo") != null) classroom.setClassroomNo((String) body.get("classroomNo"));
        if (body.get("building") != null) classroom.setBuilding((String) body.get("building"));
        if (body.get("capacity") != null && !String.valueOf(body.get("capacity")).trim().isEmpty()) {
            int capacity;
            try {
                capacity = Integer.parseInt(String.valueOf(body.get("capacity")).trim());
            } catch (NumberFormatException e) {
                result.put("success", false);
                result.put("message", "容量必须为数字");
                result.put(LOG_DETAIL_KEY, "修改教室 #" + id);
                return result;
            }
            if (capacity < 0) {
                result.put("success", false);
                result.put("message", "容量不能为负数");
                result.put(LOG_DETAIL_KEY, "修改教室 #" + id);
                return result;
            }
            classroom.setCapacity(capacity);
        }
        if (body.get("roomType") != null) {
            String roomType = String.valueOf(body.get("roomType")).trim();
            classroom.setRoomType(roomType.isEmpty() ? "普通教室" : roomType);
        }
        if (body.get("status") != null) classroom.setStatus((String) body.get("status"));
        if (body.get("description") != null) classroom.setDescription((String) body.get("description"));

        result.put(LOG_DETAIL_KEY, "修改教室：" + classroom.getBuilding() + "-" + classroom.getClassroomNo());

        try {
            classroomMapper.updateById(classroom);
            result.put("success", true);
            result.put("message", "修改成功");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "修改失败，请稍后重试");
        }
        return result;
    }
}
