package com.debuglab.invoicing.service;

import com.debuglab.invoicing.config.RabbitConfig;
import com.debuglab.invoicing.event.InvoiceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class InvoicePublisher {

    private static final Logger log = LoggerFactory.getLogger(InvoicePublisher.class);
    private static final String[] CUSTOMERS = {
            "Northwind Ltd", "Sterling Traders", "Kaveri Exports", "Deccan Systems"
    };

    private final RabbitTemplate rabbitTemplate;

    public InvoicePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public int submitBatch(String tier, int count) {
        for (int i = 0; i < count; i++) {
            InvoiceEvent event = new InvoiceEvent(nextNumber(), CUSTOMERS[i % CUSTOMERS.length],
                    tier, BigDecimal.valueOf(5000 + (i * 111L)));
            send(tier, event);
        }
        log.info("Submitted {} {} invoices", count, tier);
        return count;
    }

    public String submitUnprocessable() {
        InvoiceEvent event = new InvoiceEvent(nextNumber(), "Unknown Trader", "retry",
                BigDecimal.ZERO);
        send("retry", event);
        log.info("Submitted unprocessable invoice {}", event.getInvoiceNumber());
        return event.getInvoiceNumber();
    }

    private void send(String tier, InvoiceEvent event) {
        String routingKey = "invoice." + tier + ".submitted";
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, event, message -> {
            message.getMessageProperties().setHeader("x-retry-count", 0);
            return message;
        });
    }

    private String nextNumber() {
        return "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
