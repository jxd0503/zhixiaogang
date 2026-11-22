package com.zhixiaogang.config;

import com.zhixiaogang.util.PasswordUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/** 启动时幂等建表、补列、补索引与演示数据修正，保证老库可平滑升级。 */
@Component
public class DatabaseInitConfig {

    /** data.sql 中种子账号口令占位符，启动时替换为随机口令 */
    private static final String SEED_PASSWORD_PLACEHOLDER = "{init}";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @PostConstruct
    public void init() {
        // 仅当数据库中还没有 user 表（即全新数据库）时才执行 schema.sql + data.sql；
        // 已有数据库则跳过，避免每次重启清空重置数据
        initSchemaIfNewDatabase();

        try {
            String createAiConfigTable = """
                CREATE TABLE IF NOT EXISTS ai_config (
                    id BIGINT PRIMARY KEY,
                    api_key VARCHAR(255) DEFAULT '',
                    api_url VARCHAR(500) DEFAULT 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
                    model VARCHAR(100) DEFAULT 'qwen-plus',
                    default_provider VARCHAR(50) DEFAULT 'rule-engine',
                    ai_disabled TINYINT DEFAULT 0,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI服务配置表'
            """;
            jdbcTemplate.execute(createAiConfigTable);

            jdbcTemplate.execute("INSERT INTO ai_config (id, api_key, api_url, model, default_provider, ai_disabled) VALUES (1, '', 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', 'qwen-plus', 'rule-engine', 0) ON DUPLICATE KEY UPDATE id = id");

            initAiProviders();

            initScheduleTeacherId();

            initCourseScheduleFields();

            initClassroomRoomType();

            initQueryIndexes();

            initScheduleAdjustmentTable();
            initAdjustmentExtTables();
            initNotificationTables();
            initScheduleConstraintRequestTable();
            initSemesterConfigTable();
            initOperationLogTables();

            initAdminUsers();

            initSeedUserPasswords();

            System.out.println("数据库初始化完成");
        } catch (Exception e) {
            System.err.println("数据库初始化失败：" + e.getMessage());
        }
    }

