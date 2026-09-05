package com.debuglab.settlement.service;

import com.debuglab.settlement.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentPublisher.class);
    private static final String[] PAYERS = {
            "Aarav Sharma", "Divya Nair", "Rohan Mehta", "Priya Iyer", "Kabir Khanna"
    };

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    private final String topic;

    public PaymentPublisher(KafkaTemplate<String, PaymentEvent> kafkaTemplate,
                            @Value("${app.kafka.payments-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public int publishBatch(int count) {
        for (int i = 0; i < count; i++) {
            String reference = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            PaymentEvent event = new PaymentEvent(reference, PAYERS[i % PAYERS.length],
                    BigDecimal.valueOf(1000 + (i * 37L)));
            kafkaTemplate.send(topic, reference, event);
        }
        log.info("Published {} payment events to '{}'", count, topic);
        return count;
    }

    public String publishUnprocessable() {
        String reference = "PAY-BAD-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        kafkaTemplate.send(topic, reference,
                new PaymentEvent(reference, "Unknown Payer", BigDecimal.ZERO));
        log.info("Published unprocessable payment {}", reference);
        return reference;
    }

    public String getTopic() {
        return topic;
    }
}
