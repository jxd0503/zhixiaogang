package com.zhixiaogang.controller;

import com.zhixiaogang.entity.SemesterConfig;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.SemesterConfigService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 学期配置 API Controller
 */
@RestController
@RequestMapping("/api/semester")
public class SemesterController {

    private static final Logger log = LoggerFactory.getLogger(SemesterController.class);

    @Autowired
    private SemesterConfigService semesterConfigService;

    @Autowired
    private com.zhixiaogang.service.ScheduleConstraintService scheduleConstraintService;

    @Autowired
    private com.zhixiaogang.service.OperationLogService operationLogService;

    /**
     * 获取当前学期配置与当前周次：登录用户（学生/教师/管理员）均可读，未登录返回 401
     */
    @GetMapping("/current")
    public Map<String, Object> getCurrentSemester(HttpSession session, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            response.setStatus(401);
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        try {
            SemesterConfig config = semesterConfigService.getCurrentSemester();
            if (config == null) {
                result.put("success", true);
                result.put("configured", false);
                result.put("message", "请先配置当前学期");
                return result;
            }

            Object[] weekResult = semesterConfigService.calculateCurrentWeek(config.getStartDate(), config.getWeeks());
            int currentWeek = (int) weekResult[0];
            boolean inRange = (boolean) weekResult[1];

            result.put("success", true);
            result.put("configured", true);
            result.put("id", config.getId());
            result.put("semesterName", config.getSemesterName());
            result.put("startDate", config.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
            result.put("endDate", config.getEndDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
            result.put("weeks", config.getWeeks());
            result.put("currentWeek", currentWeek);
            result.put("inRange", inRange);
        } catch (Exception e) {
            log.error("获取学期配置失败", e);
            result.put("success", false);
            result.put("message", "获取学期配置失败，请稍后重试");
        }
        return result;
    }

    /**
     * 保存学期配置：仅管理员可调用，学生/教师/未登录一律 403 且不落库
     */
    @PostMapping("/save")
    public Map<String, Object> saveSemester(@RequestBody Map<String, Object> body,
                                            HttpSession session, HttpServletResponse response) {
        Map<String, Object> denied = new HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null || !"admin".equals(user.getRole())) {
            response.setStatus(403);
            denied.put("success", false);
            denied.put("message", "需要管理员权限");
            return denied;
        }
        SemesterConfig config = new SemesterConfig();
        if (body.containsKey("id") && body.get("id") != null) {
            String idStr = String.valueOf(body.get("id")).trim();
            try {
                config.setId(Long.parseLong(idStr));
            } catch (Exception e) {
                response.setStatus(400);
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "学期配置 ID 格式不正确");
                return result;
            }
        }
        config.setSemesterName((String) body.get("semesterName"));

        // 解析日期：空 / 非法格式均 400 友好提示
        String startDateStr = (String) body.get("startDate");
        if (startDateStr == null || startDateStr.trim().isEmpty()) {
            response.setStatus(400);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "开始日期不能为空");
            return result;
        }
        try {
            config.setStartDate(LocalDate.parse(startDateStr, DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (Exception e) {
            response.setStatus(400);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "开始日期格式不正确，请选择有效日期");
            return result;
        }

        // 解析周数：必须为合法整数；具体范围（1-40）由 service 层校验并 400
        if (body.containsKey("weeks")) {
            String weeksStr = String.valueOf(body.get("weeks")).trim();
            try {
                config.setWeeks(Integer.parseInt(weeksStr));
            } catch (Exception e) {
                response.setStatus(400);
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "持续周数必须为 1-40 之间的整数");
                return result;
            }
        } else {
            response.setStatus(400);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "持续周数不能为空");
            return result;
        }

        Map<String, Object> saved = semesterConfigService.saveSemester(config);
        boolean ok = !Boolean.FALSE.equals(saved.get("success"));
        if (!ok) {
            response.setStatus(400);
        } else {
            // 学期切换后，非当前学期的已采纳教师排课要求自动失效
            scheduleConstraintService.expireAdopted(null);
        }
        operationLogService.record("update", "semester", config.getId(),
                "保存学期设置：" + config.getSemesterName() + " 开始 " + config.getStartDate()
                        + " 共 " + config.getWeeks() + " 周",
                ok, ok ? null : String.valueOf(saved.get("message")));
        return saved;
    }
}