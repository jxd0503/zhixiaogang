package com.zhixiaogang.controller;

import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.classroom.AdminClassroomService;
import com.zhixiaogang.service.classroom.ClassroomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 管理端教室与课程库管理：教室增删改查、占用概览、课程删除；业务与 SQL 已下沉至 AdminClassroomService。 */
@Controller
@RequestMapping("/admin")
public class AdminClassroomController {

    @Autowired
    private AdminClassroomService adminClassroomService;

    @Autowired
    private ClassroomService classroomService;

    @Autowired
    private OperationLogService operationLogService;

    /** 全部教室一周占用概览（课表占用节次数 + 明细，含已批准预约来源） */
    @GetMapping("/classrooms/occupancy")
    @ResponseBody
    public Map<String, Object> getAllClassroomsOccupancy(@RequestParam(required = false) Integer week) {
        return classroomService.getAllClassroomsOccupancy(week);
    }

    /** 单个教室一周占用明细（课表 + 已批准预约，标注来源） */
    @GetMapping("/classrooms/{id}/occupancy")
    @ResponseBody
    public Map<String, Object> getClassroomOccupancy(@PathVariable Long id,
                                                     @RequestParam(required = false) Integer week) {
        return classroomService.getClassroomOccupancy(id, week);
    }

    @GetMapping("/classrooms")
    @ResponseBody
    public Map<String, Object> getClassrooms(@RequestParam(required = false) String keyword) {
        return adminClassroomService.listClassrooms(keyword);
    }

    /** 新增教室：{classroomNo, building, capacity?, roomType?, status?, description?} */
    @PostMapping("/classrooms")
    @ResponseBody
    public Map<String, Object> addClassroom(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = adminClassroomService.addClassroom(body);
        String logDetail = String.valueOf(result.remove(AdminClassroomService.LOG_DETAIL_KEY));
        return AdminWebSupport.logged(operationLogService, result, "create", "classroom", result.get("id"), logDetail);
    }

    /** 删除课程库课程：仅允许删除未被任何课表条目引用的课程，防止排课数据悬空 */
    @DeleteMapping("/courses/{id}")
    @ResponseBody
    public Map<String, Object> deleteCourse(@PathVariable Long id) {
        Map<String, Object> result = adminClassroomService.deleteCourse(id);
        String logDetail = String.valueOf(result.remove(AdminClassroomService.LOG_DETAIL_KEY));
        return AdminWebSupport.logged(operationLogService, result, "delete", "course", id, logDetail);
    }

    @DeleteMapping("/classrooms/{id}")
    @ResponseBody
    public Map<String, Object> deleteClassroom(@PathVariable Long id) {
        Map<String, Object> result = adminClassroomService.deleteClassroom(id);
        String logDetail = String.valueOf(result.remove(AdminClassroomService.LOG_DETAIL_KEY));
        return AdminWebSupport.logged(operationLogService, result, "delete", "classroom", id, logDetail);
    }

    @PutMapping("/classrooms/{id}")
    @ResponseBody
    public Map<String, Object> updateClassroom(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = adminClassroomService.updateClassroom(id, body);
        String logDetail = String.valueOf(result.remove(AdminClassroomService.LOG_DETAIL_KEY));
        return AdminWebSupport.logged(operationLogService, result, "update", "classroom", id, logDetail);
    }
}
