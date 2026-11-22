package com.zhixiaogang.service;

import com.zhixiaogang.entity.OperationLog;
import com.zhixiaogang.entity.User;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 操作日志统一入口：三端所有埋点只调用本类 record 系列方法。
 *
 * 设计要点：
 * 1) 异步：日志入内存队列，由后台守护线程批量落库，不阻塞业务请求；
 * 2) 降级：队列积压（满）时直接丢弃，保护业务优先；
 * 3) 防抖：同一用户同一操作 1 秒内重复提交只记一条；
 * 4) 空间控制：detail 截断 100 字，库表按保留天数每晚清理，文件日志按天滚动 + 20MB 分卷 + 定时清理；
 * 5) 不存大字段：只存操作摘要，不存请求/响应全文、不存图片与 Excel 内容。
 */
@Service
public class OperationLogService {

    /** 拦截器写入的请求开始时间属性名 */
    public static final String REQ_ATTR_START = "opLogStartTime";

    private static final int QUEUE_CAPACITY = 5000;
    private static final int BATCH_SIZE = 200;
    private static final int DETAIL_MAX = 100;
    private static final long DEBOUNCE_MS = 1000L;
    private static final long FILE_MAX_BYTES = 20L * 1024 * 1024;

    private final BlockingQueue<OperationLog> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** key -> 上次记录时间戳，用于 1 秒内重复提交去重 */
    private final Map<String, Long> debounceMap = new ConcurrentHashMap<>();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong writtenCount = new AtomicLong();

    private volatile boolean running = true;
    private Thread writerThread;

    /** 文件日志状态 */
    private String fileDate;
    private int fileSeq;
    private long fileBytes;
    private FileOutputStream fileOut;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SystemConfigService systemConfigService;

    private final Path logDir = Paths.get("logs");
    private final Path archiveDir = Paths.get("logs", "archive");

    @PostConstruct
    public void start() {
        cleanupExpiredFiles();
        writerThread = new Thread(this::consumeLoop, "operation-log-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (writerThread != null) {
            try {
                writerThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemaining();
        closeFile();
    }

    // ==================== 对外记录入口 ====================

    /**
     * 记录一条操作日志（成功/失败）。
     */
    public void record(String action, String targetType, Object targetId, String detail, boolean success) {
        record(action, targetType, targetId, detail, success ? "success" : "failed", null, null);
    }

    /**
     * 记录一条操作日志，失败时带上简短原因（自动截断到 100 字以内）。
     */
    public void record(String action, String targetType, Object targetId, String detail,
                       boolean success, String failReason) {
        String result = success ? "success" : ("failed: " + (failReason == null ? "" : failReason));
        record(action, targetType, targetId, detail, result, null, null);
    }

    /**
     * 记录一条操作日志，result 直接指定（如 "success" / "failed: 密码错误"）。
     */
    public void record(String action, String targetType, Object targetId, String detail, String result) {
        record(action, targetType, targetId, detail, result, null, null);
    }

    /**
     * 记录未登录场景（如登录失败）的日志：显式指定账号与角色。
     */
    public void recordAnonymous(String action, String username, String role, String targetType,
                                Object targetId, String detail, String result) {
        record(action, targetType, targetId, detail, result, username, role);
    }

    // ==================== 内部实现 ====================

    private void record(String action, String targetType, Object targetId, String detail,
                        String result, String overrideUsername, String overrideRole) {
        try {
            HttpServletRequest request = currentRequest();
            User user = currentUser(request);

            OperationLog log = new OperationLog();
            if (user != null) {
                log.setUserId(user.getId());
                // 账号（姓名），便于管理端一眼识别操作人
                String account = user.getStudentId();
                String displayName = user.getName();
                log.setUsername(trim(displayName == null || displayName.isBlank()
                        ? account : account + "（" + displayName + "）", 50));
                log.setRole(user.getRole());
            } else {
                log.setUsername(overrideUsername);
                log.setRole(overrideRole);
            }
            log.setAction(trim(action, 30));
            log.setTargetType(trim(targetType, 40));
            log.setTargetId(targetId == null ? null : trim(String.valueOf(targetId), 64));
            log.setDetail(trim(detail, DETAIL_MAX));
            log.setResult(trim(result, 255));
            log.setIp(clientIp(request));
            log.setCostMs(costMs(request));
            log.setCreateTime(LocalDateTime.now());

            // 1 秒内同用户同操作只记一条
            String key = (log.getUserId() != null ? log.getUserId() : "ip:" + log.getIp())
                    + "|" + log.getAction() + "|" + log.getTargetType() + "|" + log.getTargetId();
            long now = System.currentTimeMillis();
            Long last = debounceMap.put(key, now);
            if (last != null && now - last < DEBOUNCE_MS) {
                return;
            }
            if (debounceMap.size() > 2000) {
                debounceMap.entrySet().removeIf(e -> now - e.getValue() > DEBOUNCE_MS * 5);
            }

            if (!queue.offer(log)) {
                long dropped = droppedCount.incrementAndGet();
                if (dropped % 1000 == 1) {
                    System.err.println("操作日志队列积压，已降级丢弃 " + dropped + " 条");
                }
            }
        } catch (Exception ignored) {
            // 日志失败不影响业务
        }
    }

    private void consumeLoop() {
        List<OperationLog> buffer = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                OperationLog first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                buffer.clear();
                buffer.add(first);
                queue.drainTo(buffer, BATCH_SIZE - 1);
                persist(buffer);
                writeFiles(buffer);
                buffer.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("操作日志写入失败：" + e.getMessage());
            }
        }
    }

