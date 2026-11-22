package com.zhixiaogang.service.schedule;

import java.util.regex.Pattern;

/**
 * AI 排课共享常量：展示文案、约束主语标记、星期 / 教师称谓 / 禁排词等词表。
 */
final class AiScheduleConstants {

    static final String NO_CLASSROOM_DISPLAY = "室外（体育课）";

    /** 无明确主语的约束默认归属当前正在排课的目标班级 */
    static final String TARGET_CLASS_NAME = "__target_class__";

    /** 教师简称命中多名同姓教师时的标注后缀（不静默丢弃，交由用户指定全名） */
    static final String TEACHER_AMBIGUOUS_SUFFIX = "（不明确，请指定全名）";

    static final String[] WEEKDAY_CN = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 教师称谓后缀：「X 老师 / X 教师 / X 教授 / X 同志」统一按姓氏检索教师名单 */
    static final String[] TEACHER_TITLES = {"老师", "教师", "教授", "同志"};

    /** 课堂口语代词：一律归一为目标班级（当前正在排课的班级） */
    static final String[] TARGET_CLASS_ALIASES = {
            "目标班级", "当前班级", "班级", "目标班", "当前排课班级",
            "本班", "本班级", "该班", "该班级", "这个班", "这个班级",
            "我们班", "我们班级", "我们", "咱们班", "咱们", "咱班", "咱"
    };

    /** 弱约束词：识别到该表述时按硬约束处理，并在提示中说明 */
    static final String[] WEAK_CONSTRAINT_WORDS = {"尽量", "最好", "优先", "尽可能", "希望"};

    /** 禁用语义关键词表 */
    static final String[] FORBID_WORDS = {"不能", "不可", "禁止", "不要", "不得", "不予",
            "别排", "不排", "别在", "别安排", "请避开", "避开"};

    /** 目标文本清洗时剔除的填充词（非目标名组成部分） */
    static final String[] FILLER_WORDS = {"排课", "上课", "授课", "安排", "使用", "同学",
            "时间", "都要", "都", "均", "也", "请", "的", "了", "在", "能", "可以", "排", "只", "仅", "限定"};

    /** 节次/时段词（清洗目标文本时剔除） */
    static final String[] PERIOD_WORDS = {"上午", "下午", "早上", "早晨", "中午",
            "晚间", "晚上", "夜晚", "晚自习", "晚补", "全天"};

    /** 星期词正则：周X / 星期X / 礼拜X，仅属于时间维度 */
    static final Pattern DAY_WORD = Pattern.compile("(?:周|星期|礼拜)[一二三四五六日天]");

    private AiScheduleConstants() {
    }
}
