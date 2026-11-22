package com.zhixiaogang.service.classroom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ClassroomReservationMapper;
import com.zhixiaogang.service.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理端教室预约管理：列表（含教室编号）、批准、拒绝与撤销。
 * 从 AdminReservationController 下沉，控制器只负责路由与操作日志。
 * 返回 Map 中 {@code _logDetail} 为内部操作日志文案，控制器记录后需移除，不返回前端。
 */
@Service
public class AdminReservationService {

    public static final String LOG_DETAIL_KEY = "_logDetail";

    private static final Logger log = LoggerFactory.getLogger(AdminReservationService.class);

    @Autowired
    private ClassroomReservationMapper reservationMapper;

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ClassroomOccupancyService classroomOccupancyService;

    @Autowired
    private ClassroomReleaseNoticeService releaseNoticeService;

    /**
     * 预约列表（分页）：支持按状态、预约日期、关键词（学号/姓名/教室号/理由）过滤，
     * 返回当前页数据与总条数，前端据此渲染真分页；批量取教室编号避免 N+1。
     */
    public Map<String, Object> listReservations(Integer page, Integer size, String status, String date, String keyword) {
        Map<String, Object> result = new HashMap<>();

        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 100);
        String st = blankToNull(status);
        String kw = blankToNull(keyword);
        java.time.LocalDate d = parseDate(date);

        Long total = reservationMapper.selectCount(buildReservationQuery(st, d, kw));
        List<ClassroomReservation> reservations = reservationMapper.selectList(
                buildReservationQuery(st, d, kw)
                        .orderByDesc(ClassroomReservation::getCreateTime)
                        .orderByDesc(ClassroomReservation::getId)
                        .last("LIMIT " + s + " OFFSET " + ((long) (p - 1) * s)));

        List<Long> classroomIds = reservations.stream()
                .map(ClassroomReservation::getClassroomId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> classroomNos = new HashMap<>();
        if (!classroomIds.isEmpty()) {
            for (Classroom classroom : classroomMapper.selectBatchIds(classroomIds)) {
                classroomNos.put(classroom.getId(), classroom.getClassroomNo());
            }
        }

        List<Map<String, Object>> reservationList = new ArrayList<>();
        for (ClassroomReservation r : reservations) {
            Map<String, Object> rMap = new HashMap<>();
            rMap.put("id", r.getId());
            rMap.put("studentId", r.getStudentId());
            rMap.put("studentName", r.getStudentName());
            rMap.put("reservationDate", r.getReservationDate());
            rMap.put("startPeriod", r.getStartPeriod());
            rMap.put("endPeriod", r.getEndPeriod());
            rMap.put("reason", r.getReason());
            rMap.put("status", r.getStatus());
            rMap.put("createTime", r.getCreateTime());

            String classroomNo = r.getClassroomId() == null ? null : classroomNos.get(r.getClassroomId());
            rMap.put("classroomNo", classroomNo != null ? classroomNo : "-");
            reservationList.add(rMap);
        }

        result.put("success", true);
        result.put("reservations", reservationList);
        result.put("total", total == null ? 0 : total);
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    private LambdaQueryWrapper<ClassroomReservation> buildReservationQuery(String status, java.time.LocalDate date,
                                                                          String keyword) {
        LambdaQueryWrapper<ClassroomReservation> q = new LambdaQueryWrapper<>();
        q.eq(status != null, ClassroomReservation::getStatus, status)
                .eq(date != null, ClassroomReservation::getReservationDate, date);
        if (keyword != null) {
            List<Long> roomIds = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>()
                            .like(Classroom::getClassroomNo, keyword))
                    .stream().map(Classroom::getId).collect(Collectors.toList());
            q.and(w -> {
                w.like(ClassroomReservation::getStudentId, keyword)
                        .or().like(ClassroomReservation::getStudentName, keyword)
                        .or().like(ClassroomReservation::getReason, keyword);
                if (!roomIds.isEmpty()) {
                    w.or().in(ClassroomReservation::getClassroomId, roomIds);
                }
            });
        }
        return q;
    }