    private void persist(List<OperationLog> logs) {
        if (logs.isEmpty()) {
            return;
        }
        try {
            List<Object[]> args = new ArrayList<>(logs.size());
            for (OperationLog log : logs) {
                args.add(new Object[]{
                        log.getUserId(), log.getUsername(), log.getRole(), log.getAction(),
                        log.getTargetType(), log.getTargetId(), log.getDetail(), log.getResult(),
                        log.getIp(), log.getCostMs(),
                        Timestamp.valueOf(log.getCreateTime() == null ? LocalDateTime.now() : log.getCreateTime())
                });
            }
            jdbcTemplate.batchUpdate(
                    "INSERT INTO operation_log (user_id, username, role, action, target_type, target_id, detail, result, ip, cost_ms, create_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", args);
            writtenCount.addAndGet(logs.size());
        } catch (Exception e) {
            System.err.println("操作日志落库失败：" + e.getMessage());
        }
    }

    private void flushRemaining() {
        List<OperationLog> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            persist(rest);
            writeFiles(rest);
        }
    }

    // ==================== 文件日志（可选通道） ====================

    private void writeFiles(List<OperationLog> logs) {
        try {
            if (!systemConfigService.getBoolean("operation_log.file_enabled", true)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (OperationLog log : logs) {
                sb.append(toJsonLine(log)).append('\n');
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            ensureFile((int) bytes.length);
            if (fileOut != null) {
                fileOut.write(bytes);
                fileOut.flush();
                fileBytes += bytes.length;
            }
        } catch (Exception e) {
            System.err.println("操作日志文件写入失败：" + e.getMessage());
        }
    }

    private void ensureFile(int upcomingBytes) throws Exception {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!today.equals(fileDate)) {
            closeFile();
            fileDate = today;
            fileSeq = 0;
            fileBytes = 0;
            openFile();
            return;
        }
        if (fileOut == null) {
            openFile();
            return;
        }
        if (fileBytes + upcomingBytes > FILE_MAX_BYTES) {
            closeFile();
            fileSeq++;
            fileBytes = 0;
            openFile();
        }
    }

    private void openFile() throws Exception {
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }
        File file = new File(logDir.toFile(), fileName());
        fileOut = new FileOutputStream(file, true);
        fileBytes = file.length();
    }

    private String fileName() {
        return fileSeq == 0 ? "ops-" + fileDate + ".log" : "ops-" + fileDate + "-" + fileSeq + ".log";
    }

    private void closeFile() {
        if (fileOut != null) {
            try {
                fileOut.close();
            } catch (Exception ignored) {
            }
            fileOut = null;
        }
    }

    private String toJsonLine(OperationLog log) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJson(sb, "user_id", log.getUserId() == null ? null : String.valueOf(log.getUserId()), true);
        appendJson(sb, "username", log.getUsername(), false);
        appendJson(sb, "role", log.getRole(), false);
        appendJson(sb, "action", log.getAction(), false);
        appendJson(sb, "target_type", log.getTargetType(), false);
        appendJson(sb, "target_id", log.getTargetId(), false);
        appendJson(sb, "detail", log.getDetail(), false);
        appendJson(sb, "result", log.getResult(), false);
        appendJson(sb, "ip", log.getIp(), false);
        appendJson(sb, "cost_ms", log.getCostMs() == null ? null : String.valueOf(log.getCostMs()), false);
        appendJson(sb, "create_time",
                log.getCreateTime() == null ? null : log.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), false);
        sb.append('}');
        return sb.toString();
    }

    private void appendJson(StringBuilder sb, String key, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(value)).append('"');
        }
    }

    private String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 启动时清理过期文件日志（默认保留 30 天） */
    private void cleanupExpiredFiles() {
        try {
            int keepDays = systemConfigService.getInt("operation_log.file_keep_days", 30);
            if (keepDays <= 0 || !Files.exists(logDir)) {
                return;
            }
            long threshold = System.currentTimeMillis() - keepDays * 24L * 3600 * 1000;
            File[] files = logDir.toFile().listFiles((dir, name) -> name.startsWith("ops-") && name.endsWith(".log"));
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (file.lastModified() < threshold && file.delete()) {
                    System.out.println("已删除过期操作日志文件：" + file.getName());
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 定时清理（库表） ====================

    /**
     * 每晚 03:30 清理超过保留天数的日志（默认 90 天，可在管理端调整）。
     * 可选：清理前归档为 CSV。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupExpiredLogs() {
        try {
            int retentionDays = systemConfigService.getInt("operation_log.retention_days", 90);
            if (retentionDays <= 0) {
                return;
            }
            LocalDateTime deadline = LocalDateTime.now().minusDays(retentionDays);
            if (systemConfigService.getBoolean("operation_log.archive_enabled", false)) {
                archiveBefore(deadline);
            }
            int deleted = jdbcTemplate.update(
                    "DELETE FROM operation_log WHERE create_time < ?", Timestamp.valueOf(deadline));
            if (deleted > 0) {
                System.out.println("操作日志清理完成：删除 " + deleted + " 条（保留 " + retentionDays + " 天）");
            }
            cleanupExpiredFiles();
        } catch (Exception e) {
            System.err.println("操作日志清理失败：" + e.getMessage());
        }
    }

    private void archiveBefore(LocalDateTime deadline) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, user_id, username, role, action, target_type, target_id, detail, result, ip, cost_ms, create_time " +
                            "FROM operation_log WHERE create_time < ? ORDER BY id", Timestamp.valueOf(deadline));
            if (rows.isEmpty()) {
                return;
            }
            if (!Files.exists(archiveDir)) {
                Files.createDirectories(archiveDir);
            }
            Path file = archiveDir.resolve("operation_log_" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv");
            StringBuilder sb = new StringBuilder();
            sb.append("id,user_id,username,role,action,target_type,target_id,detail,result,ip,cost_ms,create_time\n");
            for (Map<String, Object> row : rows) {
                sb.append(csv(row.get("id"))).append(',')
                        .append(csv(row.get("user_id"))).append(',')
                        .append(csv(row.get("username"))).append(',')
                        .append(csv(row.get("role"))).append(',')
                        .append(csv(row.get("action"))).append(',')
                        .append(csv(row.get("target_type"))).append(',')
                        .append(csv(row.get("target_id"))).append(',')
                        .append(csv(row.get("detail"))).append(',')
                        .append(csv(row.get("result"))).append(',')
                        .append(csv(row.get("ip"))).append(',')
                        .append(csv(row.get("cost_ms"))).append(',')
                        .append(csv(row.get("create_time"))).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("操作日志归档完成：" + file.getFileName());
        } catch (Exception e) {
            System.err.println("操作日志归档失败：" + e.getMessage());
        }
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replace("\"", "\"\"");
        return "\"" + text + "\"";
    }

    // ==================== 工具方法 ====================

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getWrittenCount() {
        return writtenCount.get();
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private User currentUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        try {
            HttpSession session = request.getSession(false);
            if (session == null) {
                return null;
            }
            Object user = session.getAttribute("user");
            return user instanceof User ? (User) user : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        try {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                int comma = ip.indexOf(',');
                return trim(comma > 0 ? ip.substring(0, comma).trim() : ip.trim(), 64);
            }
            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                return trim(ip.trim(), 64);
            }
            return trim(request.getRemoteAddr(), 64);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer costMs(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        try {
            Object start = request.getAttribute(REQ_ATTR_START);
            if (start instanceof Long) {
                long cost = System.currentTimeMillis() - (Long) start;
                return (int) Math.max(0, Math.min(Integer.MAX_VALUE, cost));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
