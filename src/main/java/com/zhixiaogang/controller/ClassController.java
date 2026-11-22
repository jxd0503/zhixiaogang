package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.StudentClassService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** 学生端班级列表、加入/退出班级等接口（业务逻辑见 StudentClassService）。 */
@RestController
@RequestMapping("/api/class")
public class ClassController {

    @Autowired
    private StudentClassService studentClassService;

    @Autowired
    private OperationLogService operationLogService;

    /** 可加入的班级列表（分页，标注当前学生在该班的状态）；keyword 支持班级名/年级/学院/班主任姓名 */
    @GetMapping("/list")
    public Map<String, Object> list(HttpSession session,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) String keyword) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return notLoggedIn();
        }
        return studentClassService.listClasses(user.getStudentId(), page, size, keyword);
    }

    /** 提交加入申请（并发安全由 Service 内行锁 + 唯一键兜底） */
    @PostMapping("/join")
    public Map<String, Object> join(@RequestBody Map<String, Object> request, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return notLoggedIn();
        }

        Object rawClassId = request.get("classId");
        if (rawClassId == null || rawClassId.toString().trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "缺少班级参数");
            return result;
        }
        long classId;
        try {
            classId = Long.parseLong(rawClassId.toString().trim());
        } catch (NumberFormatException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "班级参数格式不正确");
            return result;
        }
        return logAndClean(studentClassService.joinClass(user, classId), classId);
    }

    /** 取消加入申请（仅待审核状态可取消） */
    @PostMapping("/cancel-join")
    public Map<String, Object> cancelJoin(@RequestBody Map<String, Object> request, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return notLoggedIn();
        }
        Long classId = Long.valueOf(request.get("classId").toString());
        return logAndClean(studentClassService.cancelJoin(user.getStudentId(), user.getName(), classId), classId);
    }

    /** 提交退班申请 */
    @PostMapping("/withdraw")
    public Map<String, Object> withdraw(@RequestBody Map<String, Object> request, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return notLoggedIn();
        }
        Long classId = Long.valueOf(request.get("classId").toString());
        return logAndClean(studentClassService.withdraw(user.getStudentId(), user.getName(), classId), classId);
    }

    /** 取消退班申请（仅退班待审核状态可取消，状态恢复为已加入） */
    @PostMapping("/cancel-withdraw")
    public Map<String, Object> cancelWithdraw(@RequestBody Map<String, Object> request, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return notLoggedIn();
        }
        Long classId = Long.valueOf(request.get("classId").toString());
        return logAndClean(studentClassService.cancelWithdraw(user.getStudentId(), user.getName(), classId), classId);
    }

    /** 我的班级信息（当前学生 status=approved 的班级） */
    @GetMapping("/my")
    public Map<String, Object> my(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return notLoggedIn();
        }
        return studentClassService.getMyClass(user.getStudentId());
    }

    private Map<String, Object> notLoggedIn() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "请先登录");
        return result;
    }

    /** 记录操作日志并移除仅供日志使用的内部字段 */
    private Map<String, Object> logAndClean(Map<String, Object> result, Object classId) {
        Object action = result.remove("logAction");
        Object detail = result.remove("logDetail");
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action == null ? "apply" : String.valueOf(action), "class", classId,
                detail == null ? "" : String.valueOf(detail), ok, ok ? null : (String) result.get("message"));
        return result;
    }
}
