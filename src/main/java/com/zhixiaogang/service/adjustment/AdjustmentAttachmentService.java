package com.zhixiaogang.service.adjustment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 调课凭证附件：教师上传（先上传拿 id，提交调课时绑定）、管理员预览/下载。
 * 文件落盘到项目根目录 uploads/adjustments/（已加入 .gitignore，不入版本库）。
 */
@Service
public class AdjustmentAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentAttachmentService.class);

    /** 单个附件上限 5MB */
    private static final long MAX_SIZE = 5L * 1024 * 1024;
    /** 单次调课最多 3 个附件 */
    public static final int MAX_COUNT = 3;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf");
    private static final Path ROOT = Paths.get("uploads", "adjustments").toAbsolutePath().normalize();

    /** 附件落盘根目录（清理任务复用，避免两处路径不一致） */
    public static Path storageRoot() {
        return ROOT;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 上传单个附件，返回附件 id（此时尚未绑定调课记录） */
    public Map<String, Object> upload(MultipartFile file, String uploaderId) {
        Map<String, Object> result = new HashMap<>();
        if (file == null || file.isEmpty()) {
            result.put("success", false);
            result.put("message", "请选择要上传的附件");
            return result;
        }
        if (file.getSize() > MAX_SIZE) {
            result.put("success", false);
            result.put("message", "单个附件不能超过 5MB");
            return result;
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = original.toLowerCase();
        boolean nameOk = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".pdf");
        if (!ALLOWED_TYPES.contains(contentType) || !nameOk) {
            result.put("success", false);
            result.put("message", "仅支持图片（jpg/png/gif/webp）或 PDF 附件");
            return result;
        }
        try {
            String ext = lower.substring(lower.lastIndexOf('.'));
            String subDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
            Path dir = ROOT.resolve(subDir);
            Files.createDirectories(dir);
            String storedName = subDir + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
            Path target = ROOT.resolve(storedName);
            file.transferTo(target.toFile());

            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schedule_adjustment_attachment " +
                                "(adjustment_id, uploader_id, original_name, stored_name, content_type, file_size, upload_time) " +
                                "VALUES (NULL, ?, ?, ?, ?, ?, NOW())",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, uploaderId);
                ps.setString(2, original);
                ps.setString(3, storedName);
                ps.setString(4, contentType);
                ps.setLong(5, file.getSize());
                return ps;
            }, keyHolder);
            Number id = keyHolder.getKey();
            result.put("success", true);
            result.put("id", id == null ? null : id.longValue());
            result.put("name", original);
            result.put("size", file.getSize());
            result.put("contentType", contentType);
            result.put("message", "上传成功");
        } catch (Exception e) {
            log.warn("调课附件上传失败", e);
            result.put("success", false);
            result.put("message", "附件上传失败，请稍后重试");
        }
        return result;
    }

    /**
     * 提交前校验附件：数量不超过 3，且均为本人上传、尚未绑定。
     * @return 错误信息；null 表示校验通过
     */
    public String validate(String uploaderId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (ids.size() > MAX_COUNT) {
            return "最多上传 " + MAX_COUNT + " 个附件";
        }
        for (Long id : distinctIds(ids)) {
            Map<String, Object> row = findRow(id);
            if (row == null) {
                return "附件不存在或已失效";
            }
            String owner = row.get("uploader_id") == null ? "" : row.get("uploader_id").toString();
            if (!owner.equals(uploaderId == null ? "" : uploaderId)) {
                return "无权使用该附件";
            }
            if (row.get("adjustment_id") != null) {
                return "附件已绑定其他申请，请重新上传";
            }
        }
        return null;
    }

    /**
     * 提交后绑定附件：首个调课记录复用原附件行，其余记录插入副本（共用同一磁盘文件），
     * 保证批量调课时每条申请都能看到凭证。
     */
    public void bindAll(List<Long> adjustmentIds, String uploaderId, List<Long> ids) {
        if (adjustmentIds == null || adjustmentIds.isEmpty() || ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> distinct = distinctIds(ids);
        Long primary = adjustmentIds.get(0);
        for (Long id : distinct) {
            jdbcTemplate.update("UPDATE schedule_adjustment_attachment SET adjustment_id = ? WHERE id = ? AND adjustment_id IS NULL",
                    primary, id);
        }
        for (int i = 1; i < adjustmentIds.size(); i++) {
            for (Long id : distinct) {
                jdbcTemplate.update(
                        "INSERT INTO schedule_adjustment_attachment " +
                                "(adjustment_id, uploader_id, original_name, stored_name, content_type, file_size, upload_time) " +
                                "SELECT ?, uploader_id, original_name, stored_name, content_type, file_size, upload_time " +
                                "FROM schedule_adjustment_attachment WHERE id = ?",
                        adjustmentIds.get(i), id);
            }
        }
    }

    private List<Long> distinctIds(List<Long> ids) {
        List<Long> distinct = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && !distinct.contains(id)) {
                distinct.add(id);
            }
        }
        return distinct;
    }

    private Map<String, Object> findRow(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, uploader_id, adjustment_id FROM schedule_adjustment_attachment WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 调课记录附件列表（管理端展示用） */
    public List<Map<String, Object>> listByAdjustment(Long adjustmentId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT id, original_name, content_type, file_size, upload_time " +
                            "FROM schedule_adjustment_attachment WHERE adjustment_id = ? ORDER BY id",
                    adjustmentId)) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", r.get("id"));
                m.put("name", r.get("original_name"));
                m.put("contentType", r.get("content_type"));
                m.put("size", r.get("file_size"));
                m.put("uploadTime", r.get("upload_time"));
                m.put("previewUrl", "/admin/adjustments/attachments/" + r.get("id") + "/file");
                list.add(m);
            }
        } catch (Exception e) {
            log.warn("加载调课附件失败", e);
        }
        return list;
    }

    /** 读取附件元数据与磁盘文件（下载/预览用） */
    public Map<String, Object> load(Long attachmentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, original_name, stored_name, content_type FROM schedule_adjustment_attachment WHERE id = ?",
                attachmentId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Path path = ROOT.resolve(String.valueOf(row.get("stored_name"))).normalize();
        if (!path.startsWith(ROOT) || !Files.exists(path)) {
            return null;
        }
        Map<String, Object> m = new HashMap<>(row);
        m.put("path", path);
        return m;
    }
}
