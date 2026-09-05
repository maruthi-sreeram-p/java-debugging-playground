package com.debuglab.notifications.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SmsNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationChannel.class);

    @Value("${notification.sms.gateway:sandbox-gateway}")
    private String gatewayId;

    @Override
    public String channelName() {
        return "SMS";
    }

    @Override
    public boolean deliver(String recipient, String subject, String body) {
        log.info("[{}] gateway={} to={} chars={}",
                channelName(), gatewayId, recipient, body == null ? 0 : body.length());
        return true;
    }
}
