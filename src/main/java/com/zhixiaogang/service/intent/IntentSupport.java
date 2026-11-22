package com.zhixiaogang.service.intent;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/********** 意图识别共享工具：正则/词表、周次与节次解析、节次与星期文案映射（静态方法，无状态） **********/
final class IntentSupport {

    private IntentSupport() {
    }

    static final Pattern WEEK_DAY_PATTERN = Pattern.compile("(周一|周二|周三|周四|周五|周六|周日|星期[一二三四五六日])");
    static final Pattern BUILDING_PATTERN = Pattern.compile("([A-Za-z]\\d+)");
    static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})");

    /** 咨询类问法关键词：出现时表示用户在"问流程/规则"而非"下达指令"（P9） */
    static final String[] CONSULT_WORDS = {"怎么", "如何", "提前", "流程", "手续", "多久", "要求", "条件"};

    /** 校园领域词表：用于关键词抽取时优先命中（P12） */
    static final String[] DOMAIN_WORDS = {"图书馆", "校园卡", "一卡通", "食堂", "宿舍", "校园网", "快递",
            "体育场", "操场", "球馆", "心理", "社团", "学费", "奖学金", "助学金", "选课", "教务", "成绩",
            "失物", "安全", "自习室", "门禁", "充值", "补办", "预约", "课表", "空教室"};

    static Integer extractWeek(String message) {
        Pattern weekPattern = Pattern.compile("第(\\d+)周");
        Matcher matcher = weekPattern.matcher(message);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static String getDayName(Integer day) {
        if (day == null) return "";
        switch (day) {
            case 1: return "周一";
            case 2: return "周二";
            case 3: return "周三";
            case 4: return "周四";
            case 5: return "周五";
            case 6: return "周六";
            case 7: return "周日";
            default: return "";
        }
    }

    static String getPeriodName(Integer period) {
        if (period == null) return "";
        switch (period) {
            case 1: return "1-2节 (8:30-10:10)";
            case 2: return "3-4节 (10:25-12:00)";
            case 3: return "5-6节 (14:00-15:40)";
            case 4: return "7-8节 (15:55-17:30)";
            case 5: return "9-10节 (18:30-20:15)";
            default: return "第" + period + "节";
        }
    }

    static Integer extractWeekDay(String message) {
        Matcher matcher = WEEK_DAY_PATTERN.matcher(message);
        if (matcher.find()) {
            String day = matcher.group();
            switch (day) {
                case "周一": case "星期一": return 1;
                case "周二": case "星期二": return 2;
                case "周三": case "星期三": return 3;
                case "周四": case "星期四": return 4;
                case "周五": case "星期五": return 5;
                case "周六": case "星期六": return 6;
                case "周日": case "星期日": return 7;
                default: return null;
            }
        }
        return null;
    }

    static String extractBuilding(String message) {
        Matcher matcher = BUILDING_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group().toUpperCase();
        }
        return null;
    }

    static String extractClassroomNo(String message) {
        Matcher matcher = Pattern.compile("([A-Za-z]\\d+-\\d+)").matcher(message);
        if (matcher.find()) {
            return matcher.group().toUpperCase();
        }
        return null;
    }

    /** P7 节次提取：支持 "第X节"、"X点/X点半"、原 PERIOD_PATTERN 兜底 */
    static Integer extractPeriod(String message) {
        // 1. "第X节" → 直接映射为节次段（1=1-2节, 2=3-4节, 3=5-6节, 4=7-8节, 5=9-10节）
        Matcher segMatcher = Pattern.compile("第(\\d+)节").matcher(message);
        if (segMatcher.find()) {
            try {
                int seg = Integer.parseInt(segMatcher.group(1));
                if (seg >= 1 && seg <= 5) return seg;
                if (seg > 5 && seg <= 10) return actualPeriodToSegment(seg); // 兼容实际节次说法
            } catch (NumberFormatException e) { /* ignore */ }
        }

        // 2. "X点" / "X点半"，结合上午/下午/晚上
        Matcher hourMatcher = Pattern.compile("(上午|下午|晚上)?\\s*(\\d+)点(半)?").matcher(message);
        if (hourMatcher.find()) {
            try {
                String prefix = hourMatcher.group(1);
                int hour = Integer.parseInt(hourMatcher.group(2));
                boolean half = hourMatcher.group(3) != null;

                if (prefix != null) {
                    if (prefix.contains("下午") || prefix.contains("晚上")) {
                        if (hour < 12) hour += 12;
                    }
                    // 上午不变
                } else {
                    // 无前缀：1-6点默认下午/晚上
                    if (hour >= 1 && hour <= 6) hour += 12;
                }
                int minutes = hour * 60 + (half ? 30 : 0);
                if (minutes < 625) return 1;   // 10:25
                if (minutes < 720) return 2;   // 12:00
                if (minutes < 940) return 3;   // 15:40
                if (minutes < 1050) return 4;  // 17:30
                return 5;
            } catch (NumberFormatException e) { /* ignore */ }
        }

        // 3. 原 PERIOD_PATTERN 兜底（纯数字）
        Matcher oldMatcher = Pattern.compile("(\\d+)点|第(\\d+)节").matcher(message);
        if (oldMatcher.find()) {
            try {
                String group = oldMatcher.group(1);
                if (group == null) group = oldMatcher.group(2);
                if (group != null) {
                    int val = Integer.parseInt(group);
                    if (message.contains("节") && val <= 10) {
                        return actualPeriodToSegment(val);
                    }
                    return val;
                }
            } catch (NumberFormatException e) { /* ignore */ }
        }
        return null;
    }

    /** 节次范围提取："第3-4节"/"3-4节"/"第3到4节" → {起始节次段, 结束节次段}（按实际节次换算）；无法识别返回 null */
    static int[] extractPeriodRange(String message) {
        Matcher matcher = Pattern.compile("第?\\s*(\\d{1,2})\\s*[-~～到至]\\s*(\\d{1,2})\\s*节").matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            int start = Integer.parseInt(matcher.group(1));
            int end = Integer.parseInt(matcher.group(2));
            if (start > end) {
                int swap = start;
                start = end;
                end = swap;
            }
            return new int[]{actualPeriodToSegment(start), actualPeriodToSegment(end)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer actualPeriodToSegment(int actualPeriod) {
        if (actualPeriod >= 1 && actualPeriod <= 2) return 1;
        if (actualPeriod >= 3 && actualPeriod <= 4) return 2;
        if (actualPeriod >= 5 && actualPeriod <= 6) return 3;
        if (actualPeriod >= 7 && actualPeriod <= 8) return 4;
        return 5;
    }

    static LocalDate extractDate(String message) {
        Matcher matcher = DATE_PATTERN.matcher(message);
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception e) {
                try {
                    return LocalDate.parse(matcher.group(), DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                } catch (Exception ex) {
                    // Ignore
                }
            }
        }
        return null;
    }

    /** P3+P12 关键词抽取：停用词移除常见虚词，保留校园领域词 */
    static String extractKeyword(String message) {
        String[] stopWords = {"我", "你", "他", "她", "它", "的", "了", "是", "在", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去", "会", "着", "没有", "看", "好", "自己", "这", "请问", "查询", "搜索", "怎么", "如何", "什么", "哪", "谁", "几", "多少", "能", "可以", "吗", "呢", "吧", "啊", "课表", "教室"};

        // P12 优先保留校园领域词
        for (String dw : DOMAIN_WORDS) {
            if (message.contains(dw)) {
                return dw;
            }
        }

        StringBuilder keyword = new StringBuilder();
        for (String word : message.split("[\\s，。！？、；：]+")) {
            boolean isStopWord = false;
            for (String stopWord : stopWords) {
                if (word.equals(stopWord)) {
                    isStopWord = true;
                    break;
                }
            }
            if (!isStopWord && word.length() > 1) {
                keyword.append(word).append(" ");
            }
        }
        return keyword.toString().trim();
    }
}
