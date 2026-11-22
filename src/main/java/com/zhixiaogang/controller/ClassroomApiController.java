package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.classroom.ClassroomService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 三端通用教室课表接口（学生 / 教师 / 管理员登录即可访问，不区分角色）。
 * 复用 ClassroomService 的占用聚合，口径与管理端占用视图、空教室查询一致：
 * 只统计 schedule 表 classroom_id 匹配的条目；无教室课程（classroom_id IS NULL，体育课等）不参与。
 */
@RestController
@RequestMapping("/api/classrooms")
public class ClassroomApiController {

    @Autowired
    private ClassroomService classroomService;

    /** 全部教室一周课表概览（教室号、楼栋、课表占用节次数） */
    @GetMapping("/occupancy")
    public Map<String, Object> allOccupancy(@RequestParam(required = false) Integer week, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return Map.of("success", false, "message", "请先登录");
        }
        return classroomService.getAllClassroomsOccupancy(week);
    }

    /** 单个教室一周课表明细（教室信息 + 按星期 / 节次排序的课表占用条目，含已批准预约来源） */
    @GetMapping("/{id}/occupancy")
    public Map<String, Object> classroomOccupancy(@PathVariable Long id,
                                                  @RequestParam(required = false) Integer week,
                                                  HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return Map.of("success", false, "message", "请先登录");
        }
        return classroomService.getClassroomOccupancy(id, week);
    }
}
