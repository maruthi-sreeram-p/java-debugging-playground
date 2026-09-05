package com.debuglab.notifications.service;

import com.debuglab.notifications.repository.NotificationLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NotificationStatsCalculator {

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    public Map<String, Long> calculate() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", notificationLogRepository.count());
        stats.put("email", notificationLogRepository.countByChannel("EMAIL"));
        stats.put("sms", notificationLogRepository.countByChannel("SMS"));
        stats.put("delivered", notificationLogRepository.countByStatus("DELIVERED"));
        stats.put("failed", notificationLogRepository.countByStatus("FAILED"));
        return stats;
    }
}
