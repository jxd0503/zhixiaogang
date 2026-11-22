package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.TeacherClassService;
import com.zhixiaogang.service.TeacherCourseService;
import com.zhixiaogang.service.adjustment.AdjustmentAttachmentService;
import com.zhixiaogang.service.adjustment.AdjustmentSwapService;
import com.zhixiaogang.service.adjustment.ScheduleAdjustmentService;
import com.zhixiaogang.service.classroom.ClassroomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 教师端 API：/api/teacher（课程管理 / 空教室查询 / 预约 / 班级管理）
 */
@RestController
@RequestMapping("/api/teacher")
public class TeacherApiController {

    private static final Logger log = LoggerFactory.getLogger(TeacherApiController.class);

    @Autowired
    private TeacherCourseService teacherCourseService;

    @Autowired
    private ClassroomService classroomService;

    @Autowired
    private TeacherClassService teacherClassService;

    @Autowired
    private ScheduleAdjustmentService scheduleAdjustmentService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private AdjustmentAttachmentService adjustmentAttachmentService;

    @Autowired
    private AdjustmentSwapService adjustmentSwapService;

    /**
     * 查询当前教师本学期的授课安排（按 teacher_id 过滤，teacher_id 为空的历史数据按 teacher_name 兼容）
     */
    @GetMapping("/courses")
    public Map<String, Object> getCourses(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        if (!"teacher".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            result.put("success", false);
            result.put("message", "无权访问");
            return result;
        }
        return teacherCourseService.getCourses(user.getName(), user.getStudentId());
    }

    /**
     * 教师课表查询（只读，课表视图）：返回该教师授课的全部班级课表，按 week 过滤。
     */
    @GetMapping("/schedule/load")
    public Map<String, Object> loadSchedule(@RequestParam(required = false) Integer week, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return teacherCourseService.getSchedule(user.getName(), user.getStudentId(), week);
    }

    /**
     * 查询指定时段（星期 × 节次）的空教室：参数 weekDay、startPeriod、endPeriod 均可空（默认全部时段）
     */
    @GetMapping("/empty-classrooms")
    public Map<String, Object> emptyClassrooms(@RequestParam(required = false) Integer weekDay,
                                               @RequestParam(required = false) Integer startPeriod,
                                               @RequestParam(required = false) Integer endPeriod,
                                               HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        if (!"teacher".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            result.put("success", false);
            result.put("message", "无权访问");
            return result;
        }
        return classroomService.getEmptyClassrooms(weekDay, startPeriod, endPeriod);
    }

    /**
     * 教师预约教室：复用 ClassroomService.reserveClassroom
     * classroom_reservation.student_id 存教师工号、student_name 存教师姓名、status=pending
     */
    @PostMapping("/reserve-classroom")
    public Map<String, Object> reserveClassroom(@RequestBody Map<String, Object> request, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        if (!"teacher".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            result.put("success", false);
            result.put("message", "无权访问");
            return result;
        }

        Long classroomId = null;
        try {
            classroomId = ((Number) request.get("classroomId")).longValue();
            String reason = (String) request.get("reason");
            LocalDate reservationDate = LocalDate.parse((String) request.get("reservationDate"));
            Integer startPeriod = request.get("startPeriod") == null ? null
                    : ((Number) request.get("startPeriod")).intValue();
            Integer endPeriod = request.get("endPeriod") == null ? null
                    : ((Number) request.get("endPeriod")).intValue();

            Map<String, Object> res = classroomService.reserveClassroom(classroomId, user.getStudentId(),
                    user.getName(), reason, reservationDate, startPeriod, endPeriod);
            return logged(res, "reserve", "classroom", classroomId,
                    "预约教室 #" + classroomId + " " + reservationDate + " 第" + startPeriod + "-" + endPeriod + "节");
        } catch (Exception e) {
            log.warn("教室预约参数解析失败: {}", e.getClass().getSimpleName());
            result.put("success", false);
            result.put("message", "请求参数格式不正确，请检查后重试");
            return logged(result, "reserve", "classroom", classroomId, "预约教室失败");
        }
    }

    // ==================== 调课申请 ====================

