package com.zhixiaogang.service.classroom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.mapper.ClassroomMapper;
import com.zhixiaogang.mapper.ClassroomReservationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/********** 教室预约域：预约提交（行锁 + 重叠校验）、我的预约、取消待审核预约 **********/
@Service
public class ClassroomReservationService {

    @Autowired
    private ClassroomMapper classroomMapper;

    @Autowired
    private ClassroomReservationMapper reservationMapper;

    @Autowired
    private ClassroomOccupancyService classroomOccupancyService;

    @Autowired
    private ClassroomReleaseNoticeService releaseNoticeService;

    /**
     * P0-1 并发安全的教室预约：
     * 事务内先对 classroom 行加 FOR UPDATE 行锁（串行化同一教室的并发请求），
     * 再对所有未取消状态（pending/approved）做节次重叠检查，最后插入。
     * uk_active_slot 唯一索引作为最终防线（同教室同日期同起止节次仅一条生效记录），
     * 唯一键冲突时返回友好提示。rejected 的记录 active_slot 为 NULL，不再阻塞重新预约。
     * 业务口径：待审核(pending)预约同样占用时段（先到先得，管理员按提交顺序审核）。
     */
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> reserveClassroom(Long classroomId, String studentId, String studentName,
                                                String reason, LocalDate reservationDate,
                                                Integer startPeriod, Integer endPeriod) {
        Map<String, Object> result = new HashMap<>();

        // P1-5 过去日期校验（当天允许；不额外限制节次，聊天流程本身取下一节次）
        if (reservationDate == null || reservationDate.isBefore(LocalDate.now())) {
            result.put("success", false);
            result.put("message", "不能预约过去的日期");
            return result;
        }

        // 与知识库「可提前 7 天预约」口径一致：最远只能预约今天之后第 7 天
        if (reservationDate.isAfter(LocalDate.now().plusDays(7))) {
            result.put("success", false);
            result.put("message", "最多只能提前 7 天预约");
            return result;
        }

        // 预约原因长度与前端 maxlength 保持一致
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.length() > 100) {
            result.put("success", false);
            result.put("message", "预约原因不能超过 100 字");
            return result;
        }

        // 当天预约：开始节次的上课时间必须晚于当前时间，拦截今天已过去的节次
        if (reservationDate.equals(LocalDate.now()) && startPeriod != null) {
            LocalTime startTime = ClassroomService.PERIOD_START_TIMES.get(startPeriod);
            if (startTime != null && !LocalTime.now().isBefore(startTime)) {
                result.put("success", false);
                result.put("message", "所选节次已过，请选择之后的时段");
                return result;
            }
        }

        // 节次段范围校验（1-5，分别对应 1-2 … 9-10 节）
        if (startPeriod == null || endPeriod == null
                || startPeriod < 1 || endPeriod > 5 || startPeriod > endPeriod) {
            result.put("success", false);
            result.put("message", "预约节次不正确");
            return result;
        }

        // 行锁：同一教室的并发预约在此串行
        Classroom classroom = classroomMapper.selectByIdForUpdate(classroomId);
        if (classroom == null) {
            result.put("success", false);
            result.put("message", "教室不存在");
            return result;
        }

        if (!"available".equals(classroom.getStatus())) {
            result.put("success", false);
            result.put("message", "该教室当前不可用");
            return result;
        }

        // 课表 / 已批准调课占用校验（按日期所在学期周次精确判定）
        String scheduleConflict = classroomOccupancyService.findScheduleConflict(
                classroomId, reservationDate, startPeriod, endPeriod);
        if (scheduleConflict != null) {
            result.put("success", false);
            result.put("message", scheduleConflict);
            return result;
        }

