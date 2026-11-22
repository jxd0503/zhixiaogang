package com.zhixiaogang.service.adjustment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 调课凭证附件单测：上传数量/类型/大小限制，提交时的归属与绑定校验。
 */
@ExtendWith(MockitoExtension.class)
class AdjustmentAttachmentServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AdjustmentAttachmentService service;

    @Test
    void uploadRejectsOversizeFile() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", big);

        Map<String, Object> result = service.upload(file, "T001");

        assertEquals(false, result.get("success"));
        assertEquals("单个附件不能超过 5MB", result.get("message"));
    }

    @Test
    void uploadRejectsUnsupportedType() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());

        Map<String, Object> result = service.upload(file, "T001");

        assertEquals(false, result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("仅支持图片"));
    }

    @Test
    void validateRejectsTooManyAttachments() {
        String error = service.validate("T001", List.of(1L, 2L, 3L, 4L));

        assertNotNull(error);
        assertTrue(error.contains("最多上传 3 个附件"));
    }

    @Test
    void validateRejectsAttachmentOfAnotherTeacher() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(attachmentRow(1L, "T002", null)));

        String error = service.validate("T001", List.of(1L));

        assertEquals("无权使用该附件", error);
    }

    @Test
    void validateRejectsAlreadyBoundAttachment() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(attachmentRow(1L, "T001", 99L)));

        String error = service.validate("T001", List.of(1L));

        assertEquals("附件已绑定其他申请，请重新上传", error);
    }

    @Test
    void validatePassesForOwnUnboundAttachment() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(attachmentRow(1L, "T001", null)));

        assertNull(service.validate("T001", List.of(1L)));
    }

    private Map<String, Object> attachmentRow(Long id, String uploaderId, Long adjustmentId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("uploader_id", uploaderId);
        row.put("adjustment_id", adjustmentId);
        return row;
    }
}
