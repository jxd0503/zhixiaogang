package com.zhixiaogang.service.schedule;

import java.util.List;
import java.util.Map;
import com.zhixiaogang.service.schedule.AiScheduleModels.*;

/**
 * AI 排课单次请求上下文：承载排课各阶段之间传递的基础数据、约束集合与分配结果。
 * 每次 generate 新建实例，避免把请求级状态放到单例 {@code @Service} 字段上。
 */
final class AiScheduleContext {

    /** 原始请求参数 */
    final Map<String, Object> request;

    List<Map<String, Object>> classes;
    List<Map<String, Object>> courses;
    int weekStart;
    int defaultWeekEnd;
    int weekEnd;
    String globalWeekRange;
    int minPeriod;
    int maxPeriod;
    boolean allowWeekend;
    List<Integer> days;
    List<int[]> segments;
    List<Map<String, Object>> existingRows;
    Map<String, List<Slot>> existTeacher;
    Map<Long, List<Slot>> existClass;
    Map<Long, List<Slot>> existRoom;
    Map<String, List<Slot>> workTeacher;
    Map<Long, List<Slot>> workClass;
    Map<Long, List<Slot>> workRoom;
    List<Map<String, Object>> rooms;
    java.util.Set<String> workloadTeachers;
    java.util.Set<String> teacherNames;
    List<ConstraintEntry> constraintEntries;
    List<String> constraintUnrecognized;
    List<String> constraintWarnings;
    Object structuredRaw;
    boolean hasStructured;
    java.util.Set<Integer> excludedIds;
    Object excludedRaw;
    List<ConstraintEntry> activeEntries;
    List<Constraint> constraints;
    List<Map<String, Object>> constraintList;
    Map<String, Long> classIdByName;
    Map<String, Long> roomIdByNo;
    Map<Long, Map<String, Object>> roomById;
    Map<Long, Integer> classSizeById;
    Map<Long, String> classBuilding;
    Map<Long, Map<String, Integer>> buildingCounts;
    List<Constraint> weekConstraints;
    List<Constraint> slotConstraints;
    List<String> unmatchedNotes;
    List<Constraint> unmatchedConstraints;
    java.util.Set<String> unmatchedSet;
    Map<String, String> courseWeekAdjust;
    java.util.Set<String> parityBlocked;
    List<String> constraintSummary;
    Map<Long, Integer> classDayPeriodCap;
    String semester;
    List<Map<String, Object>> items;
    Map<Long, Map<Integer, Integer>> classDayLoad;
    Map<Long, Map<Integer, Integer>> classDayPeriods;
    java.util.Set<String> courseDayKeys;
    Map<String, Long> courseRoomPrefer;
    List<String> scheduleFailures;

    AiScheduleContext(Map<String, Object> request) {
        this.request = request;
    }
}
