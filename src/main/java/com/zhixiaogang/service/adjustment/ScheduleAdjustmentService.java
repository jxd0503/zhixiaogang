package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.entity.ScheduleAdjustment;
import com.zhixiaogang.entity.User;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ScheduleAdjustmentMapper;
import com.zhixiaogang.util.WeekRangeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调课申请与审批服务（对外门面）。
 * 调课状态全部存于 schedule_adjustment；审批通过时把 schedule 记录时段同步更新为目标时段
 * （原时段持久化在 schedule_adjustment 的 orig_* 字段），撤销审批通过时恢复为原时段，保证底层数据自洽；
 * 展示层仍保留 isAdjustment 叠加标注（已同步行直接打标，未同步的历史数据行保留叠加补偿）。
 * 发起校验与批准校验共用同一套冲突校验逻辑（{@link AdjustmentConflictChecker}），保证两处口径一致；
 * AI 推荐、通知联动、审批流转分别由 {@link AdjustmentAiSuggester}、{@link AdjustmentNotifier}、
 * {@link AdjustmentReviewService} 承担，本类仅保留公开入口与事务边界。
 */
@Service
public class ScheduleAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleAdjustmentService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScheduleAdjustmentMapper adjustmentMapper;

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private AdjustmentConflictChecker conflictChecker;

    @Autowired
    private AdjustmentAiSuggester aiSuggester;

    @Autowired
    private AdjustmentReviewService reviewService;

    @Autowired
    private AdjustmentCancelService cancelService;

    @Autowired
    private AdjustmentAttachmentService attachmentService;

    @Autowired
    private AdjustmentAuditLogger auditLogger;

    // ==================== 统一冲突校验（发起 / 批准共用） ====================

    /**
     * 多方占用校验（口径统一）：目标时段该教室无排课、无已批准预约、无已生效调课占用；
     * 该班级无排课、无已生效调课占用；该教师（含其他班级）无排课、无已生效调课占用。
     * 所有七类冲突都按"调课生效周次与占用方生效周次是否存在交集"判断，
     * 完全不重叠时允许通过，完全或部分重叠时判定冲突。
     *
     * @param classroomId       原课程教室 ID（null 表示无教室课程，跳过教室校验）
     * @param classId           班级 ID
     * @param teacherId         授课教师工号
     * @param teacherName       授课教师姓名
     * @param targetWeekDay     目标星期 1-7
     * @param targetStart       目标开始节次
     * @param targetEnd         目标结束节次
     * @param excludeScheduleId 原课表条目 ID（排除自身，避免与原时段自冲突）
     * @param excludeAdjustmentId 当前调课记录 ID（批准时排除自身，发起时传 null）
     * @param targetWeekRange   调课目标生效周次（如 "1-16"、"3,5,7"；null 或空表示全学期）
     * @return 冲突原因列表（空表示无冲突）
     */
    public List<String> checkConflicts(Long classroomId, Long classId, String teacherId, String teacherName,
                                       int targetWeekDay, int targetStart, int targetEnd,
                                       Long excludeScheduleId, Long excludeAdjustmentId,
                                       String targetWeekRange) {
        return conflictChecker.checkConflicts(classroomId, classId, teacherId, teacherName,
                targetWeekDay, targetStart, targetEnd, excludeScheduleId, excludeAdjustmentId, targetWeekRange);
    }

    /** 冲突明细项：dimension 区分教室（room）/班级（class）/教师（teacher）三类占用来源 */
    public static class ConflictItem {
        public String dimension;
        public String kind; // schedule排课 / reservation预约 / adjustment生效调课
        public String courseName = "";
        public String className = "";
        public String teacherName = "";
        public String classroomNo = "";
        public int weekDay;
        public Integer startPeriod;
        public Integer endPeriod;
        public String weekRange;
    }

    /**
     * 按维度收集冲突明细（教室 / 班级 / 教师），供统一校验、AI 推荐与管理端二次校验共用。
     * @param classroomId 目标教室（换教室时应传目标教室；沿用原教室时传原教室）
     */
    public List<ConflictItem> collectConflicts(Long classroomId, Long classId, String teacherId, String teacherName,
                                               int targetWeekDay, int targetStart, int targetEnd,
                                               Long excludeScheduleId, Long excludeAdjustmentId,
                                               String targetWeekRange) {
        return conflictChecker.collectConflicts(classroomId, classId, teacherId, teacherName,
                targetWeekDay, targetStart, targetEnd, excludeScheduleId, excludeAdjustmentId, targetWeekRange);
    }

    /** 管理端二次校验明细（转发） */
    List<Map<String, Object>> conflictDetailLines(List<ConflictItem> items) {
        return conflictChecker.conflictDetailLines(items);
    }

    /** 后端确定性生成候选状态文字（转发） */
    String statusTextOf(List<ConflictItem> items, boolean changeClassroom) {
        return conflictChecker.statusTextOf(items, changeClassroom);
    }

    // ==================== 教师端：发起调课 ====================

    /** 发起调课申请（body: {reason, details:[{scheduleId,targetWeekDay,targetStartPeriod,targetEndPeriod,weekRange}]}）
     *  事务内执行：validateDetail 对原课表行加 FOR UPDATE 锁，并发相同申请仅先到者成功（后到者被幂等检查拒绝） */
    @Transactional
    public Map<String, Object> apply(Map<String, Object> body, User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason")).trim();
            List<Map<String, Object>> details = AdjustmentSupport.asDetailList(body.get("details"));
            if (details.isEmpty()) {
                result.put("success", false);
                result.put("message", "请至少添加一条调课明细");
                return result;
            }
            if (reason.isEmpty()) {
                result.put("success", false);
                result.put("message", "请填写调课原因");
                return result;
            }

            // 凭证附件：提交前校验归属与数量（图片/PDF，单个≤5MB 已在上传时校验）
            List<Long> attachmentIds = new ArrayList<>();
            if (body.get("attachmentIds") instanceof List<?> rawIds) {
                for (Object o : rawIds) {
                    Long id = AdjustmentSupport.longVal(o);
                    if (id != null) {
                        attachmentIds.add(id);
                    }
                }
            }
            String uploaderId = user == null ? null : user.getStudentId();
            String attachmentError = attachmentService.validate(uploaderId, attachmentIds);
            if (attachmentError != null) {
                result.put("success", false);
                result.put("message", attachmentError);
                return result;
            }

            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            List<Map<String, Object>> failures = new ArrayList<>();
            List<ScheduleAdjustment> toInsert = new ArrayList<>();
            // 同一批次内按「原课表条目 + 源周次 + 目标周次」去重，避免一次提交重复生成待审批记录
            Set<String> seenKeys = new HashSet<>();
            int index = 0;
            for (Map<String, Object> d : details) {
                index++;
                Map<String, Object> v = validateDetail(d, user, index);
                @SuppressWarnings("unchecked")
                List<String> errs = (List<String>) v.get("errors");
                @SuppressWarnings("unchecked")
                List<String> warns = (List<String>) v.get("warnings");
                if (errs.isEmpty()) {
                    ScheduleAdjustment a = (ScheduleAdjustment) v.get("adjustment");
                    String key = batchKey(a.getScheduleId(), a.getWeekRange(), a.getTargetWeek());
                    if (!seenKeys.add(key)) {
                        String msg = "明细" + index + "：与本批次中已有明细重复，已跳过";
                        errors.add(msg);
                        failures.add(failure(index, a.getScheduleId(), msg));
                        continue;
                    }
                    a.setReason(reason);
                    toInsert.add(a);
                    warnings.addAll(warns);
                } else {
                    errors.addAll(errs);
                    failures.add(failure(index, AdjustmentSupport.longVal(d.get("scheduleId")), String.join("；", errs)));
                }
            }

            // 批量提交允许部分失败：结构性问题（越界周次、重复申请等）跳过该条，其余正常生成待审批记录
            if (toInsert.isEmpty()) {
                result.put("success", false);
                result.put("message", "存在非法明细，无法提交：" + String.join("；", errors));
                result.put("conflicts", errors);
                result.put("failed", failures);
                return result;
            }

            List<Long> adjustmentIds = new ArrayList<>();
            for (ScheduleAdjustment a : toInsert) {
                adjustmentMapper.insert(a);
                if (a.getId() != null) {
                    adjustmentIds.add(a.getId());
                    // 审批流水：教师提交动作 + 提交时看到的冲突快照
                    auditLogger.log(a.getId(), "apply", uploaderId, user == null ? null : user.getName(), "teacher",
                            AdjustmentAuditLogger.conflictSnapshot(a.getConflictResult(), a.getConflictDetail()),
                            reason, "教师提交调课申请，生成待审批记录"
                                    + (attachmentIds.isEmpty() ? "" : "（附件 " + attachmentIds.size() + " 个）"));
                }
            }
            attachmentService.bindAll(adjustmentIds, uploaderId, attachmentIds);
            String baseMsg = warnings.isEmpty()
                    ? ("调课申请已提交，共 " + toInsert.size() + " 条，等待管理端审核")
                    : ("调课申请已提交，共 " + toInsert.size() + " 条，其中存在占用冲突或教室类型差异，等待管理端裁决");
            if (!failures.isEmpty()) {
                baseMsg = "已提交 " + toInsert.size() + " 条，" + failures.size() + " 条失败：" + String.join("；", errors);
            }
            result.put("success", true);
            result.put("message", baseMsg);
            result.put("count", toInsert.size());
            result.put("failed", failures);
            result.put("conflicts", warnings);
            result.put("adjustments", adjustmentIds);
        } catch (Exception e) {
            log.warn("提交调课申请失败", e);
            result.put("success", false);
            result.put("message", "调课申请提交失败，请稍后重试");
        }
        return result;
    }

    /** 批量提交去重键：同一原课表条目 + 源周次 + 目标周次只生成一条待审批记录 */
    static String batchKey(Long scheduleId, String weekRange, Integer targetWeek) {
        return (scheduleId == null ? "" : scheduleId) + "|"
                + (weekRange == null ? "" : weekRange.trim()) + "|"
                + (targetWeek == null ? "" : targetWeek);
    }

    private Map<String, Object> failure(int index, Long scheduleId, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("index", index);
        m.put("scheduleId", scheduleId);
        m.put("message", message);
        return m;
    }

    /**
     * 教师端实时校验调课明细（只读，不写库），复用与 apply 相同的 validateDetail 校验路径，保证口径一致。
     * 兼容两种请求结构：
     * ① 单条明细平铺 body（教师端当前结构）：{scheduleId, targetWeekDay, targetStartPeriod, targetEndPeriod, weekRange}
     * ② {details:[{...}]}（与 apply 一致的多明细结构）：逐项校验并按「明细 N（目标时段）：」聚合
     * 校验通过返回无冲突结果；存在冲突返回具体冲突信息（含星期、节次、教室）。
     */
    public Map<String, Object> checkDetail(Map<String, Object> body, User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean hasDetailsKey = body != null && body.containsKey("details");
            List<Map<String, Object>> details = AdjustmentSupport.asDetailList(body == null ? null : body.get("details"));
            if (details.isEmpty()) {
                if (hasDetailsKey) {
                    result.put("success", false);
                    result.put("message", "请至少添加一条调课明细");
                    return result;
                }
                if (body != null) {
                    details = new ArrayList<>();
                    details.add(body);
                }
            }
            if (details.isEmpty()) {
                result.put("success", false);
                result.put("message", "请至少添加一条调课明细");
                return result;
            }

            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int index = 0;
            for (Map<String, Object> d : details) {
                index++;
                Integer tDay = AdjustmentSupport.intVal(d.get("targetWeekDay"));
                String prefix = "明细" + index + "（"
                        + AdjustmentSupport.slotText(tDay == null ? 0 : tDay, d.get("targetStartPeriod"), d.get("targetEndPeriod"))
                        + "）：";
                Map<String, Object> v = validateDetail(d, user, prefix);
                @SuppressWarnings("unchecked")
                List<String> errs = (List<String>) v.get("errors");
                @SuppressWarnings("unchecked")
                List<String> warns = (List<String>) v.get("warnings");
                errors.addAll(errs);
                warnings.addAll(warns);
            }
            // 三方占用冲突属于"提示但不阻止提交"，与结构性错误一起以红字返回给前端
            List<String> all = new ArrayList<>(errors);
            all.addAll(warnings);
            if (all.isEmpty()) {
                result.put("success", true);
                result.put("message", "该时段无冲突，可提交");
            } else {
                result.put("success", false);
                result.put("message", String.join("；", all));
                result.put("conflicts", all);
                result.put("blocking", !errors.isEmpty());
            }
        } catch (Exception e) {
            log.warn("调课校验失败", e);
            result.put("success", false);
            result.put("message", "校验失败，请稍后重试");
        }
        return result;
    }

    // ==================== 教师端：AI 智能推荐目标时段 ====================

    /**
     * AI 智能推荐目标时段。
     * 后端先在内存中构建全量占用索引（课表 + 已批准预约 + 已生效调课），再遍历候选时段做三方占用判断，
     * 状态文字由后端确定性生成；随后把候选交给 AI 模型做排序与剔除（失败/超时 45 秒自动降级为本地结果）。
     */
    public Map<String, Object> aiSuggest(Map<String, Object> body, User user) {
        return aiSuggester.aiSuggest(body, user);
    }

    /** 教室下拉选项（换教室时使用） */
    public List<Map<String, Object>> classroomOptions() {
        return aiSuggester.classroomOptions();
    }

    /** 校验单条明细：返回 {errors:[...], adjustment:ScheduleAdjustment(校验通过时)}（默认前缀「明细N：」）
     *  仅供 apply 路径调用：在事务内对原课表行加 FOR UPDATE 锁，串行化同一课程条目的并发申请，
     *  使后到者在 pendingDuplicateError / existingApprovedAdjustmentError 检查中看到先到者的记录。 */
    private Map<String, Object> validateDetail(Map<String, Object> d, User user, int index) {
        return validateDetail(d, user, "明细" + index + "：", true);
    }

    /** 校验单条明细，允许自定义错误前缀（实时检查时前缀附带目标星期节次，便于定位冲突时段） */
    private Map<String, Object> validateDetail(Map<String, Object> d, User user, String prefix) {
        return validateDetail(d, user, prefix, false);
    }

    private Map<String, Object> validateDetail(Map<String, Object> d, User user, String prefix, boolean lock) {
        Map<String, Object> out = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("adjustment", null);

        Long scheduleId = AdjustmentSupport.longVal(d.get("scheduleId"));
        Integer tDay = AdjustmentSupport.intVal(d.get("targetWeekDay"));
        Integer tStart = AdjustmentSupport.intVal(d.get("targetStartPeriod"));
        Integer tEnd = AdjustmentSupport.intVal(d.get("targetEndPeriod"));
        Integer tWeek = AdjustmentSupport.intVal(d.get("targetWeek"));
        String weekRange = d.get("weekRange") == null ? null : String.valueOf(d.get("weekRange")).trim();
        Long targetClassroomId = AdjustmentSupport.longVal(d.get("targetClassroomId"));
        boolean changeClassroom = AdjustmentSupport.flagVal(d.get("changeClassroom"));
        // 代课教师（可选）：指定后按"时间教室不变、仅更换授课教师"处理，目标时段允许与原时段相同
        String substituteTeacherId = d.get("substituteTeacherId") == null ? null : String.valueOf(d.get("substituteTeacherId")).trim();
        if (substituteTeacherId != null && substituteTeacherId.isEmpty()) {
            substituteTeacherId = null;
        }
        String sourceType = AdjustmentSupport.normalizeSourceType(d.get("sourceType"), AdjustmentSupport.flagVal(d.get("aiSuggested")));
        boolean aiSuggested = !"manual".equals(sourceType);

        if (scheduleId == null) {
            errors.add(prefix + "原课表条目缺失");
            return out;
        }
        Map<String, Object> orig = lock ? loadOrigScheduleForUpdate(scheduleId)
                : AdjustmentSupport.loadOrigSchedule(jdbcTemplate, scheduleId);
        if (orig == null) {
            errors.add(prefix + "原课表条目不存在");
            return out;
        }
        if (!AdjustmentSupport.ownedBy(orig, user)) {
            errors.add(prefix + "无权对该课程发起调课");
            return out;
        }
        if (tDay == null || tStart == null || tEnd == null) {
            errors.add(prefix + "请选择目标星期与节次");
            return out;
        }
        if (tDay < 1 || tDay > 7 || tStart < 1 || tEnd > 10 || tStart > tEnd) {
            errors.add(prefix + "目标时段不合法");
            return out;
        }

        int oDay = ((Number) orig.get("week_day")).intValue();
        int oStart = ((Number) orig.get("start_period")).intValue();
        int oEnd = ((Number) orig.get("end_period")).intValue();

        String origWeekRange = orig.get("week_range") == null ? null : orig.get("week_range").toString();
        String effWeekRange = (weekRange == null || weekRange.isEmpty())
                ? AdjustmentSupport.defaultWeekRange(origWeekRange) : weekRange;
        // 调课场景以原课表生效周次为上界展开自然语言周次（如原课表 1-18 时 双周 → 2,4,...,18）
        WeekRangeParser.Result weekParsed = WeekRangeParser.parseWithin(effWeekRange, origWeekRange);
        if (!weekParsed.valid) {
            errors.add(prefix + weekParsed.error);
            return out;
        }
        effWeekRange = weekParsed.normalized;
        if (!AdjustmentSupport.rangeWithin(origWeekRange, effWeekRange)) {
            errors.add(prefix + "目标周次范围超出原课表周次范围（原："
                    + AdjustmentSupport.defaultWeekRange(origWeekRange) + "）");
            return out;
        }

        // 跨周调课：week_range 表示"被调整的源周次"，目标周次单独由 targetWeek 指定
        String conflictWeekRange = effWeekRange;
        if (tWeek != null) {
            int totalWeeks = WeekRangeParser.getDefaultTotalWeeks();
            if (tWeek < 1 || tWeek > totalWeeks) {
                errors.add(prefix + "目标周次需在 1-" + totalWeeks + " 周之间");
                return out;
            }
            Set<Integer> srcWeeks = WeekRangeParser.weeks(effWeekRange);
            if (srcWeeks.size() != 1) {
                errors.add(prefix + "跨周调课一次只能调整一个周次，请将周次范围限定为单周（如 10）");
                return out;
            }
            // 目标周次与源周次相同且时段未变 → 等价于未调课
            if (srcWeeks.contains(tWeek) && tDay == oDay && tStart == oStart && tEnd == oEnd) {
                errors.add(prefix + "目标周次与时段均未变化，无需调课");
                return out;
            }
            // 跨周时按目标单周判断占用冲突（源周次不再显示该课）
            conflictWeekRange = String.valueOf(tWeek);
        } else if (tDay == oDay && tStart == oStart && tEnd == oEnd && substituteTeacherId == null) {
            errors.add(prefix + "目标时段与原时段相同，无需调课");
            return out;
        }

        // 规则：同一课程同一周次存在待审核 / 已批准的调课申请时不允许重复发起；已驳回 / 已撤销的申请不阻断重提
        String dupError = conflictChecker.existingAdjustmentError(scheduleId, effWeekRange);
        if (dupError != null) {
            errors.add(prefix + dupError);
            return out;
        }

        Long origClassroomId = orig.get("classroom_id") == null ? null : ((Number) orig.get("classroom_id")).longValue();
        Long classId = orig.get("class_id") == null ? null : ((Number) orig.get("class_id")).longValue();
        String tid = orig.get("teacher_id") == null ? null : orig.get("teacher_id").toString();
        String tname = orig.get("teacher_name") == null ? null : orig.get("teacher_name").toString();

        // 代课教师：必须为启用状态的教师，且不能与原授课教师相同
        String substituteTeacherName = null;
        if (substituteTeacherId != null) {
            if (substituteTeacherId.equals(tid)
                    || ((tid == null || tid.isEmpty()) && substituteTeacherId.equals(tname))) {
                errors.add(prefix + "代课教师不能与原授课教师相同");
                return out;
            }
            Map<String, Object> sub = loadSubstituteTeacher(substituteTeacherId);
            if (sub == null) {
                errors.add(prefix + "代课教师不存在或已停用");
                return out;
            }
            substituteTeacherName = AdjustmentSupport.str(sub.get("name"));
            if (substituteTeacherName.isEmpty()) {
                errors.add(prefix + "代课教师信息不完整");
                return out;
            }
        }

        // 换教室：使用目标教室校验与应用展示；沿用原教室：教室配置固定为原教室
        Long effClassroomId = origClassroomId;
        if (changeClassroom) {
            if (targetClassroomId == null) {
                errors.add(prefix + "请选择目标教室");
                return out;
            }
            if (!targetClassroomId.equals(origClassroomId) && classroomMapper.selectById(targetClassroomId) == null) {
                errors.add(prefix + "目标教室不存在");
                return out;
            }
            // 手动指定教室时提示类型差异（提示不阻断，由管理端审批时裁决）
            if (origClassroomId != null && !targetClassroomId.equals(origClassroomId)) {
                String origRoomType = classroomRoomType(origClassroomId);
                String targetRoomType = classroomRoomType(targetClassroomId);
                if (!origRoomType.isEmpty() && !targetRoomType.isEmpty() && !origRoomType.equals(targetRoomType)) {
                    warnings.add(prefix + "目标教室类型（" + targetRoomType + "）与原教室（" + origRoomType + "）不一致，是否继续");
                }
            }
            effClassroomId = targetClassroomId;
        }

        // 三方占用冲突：作为警告返回，不阻止提交，由管理端二次校验裁决
        List<ConflictItem> items = conflictChecker.collectConflicts(effClassroomId, classId, tid, tname, tDay, tStart, tEnd,
                scheduleId, null, conflictWeekRange);
        if (!items.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (ConflictItem it : items) {
                texts.add(conflictChecker.conflictText(it));
            }
            warnings.add(prefix + String.join("；", texts));
        }

        // 代课教师时段校验：复用教师维度判定（排课 + 已生效调课），存在冲突直接阻断提交
        if (substituteTeacherId != null) {
            List<ConflictItem> subItems = conflictChecker.collectTeacherConflicts(substituteTeacherId, substituteTeacherName,
                    tDay, tStart, tEnd, null, null, conflictWeekRange);
            if (!subItems.isEmpty()) {
                List<String> texts = new ArrayList<>();
                for (ConflictItem it : subItems) {
                    texts.add("代课教师" + substituteTeacherName + "此时段已有课程：《" + it.courseName + "》（"
                            + it.className + " " + AdjustmentSupport.periodText(it.startPeriod, it.endPeriod) + "）");
                }
                errors.add(prefix + String.join("；", texts));
                return out;
            }
        }

        ScheduleAdjustment a = new ScheduleAdjustment();
        a.setScheduleId(scheduleId);
        a.setClassId(classId);
        a.setCourseId(orig.get("course_id") == null ? null : ((Number) orig.get("course_id")).longValue());
        a.setCourseName(AdjustmentSupport.str(orig.get("course_name")));
        a.setTeacherName(tname);
        a.setTeacherId(tid);
        a.setOrigWeekDay(oDay);
        a.setOrigStartPeriod(oStart);
        a.setOrigEndPeriod(oEnd);
        a.setTargetWeekDay(tDay);
        a.setTargetStartPeriod(tStart);
        a.setTargetEndPeriod(tEnd);
        a.setTargetWeek(tWeek);
        // 原始周次快照：跨周调课与"仅部分周次生效"的拆分场景撤销时都需要据此恢复
        a.setOrigWeekRange(origWeekRange);
        // 教室快照始终保存：非跨周换教室审批后也需要在撤销时恢复原教室
        a.setOrigClassroomId(origClassroomId);
        a.setWeekRange(effWeekRange);
        a.setSemester(orig.get("semester") == null ? null : orig.get("semester").toString());
        a.setTargetClassroomId(changeClassroom ? effClassroomId : null);
        a.setChangeClassroom(changeClassroom ? 1 : 0);
        a.setAiSuggested(aiSuggested ? 1 : 0);
        a.setSourceType(sourceType);
        // 状态文字由后端按本次校验结果确定性生成，不信任客户端传值
        a.setStatusText(conflictChecker.statusTextOf(items, changeClassroom));
        a.setConflictResult(conflictChecker.conflictResultOf(items));
        a.setConflictDetail(conflictChecker.conflictDetailSnapshot(items));
        a.setCheckTime(LocalDateTime.now());
        a.setCheckBy(user == null ? null : (user.getStudentId() == null ? user.getName() : user.getStudentId()));
        a.setSubstituteTeacherId(substituteTeacherId);
        a.setSubstituteTeacherName(substituteTeacherName);
        a.setStatus("pending");
        a.setApplyTime(LocalDateTime.now());
        out.put("adjustment", a);
        return out;
    }

    /** 教室类型（历史数据未填类型时按普通教室兜底；教室不存在时返回空串） */
    private String classroomRoomType(Long classroomId) {
        if (classroomId == null) {
            return "";
        }
        Classroom classroom = classroomMapper.selectById(classroomId);
        if (classroom == null) {
            return "";
        }
        String type = classroom.getRoomType();
        return type == null || type.trim().isEmpty() ? "普通教室" : type.trim();
    }

    /** 行锁版本：apply 事务内使用，串行化同一课表条目的并发调课申请 */
    private Map<String, Object> loadOrigScheduleForUpdate(Long scheduleId) {
        String sql = "SELECT s.id, s.course_id, s.classroom_id, s.class_id, s.teacher_id, s.teacher_name, " +
                "s.week_day, s.start_period, s.end_period, s.week_range, s.semester, c.course_name " +
                "FROM schedule s LEFT JOIN course c ON s.course_id = c.id WHERE s.id = ? FOR UPDATE";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, scheduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 教师端 / 管理端：申请与审批流转（委托 AdjustmentReviewService） ====================

    /** 教师端：本人调课申请列表（分页） */
    public Map<String, Object> myAdjustments(String teacherId, String teacherName, Integer page, Integer size) {
        return reviewService.myAdjustments(teacherId, teacherName, page, size);
    }

    /** 教师端：撤回待审核申请（pending→cancelled，事务内锁行避免与 approve/reject 的状态竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> withdraw(Long id, User user, String reason) {
        return reviewService.withdraw(id, user, reason);
    }

    /** 管理端：调课列表（可按状态筛选，分页） */
    public Map<String, Object> adminList(String status, Integer page, Integer size) {
        return reviewService.adminList(status, page, size);
    }

    /** 管理端：批准（READ_COMMITTED 避免 REPEATABLE READ 快照读导致 TOCTOU 竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approve(Long id, String approver) {
        return reviewService.approve(id, approver);
    }

    /**
     * 管理端：批准。
     * 教师 / 班级硬冲突不可强制放行；教室软冲突返回 needConfirm，管理员二次确认并填写理由后以 force=true 知情放行。
     * 同一课程已有生效调课（逻辑重复）始终硬拦截。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approve(Long id, String approver, boolean force, String forceReason) {
        return reviewService.approve(id, approver, force, forceReason);
    }

    /**
     * 管理端：重新校验冲突（不改变审批状态，仅刷新校验快照）。
     * 按目标时段 + 目标教室分别校验教师课表、班级课表、教室占用（排课 + 已批准预约 + 生效调课）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> conflictCheck(Long id, String checker) {
        return reviewService.conflictCheck(id, checker);
    }

    /** 管理端：拒绝（pending→rejected，事务内锁行避免并发 reject/approve 竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> reject(Long id, String approver, String reason) {
        return reviewService.reject(id, approver, reason);
    }

    /** 管理端：撤销已批准调课（approved→cancelled，事务内锁行避免并发 cancel/approve 竞态） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> cancel(Long id, String approver, String reason) {
        return reviewService.cancel(id, approver, reason);
    }

    // ==================== 撤销调课申请 ====================

    /** 教师：待审批直接撤销；已批准则生成撤销审批申请（PENDING），等待管理端同意 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> requestCancel(Long id, User user, String reason) {
        return cancelService.requestCancel(id, user, reason);
    }

    /** 管理端：同意撤销（撤销待审批→已撤销，课表恢复原时段） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> approveCancel(Long id, String approver) {
        return cancelService.approveCancel(id, approver);
    }

    /** 管理端：拒绝撤销（撤销待审批→撤销被拒，调课保持生效） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> rejectCancel(Long id, String approver, String reason) {
        return cancelService.rejectCancel(id, approver, reason);
    }

    /** 教师：紧急回滚已批准调课（立即恢复原课表并留痕，管理员事后补审确认知悉） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> emergencyRollback(Long id, User user, String reason) {
        return cancelService.emergencyRollback(id, user, reason);
    }

    /** 管理端：紧急回滚事后补审（仅确认知悉并写流水，不改变状态与课表） */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> acknowledgeEmergency(Long id, String approver) {
        return cancelService.acknowledgeEmergency(id, approver);
    }

    // ==================== 展示层叠加：生效中的调课 ====================

    /** 学生端：指定班级在 week 周生效的调课（week 为 null 表示不按周过滤） */
    public List<Map<String, Object>> getActiveAdjustmentsForClass(Long classId, Integer week) {
        if (classId == null) {
            return List.of();
        }
        return queryActive("a.class_id = ?", new Object[]{classId}, week);
    }

    /** 教师端：指定教师在 week 周生效的调课 */
    public List<Map<String, Object>> getActiveAdjustmentsForTeacher(String teacherId, String teacherName, Integer week) {
        boolean hasId = teacherId != null && !teacherId.isEmpty();
        boolean hasName = teacherName != null && !teacherName.isEmpty();
        if (!hasId && !hasName) {
            return List.of();
        }
        String cond = "(a.teacher_id = ?" + (hasName ? " OR ((a.teacher_id IS NULL OR a.teacher_id = '') AND a.teacher_name = ?)" : "") + ")";
        List<Object> args = new ArrayList<>();
        args.add(hasId ? teacherId : null);
        if (hasName) args.add(teacherName);
        return queryActive(cond, args.toArray(), week);
    }

    /** 教室课表 / 占用视图：指定教室生效的调课（含换教室后的目标教室）；week 为 null 时不按周次过滤 */
    public List<Map<String, Object>> getActiveAdjustmentsForClassroom(Long classroomId, Integer week) {
        if (classroomId == null) {
            return List.of();
        }
        return queryActive(conflictChecker.effectiveClassroomExpr("a", "s") + " = ?", new Object[]{classroomId}, week);
    }

    /**
     * 学生端：该班级在 week 周处于待审批状态的调课。
     * 仅用于课表"调课申请中"标记，不得参与教室占用、冲突判定或预约拦截。
     */
    public List<Map<String, Object>> getPendingAdjustmentsForClass(Long classId, Integer week) {
        if (classId == null) {
            return List.of();
        }
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT a.id, a.schedule_id, a.orig_week_day, a.orig_start_period, a.orig_end_period, " +
                    "a.target_week_day, a.target_start_period, a.target_end_period, a.target_week, a.week_range, " +
                    "a.semester, a.substitute_teacher_name " +
                    "FROM schedule_adjustment a WHERE a.status = 'pending' AND a.class_id = ? ");
            List<Object> args = new ArrayList<>();
            args.add(classId);
            String semester = currentSemesterName();
            if (!semester.isEmpty()) {
                sql.append("AND (a.semester = ? OR a.semester IS NULL OR a.semester = '') ");
                args.add(semester);
            }
            sql.append("ORDER BY a.orig_week_day, a.orig_start_period");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
                String weekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                if (week != null && !AdjustmentSupport.weekInRange(weekRange, week)) {
                    continue;
                }
                String subName = AdjustmentSupport.str(r.get("substitute_teacher_name"));
                Integer targetDay = AdjustmentSupport.intVal(r.get("target_week_day"));
                String targetText = subName.isEmpty()
                        ? (AdjustmentSupport.weekdayText(targetDay) + " "
                            + AdjustmentSupport.periodText(r.get("target_start_period"), r.get("target_end_period")).replace("第", ""))
                        : ("代课：" + subName);
                Map<String, Object> m = new HashMap<>();
                m.put("scheduleId", r.get("schedule_id"));
                m.put("origWeekDay", r.get("orig_week_day"));
                m.put("origStartPeriod", r.get("orig_start_period"));
                m.put("origEndPeriod", r.get("orig_end_period"));
                m.put("targetWeekDay", targetDay);
                m.put("targetStartPeriod", r.get("target_start_period"));
                m.put("targetEndPeriod", r.get("target_end_period"));
                m.put("targetWeek", r.get("target_week"));
                m.put("weekRange", weekRange);
                m.put("targetText", targetText);
                m.put("substituteTeacherName", subName);
                list.add(m);
            }
            return list;
        } catch (Exception e) {
            log.warn("加载待审批调课标记失败", e);
            return List.of();
        }
    }

    /**
     * 教师端：该教师作为代课教师的已批准调课（week 为 null 时不按周过滤）。
     * 用于教师端"我的课程"/课表展示代课安排。
     */
    public List<Map<String, Object>> getSubstituteAdjustmentsForTeacher(String teacherId, String teacherName, Integer week) {
        boolean hasId = teacherId != null && !teacherId.isEmpty();
        boolean hasName = teacherName != null && !teacherName.isEmpty();
        if (!hasId && !hasName) {
            return List.of();
        }
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT a.id AS adj_id, a.schedule_id, a.orig_week_day, a.orig_start_period, a.orig_end_period, " +
                    "a.target_week_day, a.target_start_period, a.target_end_period, a.target_week, a.week_range, a.semester, " +
                    "a.course_name, a.teacher_name AS orig_teacher_name, a.class_id, a.substitute_teacher_name, " +
                    "s.course_id, s.classroom_id, c.course_code, cl.classroom_no, cl.building, ci.class_name " +
                    "FROM schedule_adjustment a " +
                    "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                    "LEFT JOIN course c ON s.course_id = c.id " +
                    "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                    "LEFT JOIN class_info ci ON a.class_id = ci.id " +
                    "WHERE a.status = 'approved' AND a.target_schedule_id IS NULL AND " +
                    (hasId ? "a.substitute_teacher_id = ? " : "a.substitute_teacher_name = ? "));
            List<Object> args = new ArrayList<>();
            args.add(hasId ? teacherId : teacherName);
            String semester = currentSemesterName();
            if (!semester.isEmpty()) {
                sql.append("AND (a.semester = ? OR a.semester IS NULL OR a.semester = '') ");
                args.add(semester);
            }
            sql.append("ORDER BY a.target_week_day, a.target_start_period");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
                String weekRange = r.get("week_range") == null ? null : r.get("week_range").toString();
                Integer targetWeek = AdjustmentSupport.intVal(r.get("target_week"));
                if (week != null) {
                    boolean inWeek = targetWeek != null ? week.equals(targetWeek)
                            : AdjustmentSupport.weekInRange(weekRange, week);
                    if (!inWeek) {
                        continue;
                    }
                }
                Map<String, Object> m = new HashMap<>();
                m.put("adjustmentId", r.get("adj_id"));
                m.put("scheduleId", r.get("schedule_id"));
                m.put("courseId", r.get("course_id"));
                m.put("courseName", r.get("course_name"));
                m.put("courseCode", r.get("course_code"));
                m.put("classId", r.get("class_id"));
                m.put("className", r.get("class_name"));
                m.put("weekDay", r.get("target_week_day"));
                m.put("startPeriod", r.get("target_start_period"));
                m.put("endPeriod", r.get("target_end_period"));
                m.put("weekRange", weekRange);
                m.put("classroomId", r.get("classroom_id"));
                m.put("classroomNo", r.get("classroom_no"));
                m.put("building", r.get("building"));
                m.put("originalTeacherName", r.get("orig_teacher_name"));
                m.put("substituteTeacherName", r.get("substitute_teacher_name"));
                list.add(m);
            }
            return list;
        } catch (Exception e) {
            log.warn("加载教师代课安排失败", e);
            return List.of();
        }
    }

    /** 教师本人课程中处于"已有代课教师"的课表条目：scheduleId → 代课教师姓名（用于"我的课程"标注） */
    public Map<Long, String> substituteByScheduleForTeacher(String teacherId, String teacherName) {
        Map<Long, String> map = new HashMap<>();
        boolean hasId = teacherId != null && !teacherId.isEmpty();
        boolean hasName = teacherName != null && !teacherName.isEmpty();
        if (!hasId && !hasName) {
            return map;
        }
        try {
            String cond = AdjustmentSupport.teacherMatchCondition(hasId, hasName);
            List<Object> args = new ArrayList<>();
            if (hasId) args.add(teacherId);
            if (hasName) args.add(teacherName);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT a.schedule_id, a.substitute_teacher_name FROM schedule_adjustment a " +
                    "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                    "WHERE a.status = 'approved' AND a.target_schedule_id IS NULL " +
                    "AND a.substitute_teacher_name IS NOT NULL AND a.substitute_teacher_name <> '' AND " + cond,
                    args.toArray());
            for (Map<String, Object> r : rows) {
                Long sid = AdjustmentSupport.longVal(r.get("schedule_id"));
                if (sid != null) {
                    map.put(sid, AdjustmentSupport.str(r.get("substitute_teacher_name")));
                }
            }
        } catch (Exception e) {
            log.warn("加载课程代课标注失败", e);
        }
        return map;
    }

    /** 代课教师下拉选项：启用状态的教师账号（按姓名排序） */
    public List<Map<String, Object>> teacherOptions() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT student_id, name FROM user WHERE role = 'teacher' AND (status IS NULL OR status = 1) ORDER BY name")) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", r.get("student_id"));
                m.put("name", r.get("name"));
                list.add(m);
            }
        } catch (Exception e) {
            log.warn("加载代课教师选项失败", e);
        }
        return list;
    }

    /** 代课教师信息（仅启用状态的教师账号） */
    private Map<String, Object> loadSubstituteTeacher(String teacherId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT student_id, name FROM user WHERE student_id = ? AND role = 'teacher' AND (status IS NULL OR status = 1)",
                    teacherId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前学期名称（semester_config 最新一条）；未配置时返回空串，表示不按学期过滤 */
    private String currentSemesterName() {
        try {
            String name = jdbcTemplate.queryForObject(
                    "SELECT semester_name FROM semester_config ORDER BY id DESC LIMIT 1", String.class);
            return name == null ? "" : name.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 通用：查询生效中（approved）调课并补齐原课表展示字段（教室取换教室后的生效教室） */
    private List<Map<String, Object>> queryActive(String where, Object[] args, Integer week) {
        String semester = currentSemesterName();
        StringBuilder sql = new StringBuilder(
                "SELECT a.id AS adj_id, a.schedule_id, a.orig_week_day, a.orig_start_period, a.orig_end_period, " +
                "a.target_week_day, a.target_start_period, a.target_end_period, a.target_week, a.week_range, a.semester, " +
                "a.target_schedule_id, " +
                "a.course_name, a.teacher_name, a.class_id, a.reason, a.change_classroom, a.target_classroom_id, " +
                "a.substitute_teacher_id, a.substitute_teacher_name, " +
                "s.course_id, s.classroom_id, s.week_day, s.start_period, s.end_period, " +
                "c.course_code, cl.classroom_no, cl.building, ci.class_name, " +
                "tc.classroom_no AS target_classroom_no, tc.building AS target_building " +
                "FROM schedule_adjustment a " +
                "LEFT JOIN schedule s ON a.schedule_id = s.id " +
                "LEFT JOIN course c ON s.course_id = c.id " +
                "LEFT JOIN classroom cl ON s.classroom_id = cl.id " +
                "LEFT JOIN classroom tc ON a.target_classroom_id = tc.id " +
                "LEFT JOIN class_info ci ON a.class_id = ci.id " +
                "WHERE a.status = 'approved' AND " + where + " ");
        List<Object> queryArgs = new ArrayList<>(java.util.Arrays.asList(args));
        if (!semester.isEmpty()) {
            // 与课表读取口径一致：按当前学期过滤，历史空学期调课仍展示
            sql.append("AND (a.semester = ? OR a.semester IS NULL OR a.semester = '') ");
            queryArgs.add(semester);
        }
        sql.append("ORDER BY a.target_week_day, a.target_start_period");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), queryArgs.toArray());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String weekRange = row.get("week_range") == null ? null : row.get("week_range").toString();
            if (week != null && !AdjustmentSupport.weekInRange(weekRange, week)) {
                continue;
            }
            // 已物化为 schedule 行的调课（跨周 target_week / 部分周次拆分 target_schedule_id）不再叠加，避免重复展示
            if (AdjustmentSupport.intVal(row.get("target_week")) != null || row.get("target_schedule_id") != null) {
                continue;
            }
            boolean changed = row.get("change_classroom") != null
                    && ((Number) row.get("change_classroom")).intValue() == 1
                    && row.get("target_classroom_id") != null;
            Map<String, Object> m = new HashMap<>();
            m.put("id", row.get("adj_id"));
            m.put("scheduleId", row.get("schedule_id"));
            m.put("origWeekDay", row.get("orig_week_day"));
            m.put("origStartPeriod", row.get("orig_start_period"));
            m.put("origEndPeriod", row.get("orig_end_period"));
            m.put("targetWeekDay", row.get("target_week_day"));
            m.put("targetStartPeriod", row.get("target_start_period"));
            m.put("targetEndPeriod", row.get("target_end_period"));
            m.put("weekRange", weekRange);
            m.put("semester", row.get("semester"));
            m.put("courseName", row.get("course_name"));
            m.put("courseCode", row.get("course_code"));
            m.put("teacherName", row.get("teacher_name"));
            m.put("substituteTeacherId", row.get("substitute_teacher_id"));
            m.put("substituteTeacherName", row.get("substitute_teacher_name"));
            m.put("className", row.get("class_name"));
            m.put("changeClassroom", changed ? 1 : 0);
            m.put("targetClassroomId", row.get("target_classroom_id"));
            m.put("origClassroomNo", row.get("classroom_no"));
            Object classroomNo = changed ? row.get("target_classroom_no") : row.get("classroom_no");
            m.put("classroomNo", classroomNo);
            m.put("classroomId", changed ? row.get("target_classroom_id") : row.get("classroom_id"));
            m.put("location", classroomNo != null ? classroomNo : "无教室");
            m.put("building", changed ? row.get("target_building") : row.get("building"));
            m.put("reason", row.get("reason"));
            m.put("isAdjustment", true);
            // schedule 行时段是否已同步为目标时段（approved 后同步、撤销后恢复）；展示层据此决定叠加补偿
            m.put("slotSynced", AdjustmentSupport.slotSynced(row));
            list.add(m);
        }
        return list;
    }
}
