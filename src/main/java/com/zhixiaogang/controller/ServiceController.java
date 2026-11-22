package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.KnowledgeBaseService;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.classroom.ClassroomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** 学生端服务入口（教室预约等）页面与接口。 */
@RestController
@RequestMapping("/api/services")
public class ServiceController {

    @Autowired
    private ClassroomService classroomService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private OperationLogService operationLogService;

    @GetMapping("/classrooms")
    public Map<String, Object> getClassrooms(@RequestParam(required = false) String building,
                                            @RequestParam(required = false) Long id) {
        if (id != null) {
            return classroomService.getClassroomById(id);
        }
        if (building != null && !building.isEmpty()) {
            return classroomService.getClassroomsByBuilding(building);
        }
        return classroomService.getAvailableClassrooms();
    }
    
    @GetMapping("/classrooms/next-period")
    public Map<String, Object> getAvailableClassroomsForNextPeriod(@RequestParam(required = false) String building) {
        int nextPeriod = classroomService.getNextPeriod();
        if (nextPeriod == 0) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "当前已过所有上课时间");
            return result;
        }
        return classroomService.getAvailableClassroomsForPeriod(nextPeriod, building);
    }

    @GetMapping("/classrooms/available")
    public Map<String, Object> getAvailableClassroomsByDatePeriod(@RequestParam(required = false) String date,
                                                                  @RequestParam(required = false) Integer period,
                                                                  @RequestParam(required = false) String building) {
        LocalDate queryDate;
        if (date != null && !date.isEmpty()) {
            try {
                queryDate = LocalDate.parse(date);
            } catch (Exception e) {
                // P2-9 日期格式错误给字段级提示，不落 500
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "日期格式不正确，请使用 YYYY-MM-DD 格式");
                return err;
            }
        } else {
            queryDate = LocalDate.now();
        }
        return classroomService.getAvailableClassroomsByDatePeriod(queryDate, period, building);
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    @PostMapping("/classrooms/reserve")
    public Map<String, Object> reserveClassroom(HttpSession session,
                                                @RequestBody Map<String, Object> request) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return fail("请先登录");
        }

        // P2-9 字段级校验
        Object classroomIdObj = request.get("classroomId");
        if (!(classroomIdObj instanceof Number)) {
            return fail("请选择要预约的教室");
        }
        Long classroomId = ((Number) classroomIdObj).longValue();

        String reason = request.get("reason") == null ? "" : String.valueOf(request.get("reason")).trim();
        if (reason.isEmpty()) {
            return fail("请填写预约原因");
        }
        if (reason.length() > 100) {
            return fail("预约原因过长，请控制在 100 字以内");
        }

        Object dateObj = request.get("reservationDate");
        if (!(dateObj instanceof String) || ((String) dateObj).trim().isEmpty()) {
            return fail("请选择预约日期");
        }
        LocalDate reservationDate;
        try {
            reservationDate = LocalDate.parse(((String) dateObj).trim());
        } catch (Exception e) {
            return fail("预约日期格式不正确");
        }

        Object startObj = request.get("startPeriod");
        Object endObj = request.get("endPeriod");
        if (!(startObj instanceof Number) || !(endObj instanceof Number)) {
            return fail("请选择预约节次");
        }
        Integer startPeriod = ((Number) startObj).intValue();
        Integer endPeriod = ((Number) endObj).intValue();
        if (startPeriod < 1 || startPeriod > 5 || endPeriod < 1 || endPeriod > 5) {
            return fail("预约节次不正确");
        }
        if (startPeriod > endPeriod) {
            return fail("开始节次不能大于结束节次");
        }

        Map<String, Object> res = classroomService.reserveClassroom(classroomId, user.getStudentId(),
                user.getName(), reason, reservationDate, startPeriod, endPeriod);
        return logged(res, "reserve", "classroom", classroomId,
                "预约教室 #" + classroomId + " " + reservationDate + " 第" + startPeriod + "-" + endPeriod + "节");
    }

    /** 学生端「我的预约」：分页返回本人预约 */
    @GetMapping("/classrooms/my-reservations")
    public Map<String, Object> getMyReservations(HttpSession session,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return fail("请先登录");
        }
        return classroomService.getStudentReservations(user.getStudentId(), page, size);
    }

    /** 学生端「我的预约」：取消本人待审核预约 */
    @PostMapping("/classrooms/my-reservations/{id}/cancel")
    public Map<String, Object> cancelMyReservation(HttpSession session, @PathVariable Long id) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return fail("请先登录");
        }
        return logged(classroomService.cancelPendingReservation(id, user.getStudentId()),
                "cancel", "classroom_reservation", id, "取消教室预约 #" + id);
    }

    /** 记录日志后返回业务结果 */
    private Map<String, Object> logged(Map<String, Object> result, String action, String targetType,
                                       Object targetId, String detail) {
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action, targetType, targetId, detail,
                ok, ok ? null : (result == null ? null : String.valueOf(result.get("message"))));
        return result;
    }

    @GetMapping("/knowledge")
    public Map<String, Object> getKnowledge(@RequestParam(required = false) String keyword) {
        if (keyword != null && !keyword.isEmpty()) {
            return knowledgeBaseService.searchKnowledge(keyword);
        }
        return knowledgeBaseService.getAllKnowledge();
    }
}