    /** 教师端实时校验单条调课明细（目标时段教室排课+已批准预约、班级排课、教师排课三方占用） */
    @PostMapping("/adjustments/check")
    public Map<String, Object> checkAdjustment(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return scheduleAdjustmentService.checkDetail(body, user);
    }

    /** 教师端发起调课申请（可含多条明细），body {reason, details:[...]} */
    @PostMapping("/adjustments/apply")
    public Map<String, Object> applyAdjustment(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        Object details = body.get("details");
        int count = details instanceof java.util.List ? ((java.util.List<?>) details).size() : 0;
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        Map<String, Object> res = scheduleAdjustmentService.apply(body, user);
        Object targetId = res == null ? null : res.get("id");
        if (targetId == null && res != null && res.get("adjustments") instanceof java.util.List<?> ids && !ids.isEmpty()) {
            targetId = ids.get(0);
        }
        return logged(res, "apply", "adjustment", targetId,
                "提交调课申请 " + count + " 条：" + reason);
    }

    /** 互换候选：同周次范围内其他教师的课程（不同教师、对方账号启用） */
    @GetMapping("/adjustments/swap/peers")
    public Map<String, Object> swapPeerCourses(@RequestParam Long scheduleId, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return adjustmentSwapService.peerCourses(scheduleId, user);
    }

