package com.zhixiaogang.controller;

import com.zhixiaogang.entity.User;
import com.zhixiaogang.service.OperationLogService;
import com.zhixiaogang.service.adjustment.AdjustmentAttachmentCleanupService;
import com.zhixiaogang.service.adjustment.AdjustmentAttachmentService;
import com.zhixiaogang.service.adjustment.AdjustmentAuditLogger;
import com.zhixiaogang.service.adjustment.ScheduleAdjustmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 调课审批接口（仅管理端可访问，受 AdminAuthInterceptor 保护）。
 * 批准时二次校验（与教师端发起同一口径），冲突则拒绝并提示具体来源；已批准可撤销。
 */
@RestController
@RequestMapping("/admin/adjustments")
public class ScheduleAdjustmentController {

    @Autowired
    private ScheduleAdjustmentService scheduleAdjustmentService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private AdjustmentAttachmentService attachmentService;

    @Autowired
    private AdjustmentAuditLogger auditLogger;

    @Autowired
    private AdjustmentAttachmentCleanupService attachmentCleanupService;

    /** 手动触发附件孤儿清理（引用计数为 0 的文件 + 超期已关闭申请的附件行） */
    @PostMapping("/attachments/cleanup")
    public Map<String, Object> cleanupAttachments() {
        Map<String, Object> result = attachmentCleanupService.cleanup();
        return logged(result, "cleanup", "adjustment", null, "手动清理调课孤儿附件");
    }

    /** 调课凭证附件列表（管理端审批时预览/下载） */
    @GetMapping("/{id}/attachments")
    public Map<String, Object> attachments(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("attachments", attachmentService.listByAdjustment(id));
        return result;
    }

    /** 附件文件流：图片/PDF 浏览器内联预览，其他类型按附件下载 */
    @GetMapping("/attachments/{attachmentId}/file")
    public ResponseEntity<Resource> attachmentFile(@PathVariable Long attachmentId) {
        Map<String, Object> meta = attachmentService.load(attachmentId);
        if (meta == null || !(meta.get("path") instanceof Path path)) {
            return ResponseEntity.notFound().build();
        }
        String name = String.valueOf(meta.get("original_name"));
        String type = String.valueOf(meta.get("content_type"));
        boolean inline = type.startsWith("image/") || "application/pdf".equals(type);
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded)
                .body(new FileSystemResource(path));
    }

    /** 审批动作流水（谁、何时、什么动作、看到的冲突、强制放行或紧急回滚理由），供管理端追溯 */
    @GetMapping("/{id}/logs")
    public Map<String, Object> logs(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("logs", auditLogger.listByAdjustment(id));
        return result;
    }

    /** 调课历史列表（含已撤销 / 已拒绝，可按状态筛选，分页） */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return scheduleAdjustmentService.adminList(status, page, size);
    }

    /** 批准（二次校验；教室软冲突返回 needConfirm，管理员确认并填写理由后传 force=true 知情放行） */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpSession session) {
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"))
                || body != null && "true".equalsIgnoreCase(String.valueOf(body.get("force")));
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        Map<String, Object> result = scheduleAdjustmentService.approve(id, approverName(session), force, reason);
        boolean forced = result != null && Boolean.TRUE.equals(result.get("forced"));
        String reasonText = reason == null ? "" : reason.trim();
        // 操作日志 detail 上限 100 字，理由截取前 50 字保证完整可见
        if (reasonText.length() > 50) {
            reasonText = reasonText.substring(0, 50) + "…";
        }
        String detail = "批准调课申请 #" + id
                + (forced ? "（强制放行教室冲突，理由：" + reasonText + "）" : "");
        // 强制放行使用独立 action，避免与同一秒内的普通批准日志被去重吞掉
        return logged(result, forced ? "force-approve" : "approve", "adjustment", id, detail);
    }

    /** 重新校验冲突（按目标时段 + 换教室后的目标教室，查询教师/班级/教室三方占用） */
    @GetMapping("/{id}/conflict-check")
    public Map<String, Object> conflictCheck(@PathVariable Long id, HttpSession session) {
        return scheduleAdjustmentService.conflictCheck(id, approverName(session));
    }

    /** 拒绝（填写拒绝原因） */
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpSession session) {
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return logged(scheduleAdjustmentService.reject(id, approverName(session), reason),
                "reject", "adjustment", id, "拒绝调课申请 #" + id + (reason == null ? "" : "：" + reason));
    }

    /** 撤销已批准调课（填写原因），撤销后课表恢复原样 */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      HttpSession session) {
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return logged(scheduleAdjustmentService.cancel(id, approverName(session), reason),
                "cancel", "adjustment", id, "撤销调课申请 #" + id + "（状态：已批准→已撤销）"
                        + (reason == null ? "" : "：" + reason));
    }

    /** 同意教师的撤销调课申请（撤销待审批→已撤销，课表恢复原时段） */
    @PostMapping("/{id}/cancel-approve")
    public Map<String, Object> cancelApprove(@PathVariable Long id, HttpSession session) {
        return logged(scheduleAdjustmentService.approveCancel(id, approverName(session)),
                "approve", "adjustment", id, "同意撤销调课申请 #" + id + "（状态：撤销待审批→已撤销）");
    }

    /** 拒绝教师的撤销调课申请（撤销待审批→撤销被拒，调课保持生效） */
    @PostMapping("/{id}/cancel-reject")
    public Map<String, Object> cancelReject(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpSession session) {
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return logged(scheduleAdjustmentService.rejectCancel(id, approverName(session), reason),
                "reject", "adjustment", id, "拒绝撤销调课申请 #" + id + "（状态：撤销待审批→撤销被拒）"
                        + (reason == null ? "" : "：" + reason));
    }

    /** 紧急回滚事后补审：仅确认知悉并写流水，不改变调课状态与课表 */
    @PostMapping("/{id}/emergency-review")
    public Map<String, Object> emergencyReview(@PathVariable Long id, HttpSession session) {
        return logged(scheduleAdjustmentService.acknowledgeEmergency(id, approverName(session)),
                "emergency-review", "adjustment", id, "管理员确认知悉紧急回滚调课 #" + id);
    }

    /** 记录日志后返回业务结果 */
    private Map<String, Object> logged(Map<String, Object> result, String action, String targetType,
                                       Object targetId, String detail) {
        boolean ok = result != null && Boolean.TRUE.equals(result.get("success"));
        operationLogService.record(action, targetType, targetId, detail,
                ok, ok ? null : (result == null ? null : String.valueOf(result.get("message"))));
        return result;
    }

    private String approverName(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "admin";
        }
        return user.getName() != null ? user.getName() : user.getStudentId();
    }
}
