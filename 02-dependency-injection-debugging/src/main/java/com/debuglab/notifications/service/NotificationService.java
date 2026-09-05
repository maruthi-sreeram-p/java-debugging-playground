package com.debuglab.notifications.service;

import com.debuglab.notifications.channel.NotificationChannel;
import com.debuglab.notifications.dto.NotificationRequest;
import com.debuglab.notifications.dto.NotificationResponse;
import com.debuglab.notifications.entity.NotificationLog;
import com.debuglab.notifications.repository.NotificationLogRepository;
import com.debuglab.support.template.TemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationChannel emailChannel;
    private final NotificationChannel smsChannel;
    private final NotificationLogRepository notificationLogRepository;
    private final TemplateRenderer templateRenderer;
    private final DispatchAttemptTracker attemptTracker;

    @Value("${notification.retry.max-attempts:2}")
    private int maxAttempts;

    public NotificationService(NotificationChannel emailChannel,
                               NotificationChannel smsChannel,
                               NotificationLogRepository notificationLogRepository,
                               TemplateRenderer templateRenderer,
                               DispatchAttemptTracker attemptTracker) {
        this.emailChannel = emailChannel;
        this.smsChannel = smsChannel;
        this.notificationLogRepository = notificationLogRepository;
        this.templateRenderer = templateRenderer;
        this.attemptTracker = attemptTracker;
    }

    public NotificationResponse dispatch(NotificationRequest request) {
        NotificationChannel channel = resolveChannel(request.getType());

        Map<String, String> variables = request.getVariables() == null
                ? Collections.emptyMap()
                : request.getVariables();
        String renderedBody = templateRenderer.render(request.getMessage(), variables);

        int attempt = attemptTracker.recordAttempt();
        boolean delivered = channel.deliver(request.getRecipient(), request.getSubject(), renderedBody);

        NotificationLog entry = new NotificationLog();
        entry.setRecipient(request.getRecipient());
        entry.setSubject(request.getSubject());
        entry.setBody(renderedBody);
        entry.setChannel(channel.channelName());
        entry.setStatus(delivered ? "DELIVERED" : "FAILED");
        entry.setCreatedAt(LocalDateTime.now());
        NotificationLog saved = notificationLogRepository.save(entry);

        log.info("Dispatched notification {} over {} to {}",
                saved.getId(), saved.getChannel(), saved.getRecipient());

        return toResponse(saved, renderedBody, attempt);
    }

    private NotificationChannel resolveChannel(String type) {
        if ("SMS".equalsIgnoreCase(type)) {
            return smsChannel;
        }
        return emailChannel;
    }

    public List<NotificationResponse> history() {
        List<NotificationResponse> responses = new ArrayList<>();
        for (NotificationLog entry : notificationLogRepository.findAllByOrderByIdDesc()) {
            responses.add(toResponse(entry, entry.getBody(), 1));
        }
        return responses;
    }

    public Map<String, Long> statistics() {
        NotificationStatsCalculator calculator = new NotificationStatsCalculator();
        return calculator.calculate();
    }

    private NotificationResponse toResponse(NotificationLog entry, String renderedBody, int attempt) {
        NotificationResponse response = new NotificationResponse();
        response.setId(entry.getId());
        response.setChannel(entry.getChannel());
        response.setRecipient(entry.getRecipient());
        response.setSubject(entry.getSubject());
        response.setRenderedBody(renderedBody);
        response.setStatus(entry.getStatus());
        response.setAttemptCount(attempt);
        response.setMaxAttempts(maxAttempts);
        response.setCreatedAt(entry.getCreatedAt());
        return response;
    }
}
