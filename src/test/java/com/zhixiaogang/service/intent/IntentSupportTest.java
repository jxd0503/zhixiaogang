package com.zhixiaogang.service.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 节次口径单测：实际节次（1-10）与节次段（1-5）互相换算，以及口令中的节次提取。
 */
class IntentSupportTest {

    @Test
    void actualPeriodToSegmentMapping() {
        assertEquals(1, IntentSupport.actualPeriodToSegment(1).intValue());
        assertEquals(1, IntentSupport.actualPeriodToSegment(2).intValue());
        assertEquals(2, IntentSupport.actualPeriodToSegment(3).intValue());
        assertEquals(2, IntentSupport.actualPeriodToSegment(4).intValue());
        assertEquals(3, IntentSupport.actualPeriodToSegment(5).intValue());
        assertEquals(3, IntentSupport.actualPeriodToSegment(6).intValue());
        assertEquals(4, IntentSupport.actualPeriodToSegment(7).intValue());
        assertEquals(4, IntentSupport.actualPeriodToSegment(8).intValue());
        assertEquals(5, IntentSupport.actualPeriodToSegment(9).intValue());
        assertEquals(5, IntentSupport.actualPeriodToSegment(10).intValue());
    }

    @Test
    void extractPeriodTreatsSmallNumberAsSegment() {
        assertEquals(1, IntentSupport.extractPeriod("第1节").intValue());
        assertEquals(5, IntentSupport.extractPeriod("第5节").intValue());
    }

    @Test
    void extractPeriodConvertsLargeActualPeriod() {
        assertEquals(4, IntentSupport.extractPeriod("第7节").intValue());
        assertEquals(5, IntentSupport.extractPeriod("第10节").intValue());
    }

    @Test
    void extractPeriodRangeConvertsBothEnds() {
        assertArrayEquals(new int[]{2, 3}, IntentSupport.extractPeriodRange("第3-6节"));
        assertArrayEquals(new int[]{2, 3}, IntentSupport.extractPeriodRange("3到6节"));
        assertArrayEquals(new int[]{1, 2}, IntentSupport.extractPeriodRange("第2-4节"));
    }

    @Test
    void extractPeriodRangeReturnsNullWhenAbsent() {
        assertNull(IntentSupport.extractPeriodRange("今天有什么课"));
    }
}
