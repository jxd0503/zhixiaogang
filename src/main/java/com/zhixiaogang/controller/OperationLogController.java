package com.zhixiaogang.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhixiaogang.entity.OperationLog;
import com.zhixiaogang.mapper.OperationLogMapper;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.SystemConfigService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志查询（仅管理端）：只读，不提供修改/删除接口，清理只由定时任务执行。
 */
@RestController
@RequestMapping("/admin/operation-logs")
public class OperationLogController {

    private static final Logger log = LoggerFactory.getLogger(OperationLogController.class);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int EXPORT_LIMIT = 50000;

    @Autowired
    private OperationLogMapper operationLogMapper;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private SystemConfigService systemConfigService;

    /** 分页查询：按角色 / 操作人 / 操作类型 / 结果 / 时间范围筛选 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String role,
                                    @RequestParam(required = false) String username,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) String result,
                                    @RequestParam(required = false) String startTime,
                                    @RequestParam(required = false) String endTime,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> response = new HashMap<>();
        try {
            Page<OperationLog> pager = new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100));
            operationLogMapper.selectPage(pager, buildQuery(role, username, action, result, startTime, endTime));
            response.put("success", true);
            response.put("total", pager.getTotal());
            response.put("page", pager.getCurrent());
            response.put("size", pager.getSize());
            response.put("data", pager.getRecords());
        } catch (Exception e) {
            log.error("查询操作日志失败", e);
            response.put("success", false);
            response.put("message", "查询操作日志失败，请稍后重试");
        }
        return response;
    }

    /** 导出 CSV（导出本身也记一条日志） */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String role,
                       @RequestParam(required = false) String username,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String result,
                       @RequestParam(required = false) String startTime,
                       @RequestParam(required = false) String endTime,
                       HttpServletResponse response) {
        int exported = 0;
        try {
            Page<OperationLog> pager = new Page<>(1, EXPORT_LIMIT);
            List<OperationLog> rows = operationLogMapper
                    .selectPage(pager, buildQuery(role, username, action, result, startTime, endTime))
                    .getRecords();
            exported = rows.size();

            String fileName = "operation_log_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\""
                    + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
            PrintWriter writer = response.getWriter();
            writer.write('\ufeff');
            writer.write("id,操作人,角色,操作类型,对象类型,对象ID,详情,结果,IP,耗时(ms),操作时间\n");
            for (OperationLog log : rows) {
                writer.write(csv(log.getId()));
                writer.write(',');
                writer.write(csv(log.getUsername()));
                writer.write(',');
                writer.write(csv(roleName(log.getRole())));
                writer.write(',');
                writer.write(csv(actionName(log.getAction())));
                writer.write(',');
                writer.write(csv(log.getTargetType()));
                writer.write(',');
                writer.write(csv(log.getTargetId()));
                writer.write(',');
                writer.write(csv(log.getDetail()));
                writer.write(',');
                writer.write(csv(log.getResult()));
                writer.write(',');
                writer.write(csv(log.getIp()));
                writer.write(',');
                writer.write(csv(log.getCostMs()));
                writer.write(',');
                writer.write(csv(log.getCreateTime() == null ? "" : log.getCreateTime().format(DATE_TIME)));
                writer.write('\n');
            }
            writer.flush();

            operationLogService.record("export", "operation_log", null,
                    "导出操作日志 " + exported + " 条", true);
        } catch (Exception e) {
            operationLogService.record("export", "operation_log", null,
                    "导出操作日志失败", false, e.getMessage());
        }
    }

    /** 读取当前日志保留策略与读操作开关 */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("retentionDays", systemConfigService.getInt("operation_log.retention_days", 90));
        response.put("recordAllReads", systemConfigService.getBoolean("operation_log.record_all_reads", false));
        response.put("fileEnabled", systemConfigService.getBoolean("operation_log.file_enabled", true));
        response.put("fileKeepDays", systemConfigService.getInt("operation_log.file_keep_days", 30));
        response.put("archiveEnabled", systemConfigService.getBoolean("operation_log.archive_enabled", false));
        return response;
    }

    /** 更新日志保留策略与读操作开关 */
    @PostMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            int retentionDays = intValue(body.get("retentionDays"), 90);
            if (retentionDays < 1 || retentionDays > 3650) {
                response.put("success", false);
                response.put("message", "保留天数需在 1-3650 之间");
                return response;
            }
            int fileKeepDays = intValue(body.get("fileKeepDays"), 30);
            if (fileKeepDays < 1 || fileKeepDays > 3650) {
                response.put("success", false);
                response.put("message", "文件保留天数需在 1-3650 之间");
                return response;
            }
            boolean recordAllReads = boolValue(body.get("recordAllReads"));
            boolean fileEnabled = boolValue(body.get("fileEnabled"));
            boolean archiveEnabled = boolValue(body.get("archiveEnabled"));

            systemConfigService.setInt("operation_log.retention_days", retentionDays, "操作日志保留天数");
            systemConfigService.setInt("operation_log.file_keep_days", fileKeepDays, "文件日志保留天数");
            systemConfigService.set("operation_log.record_all_reads", recordAllReads ? "1" : "0", "是否记录所有读操作");
            systemConfigService.set("operation_log.file_enabled", fileEnabled ? "1" : "0", "是否写入文件日志");
            systemConfigService.set("operation_log.archive_enabled", archiveEnabled ? "1" : "0", "清理前是否归档 CSV");

            operationLogService.record("update", "operation_log_config", null,
                    "修改日志配置：保留 " + retentionDays + " 天，记录读操作=" + (recordAllReads ? "开" : "关")
                            + "，文件日志=" + (fileEnabled ? "开" : "关") + "，归档=" + (archiveEnabled ? "开" : "关"),
                    true);
            response.put("success", true);
            response.put("message", "配置已保存");
        } catch (Exception e) {
            log.error("保存日志配置失败", e);
            response.put("success", false);
            response.put("message", "保存配置失败，请稍后重试");
        }
        return response;
    }

    // ==================== 内部工具 ====================

    private LambdaQueryWrapper<OperationLog> buildQuery(String role, String username, String action,
                                                        String result, String startTime, String endTime) {
        LambdaQueryWrapper<OperationLog> query = new LambdaQueryWrapper<>();
        if (role != null && !role.isBlank()) {
            query.eq(OperationLog::getRole, role.trim());
        }
        if (username != null && !username.isBlank()) {
            query.like(OperationLog::getUsername, username.trim());
        }
        if (action != null && !action.isBlank()) {
            query.eq(OperationLog::getAction, action.trim());
        }
        if ("success".equalsIgnoreCase(result)) {
            query.likeRight(OperationLog::getResult, "success");
        } else if ("failed".equalsIgnoreCase(result)) {
            query.likeRight(OperationLog::getResult, "failed");
        }
        LocalDateTime start = parseTime(startTime, false);
        if (start != null) {
            query.ge(OperationLog::getCreateTime, start);
        }
        LocalDateTime end = parseTime(endTime, true);
        if (end != null) {
            query.le(OperationLog::getCreateTime, end);
        }
        return query.orderByDesc(OperationLog::getCreateTime).orderByDesc(OperationLog::getId);
    }

    private LocalDateTime parseTime(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            String normalized = value.replace('T', ' ');
            // 兼容 <input type="datetime-local"> 的 yyyy-MM-ddTHH:mm
            if (normalized.length() == 16) {
                normalized = normalized + ":00";
            }
            return LocalDateTime.parse(normalized, DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean boolValue(Object value) {
        return value != null && ("true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)));
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    private String roleName(String role) {
        if (role == null) {
            return "";
        }
        return switch (role) {
            case "student" -> "学生";
            case "teacher" -> "教师";
            case "admin" -> "管理员";
            default -> role;
        };
    }

    private String actionName(String action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case "login" -> "登录";
            case "logout" -> "登出";
            case "register" -> "注册";
            case "apply" -> "申请";
            case "approve" -> "批准";
            case "force-approve" -> "强制放行";
            case "reject" -> "拒绝";
            case "cancel" -> "取消";
            case "import" -> "导入";
            case "export" -> "导出";
            case "generate" -> "生成";
            case "confirm" -> "确认";
            case "create" -> "新增";
            case "update" -> "修改";
            case "delete" -> "删除";
            case "read" -> "已读";
            case "send" -> "发送";
            case "reserve" -> "预约";
            case "query" -> "查询";
            default -> action;
        };
    }
}
