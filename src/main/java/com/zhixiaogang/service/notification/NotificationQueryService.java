package com.zhixiaogang.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhixiaogang.entity.Notification;
import com.zhixiaogang.mapper.NotificationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户端通知读取：列表、未读数、单条/全部标记已读。
 * 返回值结构与前端约定保持一致（success/message/notifications/data/count）。
 */
@Service
public class NotificationQueryService {

    @Autowired
    private NotificationMapper notificationMapper;

    /** 本人通知列表（最新在前） */
    public Map<String, Object> listForUser(Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Notification> list = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getUserId, userId)
                            .orderByDesc(Notification::getCreateTime)
                            .orderByDesc(Notification::getId));
            result.put("success", true);
            result.put("notifications", list);
            // 与全局接口口径统一：列表同时挂载到 data 键（notifications 保留向后兼容）
            result.put("data", list);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "加载通知失败，请稍后重试");
            result.put("notifications", List.of());
        }
        return result;
    }

    /** 本人未读数量 */
    public Map<String, Object> unreadCount(Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long count = notificationMapper.selectCount(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getUserId, userId)
                            .eq(Notification::getIsRead, 0));
            result.put("success", true);
            result.put("count", count == null ? 0 : count.intValue());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "加载未读数失败，请稍后重试");
            result.put("count", 0);
        }
        return result;
    }

    /** 标记单条已读（仅本人通知） */
    public Map<String, Object> markRead(Long userId, Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            Notification n = notificationMapper.selectById(id);
            if (n == null || !userId.equals(n.getUserId())) {
                result.put("success", false);
                result.put("message", "通知不存在");
                return result;
            }
            if (n.getIsRead() == null || n.getIsRead() == 0) {
                LambdaUpdateWrapper<Notification> uw = new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getId, id)
                        .set(Notification::getIsRead, 1);
                notificationMapper.update(null, uw);
            }
            result.put("success", true);
            result.put("message", "已标记已读");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "操作失败，请稍后重试");
        }
        return result;
    }

    /** 全部标记已读 */
    public Map<String, Object> markReadAll(Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            LambdaUpdateWrapper<Notification> uw = new LambdaUpdateWrapper<Notification>()
                    .eq(Notification::getUserId, userId)
                    .eq(Notification::getIsRead, 0)
                    .set(Notification::getIsRead, 1);
            notificationMapper.update(null, uw);
            result.put("success", true);
            result.put("message", "已全部标记已读");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "操作失败，请稍后重试");
        }
        return result;
    }
}
