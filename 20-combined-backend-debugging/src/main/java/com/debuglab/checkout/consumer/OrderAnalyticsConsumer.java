package com.debuglab.checkout.consumer;

import com.debuglab.checkout.event.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Feeds the live sales tile on the operations dashboard.
 */
@Component
public class OrderAnalyticsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderAnalyticsConsumer.class);

    private final AtomicLong ordersSeen = new AtomicLong();
    private final AtomicReference<BigDecimal> valueSeen = new AtomicReference<>(BigDecimal.ZERO);

    @KafkaListener(topics = "${checkout.topic.orders}", groupId = "notification-service")
    public void onOrderPlaced(OrderPlacedEvent event) {
        long seen = ordersSeen.incrementAndGet();
        valueSeen.updateAndGet(current -> current.add(event.getAmount()));
        log.info("ANALYTICS counted {} (total {})", event.getOrderRef(), seen);
    }

    public long getOrdersSeen() {
        return ordersSeen.get();
    }

    public BigDecimal getValueSeen() {
        return valueSeen.get();
    }
}
