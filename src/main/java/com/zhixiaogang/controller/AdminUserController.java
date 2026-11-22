package com.zhixiaogang.controller;

import com.zhixiaogang.service.AdminUserService;
import com.zhixiaogang.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 管理端用户管理：用户列表、修改与删除。 */
@Controller
@RequestMapping("/admin")
public class AdminUserController {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private OperationLogService operationLogService;

    @GetMapping("/users")
    @ResponseBody
    public Map<String, Object> getUsers(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String department,
                                        @RequestParam(required = false) String grade,
                                        @RequestParam(required = false) String major,
                                        @RequestParam(required = false) String role,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return adminUserService.listUsers(keyword, department, grade, major, role, page, size);
    }

    @DeleteMapping("/users/{studentId}")
    @ResponseBody
    public Map<String, Object> deleteUser(@PathVariable String studentId) {
        Map<String, Object> result = adminUserService.deleteUser(studentId);
        AdminWebSupport.logAction(operationLogService, "delete", "user", studentId, "删除用户：" + studentId, result);
        return result;
    }

    @PutMapping("/users/{studentId}")
    @ResponseBody
    public Map<String, Object> updateUser(@PathVariable String studentId, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = adminUserService.updateUser(studentId, body);
        Object logName = result.remove("_userName");
        AdminWebSupport.logAction(operationLogService, "update", "user", studentId, "修改用户：" + studentId + "（"
                + (logName == null ? "" : logName) + "）", result);
        return result;
    }
}