    /** 发起教师互换：body {scheduleId, peerScheduleId, reason}，等待对方教师确认 */
    @PostMapping("/adjustments/swap")
    public Map<String, Object> applySwap(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        Long scheduleId = parseLong(body.get("scheduleId"));
        Long peerScheduleId = parseLong(body.get("peerScheduleId"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        Map<String, Object> res = adjustmentSwapService.applySwap(scheduleId, peerScheduleId, reason, user);
        return logged(res, "apply", "adjustment", res == null ? null : res.get("id"),
                "发起教师互换申请：" + reason);
    }

    /** 对方教师确认互换 */
    @PostMapping("/adjustments/{id}/confirm-swap")
    public Map<String, Object> confirmSwap(@PathVariable Long id, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return logged(adjustmentSwapService.confirmSwap(id, user), "update", "adjustment", id,
                "确认教师互换 #" + id);
    }

    private static Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 教师端本人调课申请列表（待审核 / 已批准 / 已拒绝 / 已撤销，分页） */
    @GetMapping("/adjustments")
    public Map<String, Object> myAdjustments(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return scheduleAdjustmentService.myAdjustments(user.getStudentId(), user.getName(), page, size);
    }

    /** 教师端撤回待审核申请（pending→cancelled） */
    @PostMapping("/adjustments/{id}/withdraw")
    public Map<String, Object> withdrawAdjustment(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, Object> body,
                                                  HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        Map<String, Object> res = scheduleAdjustmentService.withdraw(id, user, reason);
        return logged(res, "cancel", "adjustment", id,
                "撤回调课申请 #" + id + "（状态：待审批→已撤销）" + (reason == null ? "" : "：" + reason));
    }

    /** 教师端撤销调课申请：待审批直接撤销；已批准则生成撤销审批申请，流转至管理端待审批 */
    @PostMapping("/adjustments/{id}/cancel-request")
    public Map<String, Object> cancelAdjustmentRequest(@PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        Map<String, Object> res = scheduleAdjustmentService.requestCancel(id, user, reason);
        String change = res == null || res.get("statusChange") == null ? "" : String.valueOf(res.get("statusChange"));
        return logged(res, "apply", "adjustment", id,
                "撤销调课申请 #" + id + (change.isEmpty() ? "" : "（状态：" + change + "）")
                        + (reason == null ? "" : "：" + reason));
    }

    /** 教师端紧急回滚已批准调课：立即恢复原课表并留痕，管理员事后补审确认知悉 */
    @PostMapping("/adjustments/{id}/emergency-rollback")
    public Map<String, Object> emergencyRollback(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return logged(scheduleAdjustmentService.emergencyRollback(id, user, reason),
                "emergency-rollback", "adjustment", id,
                "教师紧急回滚调课 #" + id + "（已批准→紧急回滚，课表恢复原时段）"
                        + (reason == null ? "" : "，原因：" + reason));
    }

    /** AI 智能推荐目标时段（本地计算候选 + AI 排序筛选，超时/失败降级为本地结果） */
    @PostMapping("/adjustments/ai-suggest")
    public Map<String, Object> aiSuggest(@RequestBody Map<String, Object> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return scheduleAdjustmentService.aiSuggest(body, user);
    }

    /** 可选教室列表（状态 available，供换教室手动选择） */
    @GetMapping("/classrooms")
    public Map<String, Object> classrooms(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        result.put("success", true);
        result.put("classrooms", scheduleAdjustmentService.classroomOptions());
        return result;
    }

    /** 调课凭证附件上传（图片/PDF，单个≤5MB），返回附件 id 供提交时绑定 */
    @PostMapping("/adjustments/attachments")
    public Map<String, Object> uploadAdjustmentAttachment(@RequestParam("file") MultipartFile file,
                                                          HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) {
            return result;
        }
        return adjustmentAttachmentService.upload(file, user.getStudentId());
    }

    /** 可选代课教师列表（启用状态的教师账号） */
    @GetMapping("/teacher-options")
    public Map<String, Object> teacherOptions(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        result.put("success", true);
        result.put("teachers", scheduleAdjustmentService.teacherOptions());
        return result;
    }

    // ==================== 班级管理 ====================

    /** 当前教师管理的班级列表（class_info.teacher_id=当前教师工号） */
    @GetMapping("/classes")
    public Map<String, Object> myClasses(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return teacherClassService.getMyClasses(user.getStudentId());
    }

    /** 班级学生名单（status=approved，按姓名排序） */
    @GetMapping("/classes/{id}/students")
    public Map<String, Object> classStudents(@PathVariable Long id, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return teacherClassService.getStudents(id, user.getStudentId());
    }

    /** 班级申请列表（加入 pending + 退班 withdraw_pending） */
    @GetMapping("/classes/{id}/applications")
    public Map<String, Object> classApplications(@PathVariable Long id, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return teacherClassService.getApplications(id, user.getStudentId());
    }

    /** 通过加入申请（pending→approved） */
    @PostMapping("/applications/{id}/approve")
    public Map<String, Object> approveApplication(@PathVariable Long id, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return logged(teacherClassService.approveApplication(id, user.getStudentId()),
                "approve", "class_application", id, "通过加入班级申请 #" + id);
    }

    /** 拒绝加入申请（pending→rejected） */
    @PostMapping("/applications/{id}/reject")
    public Map<String, Object> rejectApplication(@PathVariable Long id, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return logged(teacherClassService.rejectApplication(id, user.getStudentId()),
                "reject", "class_application", id, "拒绝加入班级申请 #" + id);
    }

    /** 同意退班（withdraw_pending→withdrawn） */
    @PostMapping("/applications/{id}/confirm-withdraw")
    public Map<String, Object> confirmWithdraw(@PathVariable Long id, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return logged(teacherClassService.confirmWithdraw(id, user.getStudentId()),
                "approve", "class_application", id, "同意退班申请 #" + id);
    }

    /** 踢出学生（approved/withdraw_pending→withdrawn，强制退班） */
    @PostMapping("/classes/{id}/students/{studentId}/remove")
    public Map<String, Object> removeStudent(@PathVariable Long id, @PathVariable String studentId,
                                             HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        User user = requireTeacher(session, result);
        if (user == null) return result;
        return logged(teacherClassService.removeStudent(id, studentId, user.getStudentId()),
                "delete", "class_member", id, "班级 #" + id + " 移除学生：" + studentId);
    }

    /** 记录日志后返回业务结果 */
    private Map<String, Object> logged(Map<String, Object> result, String action, String targetType,
                                       Object targetId, String detail) {
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action, targetType, targetId, detail,
                ok, ok ? null : (result == null ? null : String.valueOf(result.get("message"))));
        return result;
    }

    /** 统一的教师身份校验：返回 user（通过）或 null（已写入失败结果） */
    private User requireTeacher(HttpSession session, Map<String, Object> result) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return null;
        }
        if (!"teacher".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            result.put("success", false);
            result.put("message", "无权访问");
            return null;
        }
        return user;
    }
}