    private static String blankToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static java.time.LocalDate parseDate(String v) {
        String s = blankToNull(v);
        if (s == null) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 批准预约：复核课表/调课占用后原子条件更新，仅先到者生效并发送通知 */
    public Map<String, Object> approve(Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put(LOG_DETAIL_KEY, "批准教室预约 #" + id);
        try {
            ClassroomReservation pending = reservationMapper.selectById(id);
            if (pending == null) {
                result.put("success", false);
                result.put("message", "预约不存在");
                return result;
            }
            if (!"pending".equals(pending.getStatus())) {
                result.put("success", false);
                result.put("message", "仅待审核的预约可批准");
                return result;
            }
            // 审批时复核课表 / 已批准调课占用，防止提交后课表变化造成双占用
            String scheduleConflict = classroomOccupancyService.findScheduleConflict(
                    pending.getClassroomId(), pending.getReservationDate(),
                    pending.getStartPeriod(), pending.getEndPeriod());
            if (scheduleConflict != null) {
                result.put(LOG_DETAIL_KEY, "批准教室预约 #" + id + " 被占用拦截");
                result.put("success", false);
                result.put("message", scheduleConflict);
                return result;
            }
            // 原子条件更新：并发批准时仅先到者生效（rows=1），杜绝双重批准与重复通知
            int rows = reservationMapper.update(null,
                    new UpdateWrapper<ClassroomReservation>()
                            .eq("id", id).eq("status", "pending").set("status", "approved"));
            if (rows == 0) {
                result.put("success", false);
                result.put("message", "仅待审核的预约可批准");
                return result;
            }
            ClassroomReservation reservation = reservationMapper.selectById(id);
            notificationService.onReservationApproved(reservation);
            result.put("success", true);
            result.put("message", "审核通过");
        } catch (Exception e) {
            log.error("批准教室预约失败: id={}", id, e);
            result.put("success", false);
            result.put("message", "操作失败，请稍后重试");
        }
        return result;
    }

    /** 拒绝预约：原子条件更新，仅待审核可拒绝，并附拒绝原因通知发起人 */
    public Map<String, Object> reject(Long id, Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        result.put(LOG_DETAIL_KEY, "拒绝教室预约 #" + id + "：" + (reason == null ? "" : reason));
        try {
            int rows = reservationMapper.update(null,
                    new UpdateWrapper<ClassroomReservation>()
                            .eq("id", id).eq("status", "pending").set("status", "rejected"));
            if (rows == 0) {
                result.put("success", false);
                result.put("message", reservationMapper.selectById(id) == null ? "预约不存在" : "仅待审核的预约可拒绝");
                return result;
            }
            ClassroomReservation reservation = reservationMapper.selectById(id);
            notificationService.onReservationRejected(reservation, reason);
            result.put("success", true);
            result.put("message", "已拒绝");
        } catch (Exception e) {
            log.error("拒绝教室预约失败: id={}", id, e);
            result.put("success", false);
            result.put("message", "操作失败，请稍后重试");
        }
        return result;
    }

    /** 撤销已批准的预约（approved→cancelled），撤销原因随通知发给发起人 */
    public Map<String, Object> cancel(Long id, Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        result.put(LOG_DETAIL_KEY, "撤销教室预约 #" + id);
        try {
            String reason = body != null && body.get("reason") != null
                    ? String.valueOf(body.get("reason")).trim() : null;
            int rows = reservationMapper.update(null,
                    new UpdateWrapper<ClassroomReservation>()
                            .eq("id", id).eq("status", "approved").set("status", "cancelled"));
            if (rows == 0) {
                result.put("success", false);
                result.put("message", reservationMapper.selectById(id) == null ? "预约不存在" : "仅已通过的预约可撤销");
                return result;
            }
            ClassroomReservation reservation = reservationMapper.selectById(id);
            notificationService.onReservationCancelled(reservation, reason);
            releaseNoticeService.onReservationReleased(reservation);
            result.put("success", true);
            result.put("message", "已撤销该预约");
        } catch (Exception e) {
            log.error("撤销教室预约失败: id={}", id, e);
            result.put("success", false);
            result.put("message", "操作失败，请稍后重试");
        }
        return result;
    }
}
