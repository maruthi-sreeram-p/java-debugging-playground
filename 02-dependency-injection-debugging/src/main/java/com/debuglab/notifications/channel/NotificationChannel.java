package com.debuglab.notifications.channel;

public interface NotificationChannel {

    String channelName();

    boolean deliver(String recipient, String subject, String body);
}
