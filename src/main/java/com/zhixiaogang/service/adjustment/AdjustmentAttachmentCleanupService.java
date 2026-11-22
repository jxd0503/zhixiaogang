package com.zhixiaogang.service.adjustment;

import com.zhixiaogang.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 调课凭证附件清理：按磁盘文件引用计数回收孤儿文件。
 * <p>
 * 批量调课时多条申请共用同一磁盘文件（stored_name 相同），因此判定维度是「文件」而不是「附件行」：
 * 只要仍有一行引用该 stored_name 就不删除文件，避免误删其它申请仍在使用的凭证。
 * <ul>
 *   <li>未绑定附件（adjustment_id 为空）超过 24 小时视为放弃上传，删除附件行；</li>
 *   <li>已拒绝 / 已撤销申请关闭超过保留天数（默认 90 天）后删除附件行；</li>
 *   <li>磁盘上引用数为 0 且最后修改时间超过 24 小时的文件删除（宽限期保护刚上传未绑定记录的文件）。</li>
 * </ul>
 */
@Service
public class AdjustmentAttachmentCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentAttachmentCleanupService.class);

    /** 未绑定附件与被引用文件的宽限期（小时），避免误删上传/提交过程中的文件 */
    private static final int GRACE_HOURS = 24;
    /** 已关闭申请附件的默认保留天数，可由 system_config 覆盖 */
    private static final int DEFAULT_RETENTION_DAYS = 90;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SystemConfigService systemConfigService;

    /** 每日清理（cron 可通过配置覆盖，便于冒烟验证） */
    @Scheduled(cron = "${adjustment.attachment-cleanup-cron:0 30 3 * * ?}")
    public void scheduledCleanup() {
        try {
            Map<String, Object> result = cleanup();
            log.info("调课附件清理完成：清理附件行 {} 条、孤儿文件 {} 个、释放 {} 字节",
                    result.get("rowsDeleted"), result.get("filesDeleted"), result.get("bytesFreed"));
        } catch (Exception e) {
            log.warn("调课附件清理任务执行失败（不影响业务）", e);
        }
    }

    /**
     * 执行一次清理，返回清理统计（供定时任务与管理员手动入口共用）。
     */
    public Map<String, Object> cleanup() {
        Map<String, Object> result = new LinkedHashMap<>();
        int unboundRows = jdbcTemplate.update(
                "DELETE FROM schedule_adjustment_attachment " +
                        "WHERE adjustment_id IS NULL AND upload_time <= TIMESTAMPADD(HOUR, ?, NOW())",
                -GRACE_HOURS);

        int retentionDays = Math.max(1,
                systemConfigService.getInt("adjustment.attachment_retention_days", DEFAULT_RETENTION_DAYS));
        int closedRows = jdbcTemplate.update(
                "DELETE a FROM schedule_adjustment_attachment a " +
                        "JOIN schedule_adjustment s ON s.id = a.adjustment_id " +
                        "WHERE s.status IN ('rejected', 'cancelled') " +
                        "AND COALESCE(s.approve_time, s.create_time) <= TIMESTAMPADD(DAY, ?, NOW())",
                -retentionDays);

        Set<String> referenced = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT stored_name FROM schedule_adjustment_attachment WHERE stored_name IS NOT NULL",
                String.class));
        long[] fileStats = deleteOrphanFiles(AdjustmentAttachmentService.storageRoot(), referenced,
                Instant.now().minus(GRACE_HOURS, ChronoUnit.HOURS));

        result.put("success", true);
        result.put("rowsDeleted", unboundRows + closedRows);
        result.put("unboundRowsDeleted", unboundRows);
        result.put("closedRowsDeleted", closedRows);
        result.put("filesDeleted", fileStats[0]);
        result.put("bytesFreed", fileStats[1]);
        result.put("referencedFiles", referenced.size());
        result.put("retentionDays", retentionDays);
        result.put("message", "清理完成：删除附件行 " + (unboundRows + closedRows)
                + " 条、孤儿文件 " + fileStats[0] + " 个，释放 " + fileStats[1] + " 字节");
        return result;
    }

    /**
     * 删除引用计数为 0 且早于 cutoff 的文件。只处理根目录下的普通文件，跳过无法解析的路径。
     *
     * @return [删除文件数, 释放字节数]
     */
    static long[] deleteOrphanFiles(Path root, Set<String> referenced, Instant cutoff) {
        long files = 0;
        long bytes = 0L;
        if (root == null || !Files.isDirectory(root)) {
            return new long[]{0, 0};
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> candidates = stream.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (referenced.contains(relative)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
                        continue;
                    }
                    long size = Files.size(file);
                    if (Files.deleteIfExists(file)) {
                        files++;
                        bytes += size;
                    }
                } catch (IOException e) {
                    log.warn("删除孤儿附件失败: {}", relative);
                }
            }
        } catch (IOException e) {
            log.warn("扫描附件目录失败: {}", root, e);
        }
        return new long[]{files, bytes};
    }
}
