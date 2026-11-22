package com.zhixiaogang.service.notification;

import com.zhixiaogang.entity.Classroom;
import com.zhixiaogang.entity.ClassroomReservation;
import com.zhixiaogang.mapper.ClassroomMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 业务事件通知：调课审批/撤回/撤销、教室预约审批/撤销、班级入班/退班申请等场景的文案组装与发送。
 * 所有通知发送失败均不影响原业务（由 NotificationDeliveryService 内部兜底）。
 */
@Service
public class NotificationEventService {

    /** 班级申请提醒文案前缀：入班与退班分开定义，供文案拼装与幂等去重查询共用，避免日后改文案导致去重失配 */
    private static final String CLASS_APP_JOIN_PREFIX = "有学生申请加入你的班级";
    private static final String CLASS_APP_WITHDRAW_PREFIX = "有学生申请退出你的班级";

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationRecipientService recipientService;

    @Autowired
    private ClassroomMapper classroomMapper;

    /**
     * 调课批准：向发起教师发「调课已批准」，向班级全部在读学生发「调课通知」。
     * content 由调用方组装（含课程、原时段、目标时段）。
     */
    public void onAdjustmentApproved(Long relatedId, String teacherStudentId, String teacherName,
                                     Long classId, String teacherTitle, String studentTitle) {
        Long teacherUid = resolveTeacher(teacherStudentId, teacherName);
        deliveryService.create(teacherUid, "adjustment", "调课已批准", teacherTitle, relatedId);
        deliveryService.notifyClassStudents(classId, "adjustment", "调课通知", studentTitle, relatedId);
    }

    /** 调课被拒绝（含批准时校验冲突转拒绝）：仅通知发起教师，学生不收通知 */
    public void onAdjustmentRejected(Long relatedId, String teacherStudentId, String teacherName, String teacherTitle) {
        Long teacherUid = resolveTeacher(teacherStudentId, teacherName);
        deliveryService.create(teacherUid, "adjustment", "调课申请未通过", teacherTitle, relatedId);
    }

    /** 教师自行撤回调课申请：仅通知发起教师本人，内容含课程与原/目标时段 */
    public void onAdjustmentWithdrawn(Long relatedId, String teacherStudentId, String teacherName, String teacherTitle) {
        Long teacherUid = resolveTeacher(teacherStudentId, teacherName);
        deliveryService.create(teacherUid, "adjustment", "调课申请已撤回", teacherTitle, relatedId);
    }

    /** 调课撤销：向班级学生与发起教师各发「调课已撤销」 */
    public void onAdjustmentCancelled(Long relatedId, String teacherStudentId, String teacherName,
                                      Long classId, String teacherTitle, String studentTitle) {
        Long teacherUid = resolveTeacher(teacherStudentId, teacherName);
        deliveryService.create(teacherUid, "adjustment", "调课已撤销", teacherTitle, relatedId);
        deliveryService.notifyClassStudents(classId, "adjustment", "调课已撤销", studentTitle, relatedId);
    }

    /** 撤销调课申请被管理员拒绝：调课保持生效，仅通知发起教师 */
    public void onAdjustmentCancelRejected(Long relatedId, String teacherStudentId, String teacherName, String teacherTitle) {
        Long teacherUid = resolveTeacher(teacherStudentId, teacherName);
        deliveryService.create(teacherUid, "adjustment", "撤销申请未通过", teacherTitle, relatedId);
    }

    /** 教室预约批准：通知预约发起人（学生或教师），内容含教室名、日期、节次与用途 */
    public void onReservationApproved(ClassroomReservation r) {
        deliveryService.create(recipientService.findUserId(r.getStudentId()), "reservation", "教室预约已通过",
                "你预约的 " + reservationBrief(r) + "已通过审核", r.getId());
    }

    /** 教室预约被拒：通知发起人，内容含教室名、日期、节次与用途，附审批拒绝原因（如有） */
    public void onReservationRejected(ClassroomReservation r, String reason) {
        String text = "你预约的 " + reservationBrief(r) + "未通过审核";
        if (reason != null && !reason.trim().isEmpty()) {
            text += "，原因：" + reason.trim();
        }
        deliveryService.create(recipientService.findUserId(r.getStudentId()), "reservation", "教室预约被拒绝", text, r.getId());
    }

