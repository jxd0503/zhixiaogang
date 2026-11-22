package com.zhixiaogang.service.adjustment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调课附件清理单测：以 stored_name 引用计数判定，批量调课共用文件不被误删；宽限期内新文件保留。
 */
class AdjustmentAttachmentCleanupServiceTest {

    @TempDir
    Path root;

    @Test
    void keepsReferencedFileAndDeletesOnlyOrphans() throws IOException {
        Path shared = write("202611/shared.pdf", "shared");
        Path orphan = write("202611/orphan.pdf", "orphan");
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        Files.setLastModifiedTime(shared, FileTime.from(old));
        Files.setLastModifiedTime(orphan, FileTime.from(old));

        long[] stats = AdjustmentAttachmentCleanupService.deleteOrphanFiles(
                root, Set.of("202611/shared.pdf"), Instant.now().minus(24, ChronoUnit.HOURS));

        assertTrue(Files.exists(shared), "仍被申请引用的文件不能删除（批量共用场景）");
        assertFalse(Files.exists(orphan));
        assertArrayEquals(new long[]{1, "orphan".getBytes(StandardCharsets.UTF_8).length}, stats);
    }

    @Test
    void keepsFreshUnreferencedFileWithinGracePeriod() throws IOException {
        Path fresh = write("202611/fresh.pdf", "fresh");

        long[] stats = AdjustmentAttachmentCleanupService.deleteOrphanFiles(
                root, Set.of(), Instant.now().minus(24, ChronoUnit.HOURS));

        assertTrue(Files.exists(fresh), "宽限期内刚上传的文件不能被清理");
        assertArrayEquals(new long[]{0, 0}, stats);
    }

    @Test
    void missingRootIsNoOp() {
        long[] stats = AdjustmentAttachmentCleanupService.deleteOrphanFiles(
                root.resolve("absent"), Set.of(), Instant.now());

        assertArrayEquals(new long[]{0, 0}, stats);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
