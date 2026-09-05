package com.debuglab.notifications.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);

    @Value("${notification.email.sender:noreply@campus.edu}")
    private String senderAddress;

    @Override
    public String channelName() {
        return "EMAIL";
    }

    @Override
    public boolean deliver(String recipient, String subject, String body) {
        log.info("[{}] from={} to={} subject='{}' bytes={}",
                channelName(), senderAddress, recipient, subject, body == null ? 0 : body.length());
        return true;
    }
}
