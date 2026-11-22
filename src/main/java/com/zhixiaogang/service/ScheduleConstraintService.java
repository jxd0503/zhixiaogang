package com.zhixiaogang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixiaogang.entity.ScheduleConstraintRequest;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ScheduleConstraintRequestMapper;
import com.zhixiaogang.service.notification.NotificationService;
import com.zhixiaogang.service.schedule.AiScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师排课要求提交通道：教师提交自然语言排课约束 → 管理端 AI 排课加载填入 → 无法满足时通知教师。
 * 复用 AiScheduleService 的约束解析能力与 NotificationService 的通知能力。
 */
@Service
public class ScheduleConstraintService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleConstraintService.class);

    public static final String ST_PENDING = "PENDING";
    public static final String ST_ADOPTED = "ADOPTED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CANCELLED = "CANCELLED";
    public static final String ST_EXPIRED = "EXPIRED";

    /** 通知类型：教师排课要求相关 */
    public static final String NOTIFY_TYPE = "schedule_constraint";

    @Autowired
    private ScheduleConstraintRequestMapper requestMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AiScheduleService aiScheduleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 教师端 ====================

    /** 教师可选择的班级：本人管理的班级 ∪ 本人有课表的班级 */
    public Map<String, Object> listTeacherClasses(User teacher) {
        Map<String, Object> result = new HashMap<>();
        try {
            String teacherId = teacher.getStudentId() == null ? "" : teacher.getStudentId();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT DISTINCT ci.id, ci.class_name FROM class_info ci WHERE ci.teacher_id = ? "
                            + "OR ci.id IN (SELECT s.class_id FROM schedule s WHERE s.teacher_name = ? AND s.class_id IS NOT NULL) "
                            + "ORDER BY ci.id", teacherId, teacher.getName());
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.get("id"));
                m.put("className", row.get("class_name"));
                list.add(m);
            }
            result.put("success", true);
            result.put("classes", list);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "班级列表加载失败：" + e.getMessage());
            result.put("classes", new ArrayList<>());
        }
        return result;
    }

    /** 提交前试解析：复用 AI 约束解析能力，返回约束条目 / 未识别原文 / 提示 */
    public Map<String, Object> preview(String text, User teacher) {
        return aiScheduleService.previewConstraints(text, teacher.getName());
    }

    /** 教师提交排课要求：保存 PENDING 记录并通知管理员处理 */
    public Map<String, Object> submit(User teacher, String text, String semester, Long classId) {
        Map<String, Object> result = new HashMap<>();
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            result.put("success", false);
            result.put("message", "请填写排课要求");
            return result;
        }
        if (content.length() > 1000) {
            result.put("success", false);
            result.put("message", "排课要求不能超过 1000 字");
            return result;
        }
        try {
            String className = "";
            if (classId != null) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT class_name FROM class_info WHERE id = ?", classId);
                if (rows.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "所选班级不存在");
                    return result;
                }
                className = rows.get(0).get("class_name") == null ? "" : String.valueOf(rows.get(0).get("class_name"));
            }
            String sem = semester == null || semester.trim().isEmpty() ? currentSemesterName() : semester.trim();

            // 合规校验与主语补全：跨教师条目直接拒绝；无主语条目补全本人姓名后落库
            Map<String, Object> preview = aiScheduleService.previewConstraints(content, teacher.getName());
            String normalized;
            String parsedJson;
            if (!Boolean.TRUE.equals(preview.get("success"))) {
                Object removed = preview.get("removed");
                if (removed instanceof List && !((List<?>) removed).isEmpty()) {
                    result.put("success", false);
                    result.put("message", String.valueOf(preview.get("message")));
                    result.put("removed", removed);
                    return result;
                }
                // AI 不可用或解析失败：与页面提示保持一致，允许按原文提交，由管理员在排课页再解析
                normalized = aiScheduleService.normalizeConstraintText(teacher.getName(), content).displayText;
                if (normalized == null || normalized.trim().isEmpty()) {
                    normalized = content;
                }
                parsedJson = "";
            } else {
                normalized = String.valueOf(preview.get("normalizedText"));
                parsedJson = previewJson(preview.get("items"));
            }

            ScheduleConstraintRequest req = new ScheduleConstraintRequest();
            req.setTeacherId(teacher.getId());
            req.setTeacherName(teacher.getName());
            req.setSemester(sem);
            req.setClassId(classId);
            req.setClassName(className);
            req.setConstraintText(normalized);
            req.setStatus(ST_PENDING);
            req.setParsedJson(parsedJson);
            requestMapper.insert(req);

            notifyAdmins("教师提交了排课要求",
                    "教师 " + teacher.getName() + " 提交了排课要求（" + sem
                            + (className.isEmpty() ? "" : "，" + className) + "）：" + normalized
                            + "。请到 AI 排课页「教师已提交排课要求」处理。", req.getId());
            result.put("success", true);
            result.put("message", "提交成功，等待管理员处理");
            result.put("id", req.getId());
            result.put("constraintText", normalized);
            result.put("parsedJson", req.getParsedJson());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "提交失败：" + e.getMessage());
        }
        return result;
    }

    /** 解析条目转 JSON 保存（无条目时为空字符串，不阻断提交） */
    private String previewJson(Object items) {
        try {
            if (items == null) {
                return "";
            }
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            return "";
        }
    }

    /** 我的排课要求（可按学期过滤，最新在前） */
    public Map<String, Object> myList(User teacher, String semester, String status) {
        Map<String, Object> result = new HashMap<>();
        try {
            LambdaQueryWrapper<ScheduleConstraintRequest> qw = new LambdaQueryWrapper<ScheduleConstraintRequest>()
                    .eq(ScheduleConstraintRequest::getTeacherId, teacher.getId());
            if (semester != null && !semester.trim().isEmpty()) {
                qw.eq(ScheduleConstraintRequest::getSemester, semester.trim());
            }
            if (status != null && !status.trim().isEmpty()) {
                qw.eq(ScheduleConstraintRequest::getStatus, status.trim().toUpperCase());
            }
            qw.orderByDesc(ScheduleConstraintRequest::getId);
            List<ScheduleConstraintRequest> rows = requestMapper.selectList(qw);
            result.put("success", true);
            result.put("requests", rows);
            result.put("data", rows);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "加载失败：" + e.getMessage());
            result.put("requests", new ArrayList<>());
        }
        return result;
    }

    /** 本学期本人已采纳（有效）的排课约束清单 */
    public Map<String, Object> myEffective(User teacher, String semester) {
        String sem = semester == null || semester.trim().isEmpty() ? currentSemesterName() : semester.trim();
        Map<String, Object> result = myList(teacher, sem, ST_ADOPTED);
        result.put("semester", sem);
        return result;
    }

    /** 教师撤销待处理约束（单条，仅本人 PENDING） */
    public Map<String, Object> cancel(User teacher, Long id) {
        return batchCancel(teacher, List.of(id));
    }

    /** 教师批量撤销待处理约束：仅本人 PENDING 记录生效，其余跳过并说明 */
    public Map<String, Object> batchCancel(User teacher, List<Long> ids) {
        Map<String, Object> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            result.put("success", false);
            result.put("message", "请选择要撤销的排课要求");
            return result;
        }
        try {
            int count = 0;
            int skipped = 0;
            for (Long id : ids) {
                ScheduleConstraintRequest req = requestMapper.selectById(id);
                if (req == null || !teacher.getId().equals(req.getTeacherId())
                        || !ST_PENDING.equals(req.getStatus())) {
                    skipped++;
                    continue;
                }
                updateStatus(id, ST_CANCELLED, "教师撤销", null);
                notifyAdmins("教师撤回了排课要求",
                        "教师 " + teacher.getName() + " 撤回了排课要求：" + req.getConstraintText(), id);
                count++;
            }
            result.put("success", count > 0);
            result.put("message", count > 0
                    ? "已撤销 " + count + " 条" + (skipped > 0 ? "，" + skipped + " 条非待处理已跳过" : "")
                    : "没有可撤销的待处理排课要求");
            result.put("count", count);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "撤销失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 管理端 ====================

    /** 管理端列表：默认返回待处理 + 已采纳，可按状态 / 学期过滤 */
    public Map<String, Object> adminList(String status, String semester) {
        Map<String, Object> result = new HashMap<>();
        try {
            LambdaQueryWrapper<ScheduleConstraintRequest> qw = new LambdaQueryWrapper<>();
            if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status.trim())) {
                qw.eq(ScheduleConstraintRequest::getStatus, status.trim().toUpperCase());
            } else if (status == null || status.trim().isEmpty()) {
                qw.in(ScheduleConstraintRequest::getStatus, ST_PENDING, ST_ADOPTED);
            }
            if (semester != null && !semester.trim().isEmpty()) {
                qw.eq(ScheduleConstraintRequest::getSemester, semester.trim());
            }
            qw.orderByDesc(ScheduleConstraintRequest::getId);
            List<ScheduleConstraintRequest> rows = requestMapper.selectList(qw);
            result.put("success", true);
            result.put("requests", rows);
            result.put("pendingCount", countByStatus(ST_PENDING));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "加载失败：" + e.getMessage());
            result.put("requests", new ArrayList<>());
            result.put("pendingCount", 0);
        }
        return result;
    }

    /** 待处理数量（AI 排课页角标） */
    public Map<String, Object> pendingCount() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", countByStatus(ST_PENDING));
        return result;
    }

    private int countByStatus(String status) {
        try {
            Long c = requestMapper.selectCount(new LambdaQueryWrapper<ScheduleConstraintRequest>()
                    .eq(ScheduleConstraintRequest::getStatus, status));
            return c == null ? 0 : c.intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 管理端采纳（可批量）：标记 ADOPTED，记录备注并通知教师 */
    public Map<String, Object> adopt(List<Long> ids, String note) {
        return handleBatch(ids, true, note);
    }

    /** 管理端驳回（可批量）：标记 REJECTED，记录原因并通知教师可取消或修改后重新提交 */
    public Map<String, Object> reject(List<Long> ids, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "请填写驳回原因，便于教师修改后重新提交");
            return result;
        }
        return handleBatch(ids, false, reason.trim());
    }

    private Map<String, Object> handleBatch(List<Long> ids, boolean adopt, String note) {
        Map<String, Object> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            result.put("success", false);
            result.put("message", "请选择要处理的排课要求");
            return result;
        }
        int count = 0;
        int skipped = 0;
        try {
            for (Long id : ids) {
                ScheduleConstraintRequest req = requestMapper.selectById(id);
                if (req == null || !ST_PENDING.equals(req.getStatus())) {
                    skipped++;
                    continue;
                }
                if (adopt) {
                    updateStatus(id, ST_ADOPTED, note, "待排课验证");
                    notificationService.create(req.getTeacherId(), NOTIFY_TYPE, "排课要求已采纳",
                            "您的排课要求已由管理员采纳：" + req.getConstraintText()
                                    + (note == null || note.trim().isEmpty() ? "" : "（管理员备注：" + note.trim() + "）")
                                    + "。管理员将在 AI 排课中应用该要求。", id);
                } else {
                    updateStatus(id, ST_REJECTED, note, "未生效");
                    notificationService.create(req.getTeacherId(), NOTIFY_TYPE, "排课要求无法满足",
                            "您的排课要求「" + req.getConstraintText() + "」无法满足，原因：" + note
                                    + "。您可以在「排课要求」页面取消该约束，或修改后重新提交。", id);
                }
                count++;
            }
            result.put("success", count > 0);
            result.put("count", count);
            result.put("message", (adopt ? "已采纳 " : "已驳回 ") + count + " 条"
                    + (skipped > 0 ? "，" + skipped + " 条非待处理已跳过" : ""));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "处理失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 一键填入：把勾选的 PENDING 约束合并为 constraints 文本（逐条「教师名：原文」标注来源），
     * 同时标记为 ADOPTED，返回文本供管理端写入排课约束输入框。
     */
    public Map<String, Object> fill(List<Long> ids, String semester) {
        Map<String, Object> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            result.put("success", false);
            result.put("message", "请选择要填入的排课要求");
            return result;
        }
        try {
            Map<Long, ScheduleConstraintRequest> byId = new LinkedHashMap<>();
            for (Long id : ids) {
                ScheduleConstraintRequest req = requestMapper.selectById(id);
                if (req != null) {
                    byId.put(id, req);
                }
            }
            List<ScheduleConstraintRequest> rows = new ArrayList<>(byId.values());
            StringBuilder sb = new StringBuilder();
            int adopted = 0;
            for (ScheduleConstraintRequest req : rows) {
                // 已结束（驳回 / 取消 / 过期）的约束不得再填入
                if (ST_REJECTED.equals(req.getStatus()) || ST_CANCELLED.equals(req.getStatus())
                        || ST_EXPIRED.equals(req.getStatus())) {
                    continue;
                }
                String text = aiScheduleService.attributeConstraintText(req.getTeacherName(), req.getConstraintText());
                if (text.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(text);
                if (ST_PENDING.equals(req.getStatus())) {
                    updateStatus(req.getId(), ST_ADOPTED, "管理员一键填入排课约束", "待排课验证");
                    notificationService.create(req.getTeacherId(), NOTIFY_TYPE, "排课要求已采纳",
                            "您的排课要求已由管理员采纳并填入 AI 排课约束：" + req.getConstraintText()
                                    + "。管理员将在本次排课中应用该要求。", req.getId());
                    adopted++;
                }
            }
            if (sb.length() == 0) {
                result.put("success", false);
                result.put("message", "所选排课要求均已结束，无法填入");
                return result;
            }
            result.put("success", true);
            result.put("constraints", sb.toString());
            result.put("adopted", adopted);
            result.put("message", "已填入 " + rows.size() + " 条教师约束"
                    + (adopted > 0 ? "（" + adopted + " 条转为已采纳）" : ""));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "填入失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 排课未生效 / 冲突后一键通知教师：
     * 依据预览返回的 constraintUnmatched / constraintUnrecognized / 失败提示文本匹配已采纳记录（按教师名），
     * 命中记录标记 REJECTED 并通知教师原因与处理建议；未命中的提示原样返回，便于管理端核对。
     */
    public Map<String, Object> notifyUnmatched(List<String> messages, String semester) {
        Map<String, Object> result = new HashMap<>();
        if (messages == null || messages.isEmpty()) {
            result.put("success", false);
            result.put("message", "没有可通知的未生效约束信息");
            return result;
        }
        try {
            LambdaQueryWrapper<ScheduleConstraintRequest> qw = new LambdaQueryWrapper<ScheduleConstraintRequest>()
                    .eq(ScheduleConstraintRequest::getStatus, ST_ADOPTED);
            if (semester != null && !semester.trim().isEmpty()) {
                qw.eq(ScheduleConstraintRequest::getSemester, semester.trim());
            }
            List<ScheduleConstraintRequest> adopted = requestMapper.selectList(qw);
            int notified = 0;
            List<String> unmatchedNotes = new ArrayList<>();
            for (ScheduleConstraintRequest req : adopted) {
                String hit = findMessageFor(req, messages);
                if (hit == null) {
                    continue;
                }
                String reason = hit.length() > 300 ? hit.substring(0, 300) : hit;
                updateStatus(req.getId(), ST_REJECTED, "排课无法满足：" + reason, "未生效");
                notificationService.create(req.getTeacherId(), NOTIFY_TYPE, "排课要求无法满足",
                        "您的排课要求「" + req.getConstraintText() + "」在本次 AI 排课中无法满足：" + reason
                                + "。您可以在「排课要求」页面取消该约束，或修改后重新提交。", req.getId());
                notified++;
            }
            for (String m : messages) {
                unmatchedNotes.add(m);
            }
            result.put("success", true);
            result.put("notified", notified);
            result.put("message", notified > 0
                    ? "已通知 " + notified + " 位教师，相关约束已标记为无法满足"
                    : "未匹配到已采纳的教师约束，请核对提示信息");
            result.put("notes", unmatchedNotes);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "通知失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 在未生效提示中匹配该教师约束：优先按该条记录的解析描述精确匹配，
     * 避免同一教师的其它正常约束被误判为无法满足；无解析结果时才退回按教师姓名匹配。
     */
    private String findMessageFor(ScheduleConstraintRequest req, List<String> messages) {
        List<String> keys = new ArrayList<>();
        String parsed = req.getParsedJson();
        if (parsed != null && !parsed.trim().isEmpty()) {
            try {
                List<?> items = objectMapper.readValue(parsed, List.class);
                for (Object item : items) {
                    if (item instanceof Map) {
                        Object desc = ((Map<?, ?>) item).get("description");
                        if (desc != null && !String.valueOf(desc).trim().isEmpty()) {
                            keys.add(String.valueOf(desc).trim());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("排课要求解析结果读取失败（id={}）: {}", req.getId(), e.getMessage());
            }
        }
        for (String msg : messages) {
            if (msg == null) {
                continue;
            }
            for (String key : keys) {
                if (msg.contains(key)) {
                    return msg.trim();
                }
            }
        }
        if (!keys.isEmpty()) {
            return null;
        }
        String teacherName = req.getTeacherName() == null ? "" : req.getTeacherName().trim();
        for (String msg : messages) {
            if (msg == null) {
                continue;
            }
            if (!teacherName.isEmpty() && msg.contains("【教师 - " + teacherName + "】")) {
                return msg.trim();
            }
        }
        return null;
    }

    /**
     * 学期切换 / 课表确认入库：把该学期已采纳约束标记为过期（避免跨学期误用）。
     * 不指定学期时仅处理当前学期以外的全部已采纳记录。
     */
    public int expireAdopted(String semester) {
        try {
            LambdaUpdateWrapper<ScheduleConstraintRequest> uw = new LambdaUpdateWrapper<ScheduleConstraintRequest>()
                    .eq(ScheduleConstraintRequest::getStatus, ST_ADOPTED)
                    .set(ScheduleConstraintRequest::getStatus, ST_EXPIRED)
                    .set(ScheduleConstraintRequest::getApplyResult, "已生效")
                    .set(ScheduleConstraintRequest::getUpdateTime, java.time.LocalDateTime.now());
            if (semester != null && !semester.trim().isEmpty()) {
                uw.eq(ScheduleConstraintRequest::getSemester, semester.trim());
            } else {
                String current = currentSemesterName();
                uw.ne(ScheduleConstraintRequest::getSemester, current);
            }
            return requestMapper.update(null, uw);
        } catch (Exception e) {
            log.warn("排课要求过期处理失败（不影响主流程）: {}", e.getMessage());
            return 0;
        }
    }

    /** 约束统计：按教师 / 按学期统计提交数、采纳数、驳回数 */
    public Map<String, Object> stats(String semester) {
        Map<String, Object> result = new HashMap<>();
        try {
            String semFilter = semester == null || semester.trim().isEmpty() ? "" : semester.trim();
            StringBuilder sql = new StringBuilder(
                    "SELECT teacher_name AS teacherName, semester, "
                            + "COUNT(*) AS total, "
                            + "SUM(CASE WHEN status IN ('ADOPTED','EXPIRED') THEN 1 ELSE 0 END) AS adopted, "
                            + "SUM(CASE WHEN status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected, "
                            + "SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pending "
                            + "FROM schedule_constraint_request ");
            List<Object> args = new ArrayList<>();
            if (!semFilter.isEmpty()) {
                sql.append("WHERE semester = ? ");
                args.add(semFilter);
            }
            sql.append("GROUP BY teacher_name, semester ORDER BY total DESC, teacher_name");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            result.put("success", true);
            result.put("stats", rows);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "统计加载失败：" + e.getMessage());
            result.put("stats", new ArrayList<>());
        }
        return result;
    }

    // ==================== 内部工具 ====================

    private void updateStatus(Long id, String status, String adminNote, String applyResult) {
        LambdaUpdateWrapper<ScheduleConstraintRequest> uw = new LambdaUpdateWrapper<ScheduleConstraintRequest>()
                .eq(ScheduleConstraintRequest::getId, id)
                .set(ScheduleConstraintRequest::getStatus, status)
                .set(ScheduleConstraintRequest::getUpdateTime, java.time.LocalDateTime.now());
        if (adminNote != null) {
            uw.set(ScheduleConstraintRequest::getAdminNote, adminNote);
        }
        if (applyResult != null) {
            uw.set(ScheduleConstraintRequest::getApplyResult, applyResult);
        }
        requestMapper.update(null, uw);
    }

    /** 通知全部管理员账号（role=admin） */
    private void notifyAdmins(String title, String content, Long relatedId) {
        try {
            List<Long> adminIds = jdbcTemplate.queryForList(
                    "SELECT id FROM user WHERE role = 'admin' AND status = 1", Long.class);
            notificationService.notifyUsers(adminIds, NOTIFY_TYPE, title, content, relatedId);
        } catch (Exception e) {
            log.warn("管理员通知发送失败（不影响提交）: {}", e.getMessage());
        }
    }

    /** 当前学期名称（未配置时返回空串） */
    public String currentSemesterName() {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT semester_name FROM semester_config ORDER BY id DESC LIMIT 1", String.class);
            return names.isEmpty() || names.get(0) == null ? "" : names.get(0);
        } catch (Exception e) {
            return "";
        }
    }
}
