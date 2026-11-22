package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.ScheduleService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 学生端课表查询与展示接口。 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    @GetMapping("/check")
    public Map<String, Object> checkSchedule(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return Map.of("success", false, "message", "请先登录");
        }
        return scheduleService.checkSchedule(user.getStudentId());
    }

    @GetMapping("/load")
    public Map<String, Object> loadSchedule(@RequestParam Integer week, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return Map.of("success", false, "message", "请先登录");
        }
        return scheduleService.loadSchedule(user.getStudentId(), week);
    }
}