    private void initSchemaIfNewDatabase() {
        try {
            Boolean userTableExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user')",
                Boolean.class
            );

            if (Boolean.TRUE.equals(userTableExists)) {
                System.out.println("检测到数据库已存在（user 表已存在），跳过 schema.sql / data.sql 初始化");
                return;
            }

            System.out.println("首次启动：检测到全新数据库，开始执行 schema.sql + data.sql 初始化...");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("schema.sql"));
            populator.addScript(new ClassPathResource("data.sql"));
            populator.execute(dataSource);
            System.out.println("数据库脚本初始化完成（schema.sql + data.sql）");
        } catch (Exception e) {
            // 首次初始化失败时应用无法正常工作，直接抛出终止启动
            throw new IllegalStateException("数据库脚本初始化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 新建 ai_provider 表并完成首次预置（幂等）：
     * 1) 从旧 ai_config 迁移一条「通义千问」为生效提供商（沿用现有 api_url / api_key）；
     * 2) 预置一条停用状态的 DeepSeek 示例。
     * 预置只在 ai_provider 表为空时执行，不覆盖管理端后续修改。
     */
    private void initAiProviders() {
        try {
            String createAiProviderTable = """
                CREATE TABLE IF NOT EXISTS ai_provider (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '提供商ID',
                    name VARCHAR(50) NOT NULL COMMENT '提供商名称',
                    api_url VARCHAR(255) NOT NULL COMMENT 'OpenAI兼容端点',
                    api_key VARCHAR(255) DEFAULT '' COMMENT 'API Key',
                    models VARCHAR(500) NOT NULL COMMENT '可用模型，逗号分隔',
                    current_model VARCHAR(100) DEFAULT '' COMMENT '该提供商当前生效模型（空则取models第一个）',
                    multimodal_models VARCHAR(255) DEFAULT '' COMMENT '支持识图的多模态模型名，逗号分隔（当前生效模型在此列表内时管理端开放AI识图导入课表）',
                    enabled TINYINT DEFAULT 1 COMMENT '1启用 0停用',
                    is_default TINYINT DEFAULT 0 COMMENT '1生效中 0否（全局唯一）',
                    sort_order INT DEFAULT 0 COMMENT '排序',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI模型提供商表'
            """;
            jdbcTemplate.execute(createAiProviderTable);

            // 旧库补列（幂等）：multimodal_models
            try {
                jdbcTemplate.execute("ALTER TABLE ai_provider ADD COLUMN multimodal_models VARCHAR(255) DEFAULT '' COMMENT '支持识图的多模态模型名，逗号分隔（当前生效模型在此列表内时管理端开放AI识图导入课表）' AFTER current_model");
            } catch (Exception ignore) {
            }

            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_provider", Integer.class);
            if (count != null && count > 0) {
                return;
            }

            // 从 ai_config 迁移通义千问（api_key 原样沿用，保证与现有配置一致）
            String migrateQwen = """
                INSERT INTO ai_provider
                    (name, api_url, api_key, models, current_model, multimodal_models, enabled, is_default, sort_order, create_time, update_time)
                SELECT '通义千问',
                       COALESCE(NULLIF(ac.api_url, ''), 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'),
                       COALESCE(ac.api_key, ''),
                       'qwen3.8-flash,qwen-plus,qwen-turbo,qwen-max',
                       'qwen3.8-flash',
                       'qwen-vl-plus,qwen-vl-max',
                       1, 1, 0, NOW(), NOW()
                FROM ai_config ac
                WHERE ac.id = 1
            """;
            jdbcTemplate.update(migrateQwen);

            // 预置 DeepSeek 示例（停用、无 Key；启用前需由管理端填入 Key）
            String seedDeepSeek = """
                INSERT INTO ai_provider
                    (name, api_url, api_key, models, current_model, enabled, is_default, sort_order, create_time, update_time)
                SELECT 'DeepSeek',
                       'https://api.deepseek.com/v1/chat/completions',
                       '',
                       'deepseek-chat,deepseek-reasoner',
                       'deepseek-chat',
                       0, 0, 1, NOW(), NOW()
                FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM ai_provider WHERE name = 'DeepSeek')
            """;
            jdbcTemplate.update(seedDeepSeek);

            System.out.println("AI模型提供商表初始化完成：已迁移通义千问并预置DeepSeek");
        } catch (Exception e) {
            System.err.println("AI模型提供商表初始化失败：" + e.getMessage());
        }

        // 通义千问补写多模态模型（仅当为空时写入，不覆盖管理端修改）
        try {
            jdbcTemplate.update("""
                UPDATE ai_provider SET multimodal_models = 'qwen-vl-plus,qwen-vl-max'
                WHERE name = '通义千问' AND (multimodal_models IS NULL OR multimodal_models = '')
                """);
        } catch (Exception ignore) {
        }
    }

    /**
     * 课表架构改造补丁（幂等，兼容既有库）：
     * 1) schedule 表补 teacher_id 列；2) 按 user 表姓名反查回填教师工号。
     */
    private void initScheduleTeacherId() {
        try {
            try {
                jdbcTemplate.execute("ALTER TABLE schedule ADD COLUMN teacher_id VARCHAR(50) DEFAULT NULL COMMENT '授课教师工号（用于权限过滤与关联）' AFTER teacher_name");
                jdbcTemplate.execute("ALTER TABLE schedule ADD INDEX idx_teacher_id (teacher_id)");
            } catch (Exception ignore) {
                // 列已存在
            }
            jdbcTemplate.update("""
                UPDATE schedule s JOIN user u ON u.name = s.teacher_name AND u.role = 'teacher'
                SET s.teacher_id = u.student_id
                WHERE (s.teacher_id IS NULL OR s.teacher_id = '')
                """);
        } catch (Exception e) {
            System.err.println("schedule.teacher_id 迁移失败：" + e.getMessage());
        }
    }

    /**
     * AI 自动排课所需课程字段补丁（幂等，兼容既有库）：
     * 1) course 表补 week_hours 周学时列（默认每周 2 节）；2) 补 classroom_type 教室类型列。
     */
    private void initCourseScheduleFields() {
        try {
            try {
                jdbcTemplate.execute("ALTER TABLE course ADD COLUMN week_hours INT DEFAULT 2 COMMENT '每周学时（默认2节）' AFTER hours");
            } catch (Exception ignore) {
                // 列已存在
            }
            try {
                jdbcTemplate.execute("ALTER TABLE course ADD COLUMN classroom_type VARCHAR(50) DEFAULT '' COMMENT '教室类型（如机房/实训室/普通教室，空表示不限）' AFTER week_hours");
            } catch (Exception ignore) {
                // 列已存在
            }
            jdbcTemplate.update("UPDATE course SET week_hours = 2 WHERE week_hours IS NULL OR week_hours <= 0");
        } catch (Exception e) {
            System.err.println("course 排课字段迁移失败：" + e.getMessage());
        }
    }

    /**
     * 教室类型字段迁移（幂等，兼容既有库）：
     * classroom 表新增 room_type 列，并按旧的 has_projector / has_computer 自动映射：
     * 有电脑且有投影 → 多媒体教室；仅电脑无投影 → 微机室；两者都无 → 普通教室；无法判断按普通教室兜底。
     * 旧列保留不删除（不再参与任何逻辑），避免历史数据丢失。
     */
    private void initClassroomRoomType() {
        try {
            try {
                jdbcTemplate.execute("ALTER TABLE classroom ADD COLUMN room_type VARCHAR(30) NULL COMMENT '教室类型：普通教室/多媒体教室/微机室/实验室/语音室/室外场地' AFTER capacity");
            } catch (Exception ignore) {
                // 列已存在
            }
            try {
                jdbcTemplate.update("""
                    UPDATE classroom SET room_type = CASE
                        WHEN has_computer = 1 AND has_projector = 1 THEN '多媒体教室'
                        WHEN has_computer = 1 THEN '微机室'
                        ELSE '普通教室'
                    END
                    WHERE room_type IS NULL OR room_type = ''
                    """);
            } catch (Exception ignore) {
                // 旧列已不存在时跳过映射
            }
            jdbcTemplate.update("UPDATE classroom SET room_type = '普通教室' WHERE room_type IS NULL OR room_type = ''");
            try {
                jdbcTemplate.execute("ALTER TABLE classroom MODIFY COLUMN room_type VARCHAR(30) DEFAULT '普通教室' COMMENT '教室类型：普通教室/多媒体教室/微机室/实验室/语音室/室外场地'");
            } catch (Exception ignore) {
                // 默认值已设置
            }
        } catch (Exception e) {
            System.err.println("classroom 教室类型字段迁移失败：" + e.getMessage());
        }
    }

    /**
     * 高频查询索引补丁（幂等）：先查 information_schema.statistics 判断索引是否存在，
     * 不存在才 ADD INDEX，只调整索引、不改动任何业务数据。
     * 1) schedule：教室 + 星期 + 节次（冲突校验、教室占用查询）；
     * 2) class_member：班级 + 状态、学生 + 状态（成员/审核/所属班级查询）。
     */
    private void initQueryIndexes() {
        ensureIndex("schedule", "idx_room_day_period",
                "ALTER TABLE schedule ADD INDEX idx_room_day_period (classroom_id, week_day, start_period, end_period)");
        ensureIndex("class_member", "idx_class_status",
                "ALTER TABLE class_member ADD INDEX idx_class_status (class_id, status)");
        ensureIndex("class_member", "idx_student_status",
                "ALTER TABLE class_member ADD INDEX idx_student_status (student_id, status)");
    }

    private void ensureIndex(String table, String indexName, String addIndexSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                    Integer.class, table, indexName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(addIndexSql);
            }
        } catch (Exception e) {
            System.err.println("索引 " + indexName + " 迁移失败：" + e.getMessage());
        }
    }

    /**
     * 调课记录表（幂等）：新建 schedule_adjustment，供「调课申请与审批」功能使用。
     * 原 schedule 表记录一律不修改、不删除，调课仅作为展示层叠加。
     */
    private void initScheduleAdjustmentTable() {
        try {
            String createScheduleAdjustmentTable = """
                CREATE TABLE IF NOT EXISTS schedule_adjustment (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '调课记录ID',
                    schedule_id BIGINT COMMENT '原课表条目ID（保留关联，不修改原记录）',
                    class_id BIGINT COMMENT '班级ID',
                    course_id BIGINT COMMENT '课程ID',
                    course_name VARCHAR(100) COMMENT '课程名称（冗余展示用）',
                    teacher_name VARCHAR(50) COMMENT '授课教师姓名（冗余展示用）',
                    teacher_id VARCHAR(50) COMMENT '授课教师工号（权限过滤与关联用）',
                    orig_week_day INT COMMENT '原星期几: 1-7',
                    orig_start_period INT COMMENT '原开始节次',
                    orig_end_period INT COMMENT '原结束节次',
                    target_week_day INT COMMENT '目标星期几: 1-7',
                    target_start_period INT COMMENT '目标开始节次',
                    target_end_period INT COMMENT '目标结束节次',
                    target_week INT COMMENT '目标周次（null=仅调星期节次，不跨周）',
                    orig_week_range VARCHAR(100) COMMENT '原课表条目原始周次范围快照（跨周调课撤销用）',
                    orig_classroom_id BIGINT COMMENT '原教室ID快照（跨周调课撤销用）',
                    target_schedule_id BIGINT COMMENT '跨周调课生效后新建的课表行ID（撤销时删除）',
                    week_range VARCHAR(100) COMMENT '调课生效周次范围（默认整学期，如1-16）',
                    semester VARCHAR(20) COMMENT '学期',
                    reason VARCHAR(500) COMMENT '调课原因',
                    status VARCHAR(20) DEFAULT 'pending' COMMENT '状态: pending待审核 approved已批准 rejected已拒绝 cancelled已撤销或撤回',
                    apply_time DATETIME COMMENT '申请时间',
                    approve_time DATETIME COMMENT '审批时间',
                    approver VARCHAR(50) COMMENT '审批人',
                    reject_reason VARCHAR(500) COMMENT '拒绝或撤销原因',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    INDEX idx_status (status),
                    INDEX idx_teacher_id (teacher_id),
                    INDEX idx_class_id (class_id),
                    INDEX idx_schedule_id (schedule_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调课记录表'
            """;
            jdbcTemplate.execute(createScheduleAdjustmentTable);
            // AI 辅助调课：在原表上幂等补列，兼容历史数据
            String[] adjustColumns = {
                "ALTER TABLE schedule_adjustment ADD COLUMN target_classroom_id BIGINT DEFAULT NULL COMMENT '目标教室ID（null=沿用原教室）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN change_classroom TINYINT DEFAULT 0 COMMENT '是否换教室: 0沿用原教室 1换教室'",
                "ALTER TABLE schedule_adjustment ADD COLUMN ai_suggested TINYINT DEFAULT 0 COMMENT '是否来自AI推荐: 0手动 1AI推荐'",
                "ALTER TABLE schedule_adjustment ADD COLUMN source_type VARCHAR(20) DEFAULT NULL COMMENT '来源: ai AI推荐 local AI不可用本地规则 manual 手动选择'",
                "ALTER TABLE schedule_adjustment ADD COLUMN status_text VARCHAR(200) DEFAULT NULL COMMENT '提交时该候选的状态文字快照'",
                "ALTER TABLE schedule_adjustment ADD COLUMN conflict_result VARCHAR(20) DEFAULT NULL COMMENT '冲突结论: none/teacher/class/room/multi'",
                "ALTER TABLE schedule_adjustment ADD COLUMN conflict_detail VARCHAR(1000) DEFAULT NULL COMMENT '校验明细快照'",
                "ALTER TABLE schedule_adjustment ADD COLUMN check_time DATETIME DEFAULT NULL COMMENT '最近一次校验时间'",
                "ALTER TABLE schedule_adjustment ADD COLUMN check_by VARCHAR(50) DEFAULT NULL COMMENT '校验人'",
                "ALTER TABLE schedule_adjustment ADD COLUMN cancel_status VARCHAR(20) DEFAULT NULL COMMENT '撤销申请状态: null无 PENDING待审批 REJECTED已拒绝 DONE已完成'",
                "ALTER TABLE schedule_adjustment ADD COLUMN cancel_reason VARCHAR(500) DEFAULT NULL COMMENT '撤销申请原因'",
                "ALTER TABLE schedule_adjustment ADD COLUMN cancel_apply_time DATETIME DEFAULT NULL COMMENT '撤销申请时间'",
                "ALTER TABLE schedule_adjustment ADD COLUMN cancel_approver VARCHAR(50) DEFAULT NULL COMMENT '撤销审批人'",
                "ALTER TABLE schedule_adjustment ADD COLUMN cancel_approve_time DATETIME DEFAULT NULL COMMENT '撤销审批时间'",
                "ALTER TABLE schedule_adjustment ADD COLUMN cancel_reject_reason VARCHAR(500) DEFAULT NULL COMMENT '撤销申请被拒原因'",
                "ALTER TABLE schedule_adjustment ADD COLUMN target_week INT DEFAULT NULL COMMENT '目标周次（null=仅调星期节次，不跨周）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN orig_week_range VARCHAR(100) DEFAULT NULL COMMENT '原课表条目原始周次范围快照（跨周调课撤销用）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN orig_classroom_id BIGINT DEFAULT NULL COMMENT '原教室ID快照（跨周调课撤销用）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN target_schedule_id BIGINT DEFAULT NULL COMMENT '跨周调课生效后新建的课表行ID（撤销时删除）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN substitute_teacher_id VARCHAR(50) DEFAULT NULL COMMENT '代课教师工号（可选，审批通过后课表标注代课）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN substitute_teacher_name VARCHAR(50) DEFAULT NULL COMMENT '代课教师姓名（冗余展示用）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN last_remind_time DATETIME DEFAULT NULL COMMENT '待审批超时提醒的最近发送时间（防重复提醒）'",
                "ALTER TABLE schedule_adjustment ADD COLUMN emergency_flag TINYINT NOT NULL DEFAULT 0 COMMENT '是否紧急回滚: 0否 1是'",
                "ALTER TABLE schedule_adjustment ADD COLUMN emergency_reason VARCHAR(500) DEFAULT NULL COMMENT '紧急回滚原因'",
                "ALTER TABLE schedule_adjustment ADD COLUMN emergency_review_by VARCHAR(50) DEFAULT NULL COMMENT '紧急回滚补审人'",
                "ALTER TABLE schedule_adjustment ADD COLUMN emergency_review_time DATETIME DEFAULT NULL COMMENT '紧急回滚补审时间'",
                "ALTER TABLE schedule_adjustment ADD COLUMN peer_schedule_id BIGINT DEFAULT NULL COMMENT '教师互换：对方课表行ID'",
                "ALTER TABLE schedule_adjustment ADD COLUMN peer_teacher_id VARCHAR(50) DEFAULT NULL COMMENT '教师互换：对方教师工号快照'",
                "ALTER TABLE schedule_adjustment ADD COLUMN peer_teacher_name VARCHAR(50) DEFAULT NULL COMMENT '教师互换：对方教师姓名快照'",
                "ALTER TABLE schedule_adjustment ADD COLUMN peer_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '教师互换：对方是否已确认 0否 1是'",
                "ALTER TABLE schedule_adjustment ADD COLUMN peer_confirm_time DATETIME DEFAULT NULL COMMENT '教师互换：对方确认时间'",
                "ALTER TABLE schedule_adjustment ADD COLUMN swap_flag TINYINT NOT NULL DEFAULT 0 COMMENT '是否教师互换: 0普通调课 1互换'"
            };
            for (String sql : adjustColumns) {
                try {
                    jdbcTemplate.execute(sql);
                } catch (Exception ignore) {
                    // 列已存在
                }
            }
            ensureIndex("schedule_adjustment", "idx_adj_peer_schedule",
                    "ALTER TABLE schedule_adjustment ADD INDEX idx_adj_peer_schedule (peer_schedule_id)");
            ensureIndex("schedule_adjustment", "idx_adj_peer_teacher",
                    "ALTER TABLE schedule_adjustment ADD INDEX idx_adj_peer_teacher (peer_teacher_id)");
            ensureIndex("schedule_adjustment", "idx_adj_sub_teacher",
                    "ALTER TABLE schedule_adjustment ADD INDEX idx_adj_sub_teacher (substitute_teacher_id)");
            ensureIndex("schedule_adjustment", "idx_adj_status_apply",
                    "ALTER TABLE schedule_adjustment ADD INDEX idx_adj_status_apply (status, apply_time)");
            System.out.println("调课记录表初始化完成：schedule_adjustment");
        } catch (Exception e) {
            System.err.println("调课记录表初始化失败：" + e.getMessage());
        }
    }

    /**
     * 调课扩展表（幂等）：
     * 1) schedule_adjustment_attachment 调课凭证附件（两阶段：先上传拿 id，提交时绑定 adjustment_id）；
     * 2) schedule_adjustment_log 审批动作流水（谁、何时、动作、冲突快照、理由），用于全过程追溯。
     * 老库缺失时自动补建，不修改任何既有业务数据。
     */
    private void initAdjustmentExtTables() {
        try {
            String createAttachmentTable = """
                CREATE TABLE IF NOT EXISTS schedule_adjustment_attachment (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '附件ID',
                    adjustment_id BIGINT DEFAULT NULL COMMENT '关联调课记录ID（上传后绑定，未提交时为空）',
                    uploader_id VARCHAR(50) COMMENT '上传人工号（权限校验与孤儿文件清理）',
                    original_name VARCHAR(255) COMMENT '原始文件名',
                    stored_name VARCHAR(255) COMMENT '落盘文件名',
                    content_type VARCHAR(100) COMMENT '文件类型',
                    file_size BIGINT DEFAULT 0 COMMENT '文件大小（字节）',
                    upload_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
                    INDEX idx_adj (adjustment_id),
                    INDEX idx_uploader (uploader_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调课凭证附件表'
            """;
            jdbcTemplate.execute(createAttachmentTable);

            String createLogTable = """
                CREATE TABLE IF NOT EXISTS schedule_adjustment_log (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '流水ID',
                    adjustment_id BIGINT NOT NULL COMMENT '调课记录ID',
                    action VARCHAR(30) NOT NULL COMMENT '动作: apply提交/approve批准/force-approve强制放行/reject拒绝/withdraw撤回/cancel撤销/emergency-rollback紧急回滚/emergency-review紧急补审/remind超时提醒',
                    operator_id VARCHAR(50) COMMENT '操作人工号',
                    operator_name VARCHAR(50) COMMENT '操作人姓名',
                    operator_role VARCHAR(20) COMMENT '操作人角色: student/teacher/admin/system',
                    conflict_snapshot VARCHAR(1000) COMMENT '操作时看到的冲突快照',
                    reason VARCHAR(500) COMMENT '强制放行/拒绝/紧急回滚等理由',
                    detail VARCHAR(500) COMMENT '动作补充说明',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
                    INDEX idx_adj (adjustment_id),
                    INDEX idx_create_time (create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调课审批动作流水表'
            """;
            jdbcTemplate.execute(createLogTable);
            System.out.println("调课扩展表初始化完成：schedule_adjustment_attachment / schedule_adjustment_log");
        } catch (Exception e) {
            System.err.println("调课扩展表初始化失败：" + e.getMessage());
        }
    }

    /**
     * 通用通知表（幂等）：系统自动通知与管理端自定义通知共用 notifications 表，type 区分。
     * notification_send_log 记录管理端自定义通知的每次发送（标题、内容、范围、条数），供历史追溯。
     */
    private void initNotificationTables() {
        try {
            String createNotificationsTable = """
                CREATE TABLE IF NOT EXISTS notifications (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '通知ID',
                    user_id BIGINT NOT NULL COMMENT '接收用户ID（user.id）',
                    type VARCHAR(30) COMMENT '通知类型: adjustment调课 / reservation教室预约 / custom管理端自定义 / class_application班级申请（含审核结果） / schedule课表发布',
                    title VARCHAR(200) COMMENT '通知标题',
                    content VARCHAR(1000) COMMENT '通知内容（纯文本）',
                    related_id BIGINT COMMENT '关联业务ID（如调课申请id、预约id）',
                    is_read TINYINT DEFAULT 0 COMMENT '是否已读: 0未读 1已读',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    INDEX idx_user_read (user_id, is_read),
                    INDEX idx_user_time (user_id, create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用通知表'
            """;
            jdbcTemplate.execute(createNotificationsTable);

            String createSendLogTable = """
                CREATE TABLE IF NOT EXISTS notification_send_log (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '发送记录ID',
                    title VARCHAR(200) COMMENT '通知标题',
                    content VARCHAR(1000) COMMENT '通知内容（纯文本）',
                    scope VARCHAR(30) COMMENT '发送范围: all_users / all_students / class',
                    class_id BIGINT COMMENT '指定班级ID（scope=class 时）',
                    class_name VARCHAR(100) COMMENT '班级名称（冗余展示用）',
                    receiver_count INT COMMENT '本次实际发送条数',
                    sender VARCHAR(50) COMMENT '发送人（管理员）',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
                    INDEX idx_create_time (create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知发送记录表'
            """;
            jdbcTemplate.execute(createSendLogTable);
            System.out.println("通知表初始化完成：notifications / notification_send_log");
        } catch (Exception e) {
            System.err.println("通知表初始化失败：" + e.getMessage());
        }
    }

    /**
     * 教师排课要求提交表（幂等）：教师以自然语言提交个人排课约束，管理端 AI 排课时加载填入。
     * status: PENDING待处理 / ADOPTED已采纳 / REJECTED无法满足 / CANCELLED教师取消 / EXPIRED过期
     */
    private void initScheduleConstraintRequestTable() {
        try {
            String createTable = """
                CREATE TABLE IF NOT EXISTS schedule_constraint_request (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '排课要求ID',
                    teacher_id BIGINT NOT NULL COMMENT '教师用户ID（user.id）',
                    teacher_name VARCHAR(50) COMMENT '教师姓名（冗余展示用）',
                    semester VARCHAR(50) COMMENT '学期名称',
                    class_id BIGINT COMMENT '适用班级ID（空表示不限定）',
                    class_name VARCHAR(100) COMMENT '适用班级名称（冗余展示用）',
                    constraint_text VARCHAR(1000) NOT NULL COMMENT '原始自然语言约束',
                    parsed_json TEXT COMMENT 'AI 预解析的约束条目 JSON',
                    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态: PENDING待处理 ADOPTED已采纳 REJECTED无法满足 CANCELLED教师取消 EXPIRED过期',
                    admin_note VARCHAR(500) COMMENT '管理端处理备注（采纳说明 / 驳回原因）',
                    apply_result VARCHAR(500) COMMENT '采纳后排课应用中是否全部生效',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_teacher_id (teacher_id),
                    INDEX idx_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师排课要求提交表'
            """;
            jdbcTemplate.execute(createTable);
            System.out.println("教师排课要求表初始化完成：schedule_constraint_request");
        } catch (Exception e) {
            System.err.println("教师排课要求表初始化失败：" + e.getMessage());
        }
    }

    /**
     * 学期配置表（幂等）：semester_config 记录当前学期名称、开始日期与持续周数，
     * 是 AI 排课周次、教室占用区间与调课周次计算的基准。旧库缺失该表时自动补齐。
     */
    private void initSemesterConfigTable() {
        try {
            String createTable = """
                CREATE TABLE IF NOT EXISTS semester_config (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
                    semester_name VARCHAR(128) NOT NULL COMMENT '学期名称，如 2026-2027 学年第一学期',
                    start_date DATE NOT NULL COMMENT '学期开始日期',
                    weeks INT NOT NULL COMMENT '持续周数，1-40',
                    end_date DATE NOT NULL COMMENT '学期结束日期（自动计算）',
                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学期配置表'
            """;
            jdbcTemplate.execute(createTable);
            System.out.println("学期配置表初始化完成：semester_config");
        } catch (Exception e) {
            System.err.println("学期配置表初始化失败：" + e.getMessage());
        }
    }

    /**
     * 操作日志表（幂等）：三端写操作统一落库，字段精简（不存请求/响应全文、不存长文本）。
     * system_config 承载日志保留天数、是否记录读操作等开关。
     */
    private void initOperationLogTables() {
        try {
            String createLogTable = """
                CREATE TABLE IF NOT EXISTS operation_log (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
                    user_id BIGINT COMMENT '操作人用户ID（user.id，未登录为空）',
                    username VARCHAR(50) COMMENT '操作人账号',
                    role VARCHAR(20) COMMENT '操作人角色: student/teacher/admin',
                    action VARCHAR(30) NOT NULL COMMENT '操作类型: login/logout/apply/approve/reject/import/export/generate/confirm/update/delete/query',
                    target_type VARCHAR(40) COMMENT '操作对象类型: schedule/classroom/course/class/user/reservation/adjustment/notification/semester/ai_provider',
                    target_id VARCHAR(64) COMMENT '操作对象ID',
                    detail VARCHAR(255) COMMENT '关键参数摘要（≤100字）',
                    result VARCHAR(255) COMMENT '结果: success 或 failed: 简短原因',
                    ip VARCHAR(64) COMMENT '客户端IP',
                    cost_ms INT COMMENT '接口耗时(ms)',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
                    INDEX idx_user_id (user_id),
                    INDEX idx_action (action),
                    INDEX idx_create_time (create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表'
            """;
            jdbcTemplate.execute(createLogTable);

            String createConfigTable = """
                CREATE TABLE IF NOT EXISTS system_config (
                    config_key VARCHAR(64) PRIMARY KEY COMMENT '配置键',
                    config_value VARCHAR(255) COMMENT '配置值',
                    description VARCHAR(200) COMMENT '配置说明',
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表'
            """;
            jdbcTemplate.execute(createConfigTable);

            jdbcTemplate.update("INSERT INTO system_config (config_key, config_value, description) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE config_key = config_key",
                    "operation_log.retention_days", "90", "操作日志保留天数");
            jdbcTemplate.update("INSERT INTO system_config (config_key, config_value, description) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE config_key = config_key",
                    "operation_log.record_all_reads", "0", "是否记录所有读操作: 1开启 0关闭");
            jdbcTemplate.update("INSERT INTO system_config (config_key, config_value, description) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE config_key = config_key",
                    "operation_log.file_enabled", "1", "是否写文件日志: 1开启 0关闭");
            jdbcTemplate.update("INSERT INTO system_config (config_key, config_value, description) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE config_key = config_key",
                    "operation_log.file_keep_days", "30", "文件日志保留天数");
            jdbcTemplate.update("INSERT INTO system_config (config_key, config_value, description) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE config_key = config_key",
                    "operation_log.archive_enabled", "0", "清理前是否归档为CSV: 1开启 0关闭");
            jdbcTemplate.update("INSERT INTO system_config (config_key, config_value, description) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE config_key = config_key",
                    "adjustment.sla_hours", "48", "调课待审批超时提醒阈值（小时）");
            System.out.println("操作日志表初始化完成：operation_log / system_config");
        } catch (Exception e) {
            System.err.println("操作日志表初始化失败：" + e.getMessage());
        }
    }

    /**
     * 管理员账号：仅当 admin 账号不存在时创建一次，初始口令随机生成并打印一次。
     * 不再每次启动重置密码或禁用其他账号，避免覆盖线上修改。
     */
    private void initAdminUsers() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE student_id = 'admin'", Integer.class);
            if (count != null && count > 0) {
                System.out.println("管理员账号已存在，跳过创建");
                return;
            }

            String rawPassword = PasswordUtils.generateRandomPassword();
            jdbcTemplate.update(
                "INSERT INTO user (student_id, password, name, role, department, status) VALUES ('admin', ?, '管理员', 'admin', '信息中心', 1)",
                PasswordUtils.encode(rawPassword));
            System.out.println("=================================================");
            System.out.println("已创建管理员账号：admin / " + rawPassword + "（请登录后立即修改）");
            System.out.println("=================================================");
        } catch (Exception e) {
            System.err.println("管理员账号初始化失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 种子账号初始口令：data.sql 中以占位符写入，首次启动替换为随机口令并打印一次，
     * 避免演示账号统一使用固定弱口令。仅处理仍处于占位符状态的种子学号/工号。
     */
    private void initSeedUserPasswords() {
        try {
            List<String> seedIds = List.of("2024001", "2024002", "2024003", "2024007", "t001", "t002");
            for (String seedId : seedIds) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user WHERE student_id = ? AND password = ?",
                        Integer.class, seedId, SEED_PASSWORD_PLACEHOLDER);
                if (count == null || count == 0) {
                    continue;
                }
                String rawPassword = PasswordUtils.generateRandomPassword();
                jdbcTemplate.update("UPDATE user SET password = ? WHERE student_id = ?",
                        PasswordUtils.encode(rawPassword), seedId);
                System.out.println("已为种子账号 " + seedId + " 生成初始口令：" + rawPassword);
            }
        } catch (Exception e) {
            System.err.println("种子账号初始口令初始化失败：" + e.getMessage());
        }
    }
}