        // 重叠检查覆盖 pending + approved（部分重叠也拦截）
        List<ClassroomReservation> activeReservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ClassroomReservation>()
                        .eq(ClassroomReservation::getClassroomId, classroomId)
                        .eq(ClassroomReservation::getReservationDate, reservationDate)
                        .in(ClassroomReservation::getStatus, java.util.Arrays.asList("pending", "approved")));

        for (ClassroomReservation reservation : activeReservations) {
            // 节次段为闭区间（start/end 均取 1-5，单段预约起止相同）
            if (!(endPeriod < reservation.getStartPeriod() || startPeriod > reservation.getEndPeriod())) {
                result.put("success", false);
                result.put("message", "该时间段已有预约，请选择其他时间");
                return result;
            }
        }

        ClassroomReservation reservation = new ClassroomReservation();
        reservation.setClassroomId(classroomId);
        reservation.setStudentId(studentId);
        reservation.setStudentName(studentName);
        reservation.setReason(trimmedReason);
        reservation.setReservationDate(reservationDate);
        reservation.setStartPeriod(startPeriod);
        reservation.setEndPeriod(endPeriod);
        reservation.setStatus("pending");

        try {
            reservationMapper.insert(reservation);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下唯一索引兜底：完全相同时段已被其他请求抢先
            result.put("success", false);
            result.put("message", "该时间段已有预约，请选择其他时间");
            return result;
        }

        result.put("success", true);
        result.put("id", reservation.getId());
        result.put("message", "预约申请已提交，等待审核");
        return result;
    }

    /** P1-4 查询某学生的预约（按日期倒序，最多返回最近 50 条，避免列表无限增长） */
    public List<ClassroomReservation> getReservationsByStudent(String studentId) {
        return reservationMapper.selectList(new LambdaQueryWrapper<ClassroomReservation>()
                .eq(ClassroomReservation::getStudentId, studentId)
                .orderByDesc(ClassroomReservation::getReservationDate)
                .orderByDesc(ClassroomReservation::getId)
                .last("LIMIT 50"));
    }

    /** 学生端「我的预约」：分页返回本人预约（含教室编号，教室信息批量查询避免 N+1） */
    public Map<String, Object> getStudentReservations(String studentId, Integer page, Integer size) {
        Map<String, Object> result = new HashMap<>();
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 100);

        Long total = reservationMapper.selectCount(new LambdaQueryWrapper<ClassroomReservation>()
                .eq(ClassroomReservation::getStudentId, studentId));
        List<ClassroomReservation> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<ClassroomReservation>()
                        .eq(ClassroomReservation::getStudentId, studentId)
                        .orderByDesc(ClassroomReservation::getReservationDate)
                        .orderByDesc(ClassroomReservation::getId)
                        .last("LIMIT " + s + " OFFSET " + ((long) (p - 1) * s)));

        List<Long> classroomIds = reservations.stream()
                .map(ClassroomReservation::getClassroomId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        Map<Long, String> classroomNos = new HashMap<>();
        if (!classroomIds.isEmpty()) {
            for (Classroom c : classroomMapper.selectBatchIds(classroomIds)) {
                classroomNos.put(c.getId(), c.getClassroomNo());
            }
        }

        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (ClassroomReservation r : reservations) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", r.getId());
            item.put("classroomId", r.getClassroomId());
            String classroomNo = r.getClassroomId() == null ? null : classroomNos.get(r.getClassroomId());
            item.put("classroomNo", classroomNo != null ? classroomNo : "-");
            item.put("reservationDate", r.getReservationDate());
            item.put("startPeriod", r.getStartPeriod());
            item.put("endPeriod", r.getEndPeriod());
            item.put("reason", r.getReason());
            item.put("status", r.getStatus());
            item.put("createTime", r.getCreateTime());
            list.add(item);
        }
        result.put("success", true);
        result.put("reservations", list);
        result.put("total", total == null ? 0 : total);
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    /** 学生取消本人待审核预约：pending → cancelled（原子条件更新，仅本人可操作） */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> cancelPendingReservation(Long id, String studentId) {
        Map<String, Object> result = new HashMap<>();
        ClassroomReservation reservation = id == null ? null : reservationMapper.selectById(id);
        if (reservation == null || studentId == null || !studentId.equals(reservation.getStudentId())) {
            result.put("success", false);
            result.put("message", "预约不存在");
            return result;
        }
        int rows = reservationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ClassroomReservation>()
                        .eq("id", id).eq("student_id", studentId).eq("status", "pending")
                        .set("status", "cancelled"));
        if (rows == 0) {
            ClassroomReservation latest = reservationMapper.selectById(id);
            String status = latest == null ? "" : latest.getStatus();
            String message;
            if ("approved".equals(status)) {
                message = "预约已通过，无法取消";
            } else if ("rejected".equals(status)) {
                message = "预约已被拒绝";
            } else if ("cancelled".equals(status)) {
                message = "预约已取消";
            } else {
                message = "仅待审核的预约可取消";
            }
            result.put("success", false);
            result.put("message", message);
            return result;
        }
        releaseNoticeService.onReservationReleased(reservation);
        result.put("success", true);
        result.put("message", "已取消预约");
        return result;
    }
}