    /** 已批准预约被管理端撤销：通知发起人，含撤销原因，无原因时也以"已取消"收尾 */
    public void onReservationCancelled(ClassroomReservation r, String reason) {
        String text = reservationText(r) + "已取消";
        if (reason != null && !reason.trim().isEmpty()) {
            text += "，原因：" + reason.trim();
        }
        deliveryService.create(recipientService.findUserId(r.getStudentId()), "reservation", "预约已取消", text, r.getId());
    }

    /**
     * 班级申请审核结果：向申请学生（按学号解析用户）发通知，relatedId 为申请记录（class_member.id）。
     * 学号解析不到用户或写入失败时静默忽略，不影响审核流程。
     */
    public void onClassApplicationResult(String studentId, String title, String content, Long relatedId) {
        deliveryService.create(recipientService.findUserId(studentId), "class_application", title, content, relatedId);
    }

    /**
     * 学生提交入班 / 退班申请：通知目标班级的管理教师「有新申请待审核」。
     * 教师按 class_info.teacher_id → user.student_id 解析账号，解析不到则静默跳过（不报错）。
     * type 统一为 class_application，relatedId 为申请记录（class_member.id）；
     * 幂等去重按「同一教师 + 同一申请记录 + 同一类别（加入/退出）」判断：
     * 入班与退班发生在同一条 class_member 记录上（同一 id），二者类别不同必须各自提醒一次，
     * 仅当同一记录同一类别的未读提醒已存在时才跳过，避免同一 pending 申请反复打扰教师。
     */
    public void onClassApplicationSubmitted(Long classId, String studentName, boolean withdraw, Long applicationId) {
        if (classId == null) {
            return;
        }
        Long teacherUid = recipientService.classTeacherUserId(classId);
        if (teacherUid == null) {
            return;
        }
        if (recipientService.hasUnreadClassApplication(teacherUid, applicationId,
                CLASS_APP_JOIN_PREFIX, CLASS_APP_WITHDRAW_PREFIX, withdraw)) {
            return;
        }
        String content = (withdraw ? CLASS_APP_WITHDRAW_PREFIX : CLASS_APP_JOIN_PREFIX)
                + "【" + recipientService.classNameOf(classId) + "】：" + nz(studentName) + " 同学，请及时审核";
        deliveryService.create(teacherUid, "class_application", "有新申请待审核", content, applicationId);
    }

    /** 教师账号解析：优先工号，工号缺失时按姓名兜底 */
    private Long resolveTeacher(String teacherStudentId, String teacherName) {
        return teacherStudentId != null && !teacherStudentId.isEmpty()
                ? recipientService.findUserId(teacherStudentId) : recipientService.findUserIdByName(teacherName);
    }

    /** 预约通知公共文案：教室名 日期 节次（用途），用途为空时省略括号 */
    private String reservationBrief(ClassroomReservation r) {
        String roomNo = "-";
        if (r.getClassroomId() != null) {
            Classroom c = classroomMapper.selectById(r.getClassroomId());
            if (c != null && c.getClassroomNo() != null) {
                roomNo = c.getClassroomNo();
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(roomNo).append(" ").append(r.getReservationDate()).append(" ")
                .append(segmentPeriodText(r.getStartPeriod(), r.getEndPeriod()).trim());
        String purpose = r.getReason() == null ? "" : r.getReason().trim();
        if (!purpose.isEmpty()) {
            sb.append("（").append(purpose).append("）");
        }
        return sb.toString();
    }

    /** 预约通知公共文案：以"的申请"结尾，各场景自行追加动作词（已通过/未通过/已取消） */
    private String reservationText(ClassroomReservation r) {
        String roomNo = "-";
        if (r.getClassroomId() != null) {
            Classroom c = classroomMapper.selectById(r.getClassroomId());
            if (c != null && c.getClassroomNo() != null) {
                roomNo = c.getClassroomNo();
            }
        }
        return "你在 " + r.getReservationDate() + " " + segmentPeriodText(r.getStartPeriod(), r.getEndPeriod())
                + "预约教室 " + roomNo + " 的申请";
    }

    /** 预约段号(1-5)换算为节次范围文本，单段如"第9-10节 "，跨段如"第5-6至9-10节 " */
    private static String segmentPeriodText(int segStart, int segEnd) {
        int ps = (segStart - 1) * 2 + 1;
        int pe = segEnd * 2;
        if (segStart == segEnd) {
            return "第" + ps + "-" + pe + "节 ";
        }
        int es = (segEnd - 1) * 2 + 1;
        int ee = segEnd * 2;
        return "第" + ps + "-" + (ps + 1) + "至" + es + "-" + ee + "节 ";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
