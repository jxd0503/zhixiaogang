package com.zhixiaogang.service.schedule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 教师排课约束文本的合规标准化：
 * 移除针对其他教师的约束条目，并为无主语条目补全当前教师姓名，保证每条约束只归属填写人本人。
 * 纯函数实现，不依赖 Spring 容器，便于管理端合并与教师端提交共用同一口径。
 */
public final class ConstraintTextNormalizer {

    private ConstraintTextNormalizer() {
    }

    /** 标准化结果：展示文本、解析文本、被移除的跨教师条目 */
    public static final class Result {
        public final String displayText;
        public final String parseText;
        public final List<String> removedSegments;

        Result(String displayText, String parseText, List<String> removedSegments) {
            this.displayText = displayText;
            this.parseText = parseText;
            this.removedSegments = removedSegments;
        }

        public boolean hasRemoved() {
            return !removedSegments.isEmpty();
        }
    }

    /**
     * 标准化约束文本。
     * displayText：给教师查看与落库的文本，已含主语的原句原样保留；
     * parseText：给约束解析使用的文本，第一人称条目会前置教师姓名以保证归属。
     */
    public static Result normalize(String teacherName, String text, Collection<String> otherTeacherNames) {
        String tn = teacherName == null ? "" : teacherName.trim();
        String raw = text == null ? "" : text.trim();
        List<String> removed = new ArrayList<>();
        if (raw.isEmpty()) {
            return new Result("", "", removed);
        }
        Set<String> others = new LinkedHashSet<>();
        if (otherTeacherNames != null) {
            for (String n : otherTeacherNames) {
                if (n != null && !n.trim().isEmpty() && !n.trim().equals(tn)) {
                    others.add(n.trim());
                }
            }
        }
        List<String> display = new ArrayList<>();
        List<String> parse = new ArrayList<>();
        for (String seg : raw.split("[\n\r;；。,，]+")) {
            String s = seg.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (mentionsOtherTeacher(s, tn, others)) {
                removed.add(s);
                continue;
            }
            if (startsWithOwnName(s, tn)) {
                // 已有本人姓名主语：展示与解析均保留原文
                display.add(s);
                parse.add(s);
            } else if (startsWithFirstPerson(s)) {
                // 第一人称主语：展示保留「我」，解析前置姓名保证多教师合并后仍归属本人
                display.add(s);
                parse.add(tn.isEmpty() ? s : tn + "：" + s);
            } else {
                // 无主语：展示与解析都补全本人姓名
                String filled = tn.isEmpty() ? s : tn + "：" + s;
                display.add(filled);
                parse.add(filled);
            }
        }
        return new Result(String.join("\n", display), String.join("\n", parse), removed);
    }

    /** 句子是否针对其他教师：出现其他教师全名，或出现「其他教师姓氏 + 老师」 */
    private static boolean mentionsOtherTeacher(String seg, String selfName, Set<String> others) {
        String s = seg.replaceAll("\\s+", "");
        for (String other : others) {
            if (s.contains(other)) {
                return true;
            }
            String surname = other.substring(0, 1);
            if (selfName.startsWith(surname)) {
                // 与本人同姓时无法区分「X老师」指向，按本人处理，避免误删合法条目
                continue;
            }
            if (s.contains(surname + "老师") || s.contains(surname + "教师")) {
                return true;
            }
        }
        return false;
    }

    /** 句子以本人姓名开头（视为已有本人主语） */
    private static boolean startsWithOwnName(String seg, String selfName) {
        if (selfName.isEmpty()) {
            return false;
        }
        return seg.replaceAll("\\s+", "").startsWith(selfName);
    }

    /** 句子以第一人称代词开头 */
    private static boolean startsWithFirstPerson(String seg) {
        String s = seg.replaceAll("\\s+", "");
        return s.startsWith("我") || s.startsWith("本人");
    }
}
