-- 本文件是唯一的建表脚本，由 DatabaseInitConfig 幂等初始化调用，配套 data.sql 提供初始数据。
-- 建表在当前连接所选中的数据库内执行，不在脚本里写死库名。

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS class_member;
DROP TABLE IF EXISTS class_info;
DROP TABLE IF EXISTS chat_log;
DROP TABLE IF EXISTS classroom_reservation;
DROP TABLE IF EXISTS schedule_adjustment;
DROP TABLE IF EXISTS knowledge_base;
DROP TABLE IF EXISTS schedule;
DROP TABLE IF EXISTS classroom;
DROP TABLE IF EXISTS course;
DROP TABLE IF EXISTS user;

SET FOREIGN_KEY_CHECKS = 1;
CREATE TABLE user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    student_id VARCHAR(50) NOT NULL UNIQUE COMMENT '学号/工号',
    password VARCHAR(100) NOT NULL COMMENT '密码',
    name VARCHAR(50) NOT NULL COMMENT '姓名',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(100) COMMENT '邮箱',
    role VARCHAR(20) DEFAULT 'student' COMMENT '角色: student/teacher/admin',
    department VARCHAR(100) COMMENT '院系/部门',
    major VARCHAR(100) COMMENT '专业',
    grade VARCHAR(20) COMMENT '年级',
    status INT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_student_id (student_id),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE course (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课程ID',
    course_code VARCHAR(50) NOT NULL UNIQUE COMMENT '课程代码',
    course_name VARCHAR(100) NOT NULL COMMENT '课程名称',
    credits DECIMAL(3,1) COMMENT '学分',
    hours INT COMMENT '学时',
    week_hours INT DEFAULT 2 COMMENT '每周学时（默认2节）',
    classroom_type VARCHAR(50) DEFAULT '' COMMENT '教室类型（如机房/实训室/普通教室，空表示不限）',
    department VARCHAR(100) COMMENT '开课院系',
    description TEXT COMMENT '课程描述',
    status INT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_course_code (course_code),
    INDEX idx_course_name (course_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表';

CREATE TABLE classroom (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '教室ID',
    classroom_no VARCHAR(50) NOT NULL UNIQUE COMMENT '教室编号',
    building VARCHAR(50) COMMENT '楼栋',
    floor INT COMMENT '楼层',
    capacity INT COMMENT '容纳人数',
    room_type VARCHAR(30) DEFAULT '普通教室' COMMENT '教室类型: 普通教室/多媒体教室/微机室/实验室/语音室/室外场地',
    status VARCHAR(20) DEFAULT 'available' COMMENT '状态: available可用 maintenance维修中 occupied占用',
    description VARCHAR(200) COMMENT '描述',
    INDEX idx_classroom_no (classroom_no),
    INDEX idx_building (building),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教室表';

CREATE TABLE class_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '班级ID',
    class_name VARCHAR(100) NOT NULL UNIQUE COMMENT '班级名称',
    grade VARCHAR(20) COMMENT '年级',
    department VARCHAR(100) COMMENT '院系',
    teacher_id VARCHAR(50) COMMENT '管理教师学号/工号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (teacher_id) REFERENCES user(student_id),
    INDEX idx_class_name (class_name),
    INDEX idx_teacher_id (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级表';

CREATE TABLE schedule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课表ID',
    student_id VARCHAR(50) COMMENT '学号（班级课表时为空）',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    classroom_id BIGINT COMMENT '教室ID（体育课等无教室课程为 NULL）',
    teacher_name VARCHAR(50) COMMENT '教师姓名（展示用）',
    teacher_id VARCHAR(50) COMMENT '授课教师工号（权限过滤与关联用）',
    week_day INT NOT NULL COMMENT '星期几: 1-7',
    start_period INT NOT NULL COMMENT '开始节次',
    end_period INT NOT NULL COMMENT '结束节次',
    week_range VARCHAR(100) COMMENT '周次范围',
    semester VARCHAR(20) COMMENT '学期',
    class_id BIGINT COMMENT '班级ID（班级课表时填写）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (student_id) REFERENCES user(student_id),
    FOREIGN KEY (course_id) REFERENCES course(id),
    FOREIGN KEY (classroom_id) REFERENCES classroom(id),
    FOREIGN KEY (class_id) REFERENCES class_info(id),
    INDEX idx_student_id (student_id),
    INDEX idx_week_day (week_day),
    INDEX idx_class_id (class_id),
    INDEX idx_teacher_id (teacher_id),
    INDEX idx_room_day_period (classroom_id, week_day, start_period, end_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课表表';

CREATE TABLE chat_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    student_id VARCHAR(50) NOT NULL COMMENT '用户学号',
    user_message TEXT NOT NULL COMMENT '用户消息',
    bot_response TEXT COMMENT '机器人回复',
    intent_type VARCHAR(50) COMMENT '意图类型',
    mode VARCHAR(20) DEFAULT 'AI_MODE' COMMENT '对话模式（AI_MODE/LOCAL_MODE）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (student_id) REFERENCES user(student_id),
    INDEX idx_student_id (student_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话日志表';

CREATE TABLE knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识库ID',
    title VARCHAR(200) NOT NULL COMMENT '标题',
    content TEXT NOT NULL COMMENT '内容',
    category VARCHAR(50) COMMENT '分类',
    tags VARCHAR(200) COMMENT '标签',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_title (title),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表';

CREATE TABLE classroom_reservation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '预约ID',
    classroom_id BIGINT NOT NULL COMMENT '教室ID',
    student_id VARCHAR(50) NOT NULL COMMENT '预约人学号',
    student_name VARCHAR(50) COMMENT '预约人姓名',
    reason VARCHAR(200) COMMENT '预约原因',
    reservation_date DATE NOT NULL COMMENT '预约日期',
    start_period INT NOT NULL COMMENT '开始节次',
    end_period INT NOT NULL COMMENT '结束节次',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '状态: pending待审核 approved已批准 rejected已拒绝',
    active_slot VARCHAR(100) GENERATED ALWAYS AS
        (CASE WHEN status IN ('pending','approved')
              THEN CONCAT(classroom_id, '|', reservation_date, '|', start_period, '|', end_period)
              ELSE NULL END) VIRTUAL
        COMMENT '生效预约时段去重键（pending/approved 同时段唯一，released/rejected 不占用）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (classroom_id) REFERENCES classroom(id),
    FOREIGN KEY (student_id) REFERENCES user(student_id),
    UNIQUE KEY uk_active_slot (active_slot),
    INDEX idx_classroom_id (classroom_id),
    INDEX idx_student_id (student_id),
    INDEX idx_reservation_date (reservation_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教室预约表';

CREATE TABLE class_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '成员ID',
    class_id BIGINT NOT NULL COMMENT '班级ID',
    student_id VARCHAR(50) NOT NULL COMMENT '学生学号',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '状态: pending申请中 approved已加入 rejected已拒绝 withdraw_pending退班申请中 withdrawn已退班',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (class_id) REFERENCES class_info(id),
    FOREIGN KEY (student_id) REFERENCES user(student_id),
    UNIQUE KEY uk_class_student (class_id, student_id),
    INDEX idx_class_id (class_id),
    INDEX idx_student_id (student_id),
    INDEX idx_status (status),
    INDEX idx_class_status (class_id, status),
    INDEX idx_student_status (student_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级成员表';

-- 调课记录表（原 schedule 表记录一律不修改、不删除，调课仅作为展示层叠加）
CREATE TABLE schedule_adjustment (
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
    target_classroom_id BIGINT COMMENT '目标教室ID（null=沿用原教室）',
    target_week INT COMMENT '目标周次（null=仅调星期节次，不跨周）',
    orig_week_range VARCHAR(100) COMMENT '原课表条目原始周次范围快照（跨周调课撤销用）',
    orig_classroom_id BIGINT COMMENT '原教室ID快照（跨周调用撤销用）',
    target_schedule_id BIGINT COMMENT '跨周调课生效后新建的课表行ID（撤销时删除）',
    change_classroom TINYINT DEFAULT 0 COMMENT '是否换教室: 0沿用原教室 1换教室',
    ai_suggested TINYINT DEFAULT 0 COMMENT '是否来自AI推荐: 0手动 1AI推荐',
    source_type VARCHAR(20) COMMENT '来源: ai AI推荐 local AI不可用本地规则 manual 手动选择',
    status_text VARCHAR(200) COMMENT '提交时该候选的状态文字快照',
    conflict_result VARCHAR(20) COMMENT '冲突结论: none/teacher/class/room/multi',
    conflict_detail VARCHAR(1000) COMMENT '校验明细快照',
    check_time DATETIME COMMENT '最近一次校验时间',
    check_by VARCHAR(50) COMMENT '校验人',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调课记录表';

-- AI 模型提供商表（通义千问 / DeepSeek 等 OpenAI 兼容服务，由管理端维护，全局仅一条 is_default=1）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI模型提供商表';

-- 教师排课要求提交表：教师以自然语言提交个人排课约束，管理端 AI 排课时加载填入
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师排课要求提交表';

-- 学期配置表：记录当前学期名称、开始日期与持续周数，AI 排课与周次计算的基准
CREATE TABLE IF NOT EXISTS semester_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    semester_name VARCHAR(128) NOT NULL COMMENT '学期名称，如 2026-2027 学年第一学期',
    start_date DATE NOT NULL COMMENT '学期开始日期',
    weeks INT NOT NULL COMMENT '持续周数，1-40',
    end_date DATE NOT NULL COMMENT '学期结束日期（自动计算）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学期配置表';
