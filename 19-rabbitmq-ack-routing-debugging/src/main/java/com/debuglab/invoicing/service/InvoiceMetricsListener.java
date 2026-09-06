package com.debuglab.invoicing.service;

import com.debuglab.invoicing.config.RabbitConfig;
import com.debuglab.invoicing.event.InvoiceEvent;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps a running count of the priority invoices flowing through the pipeline
 * so the operations dashboard has something to plot.
 */
@Service
public class InvoiceMetricsListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceMetricsListener.class);

    private final AtomicLong prioritySeen = new AtomicLong();

    @RabbitListener(queues = RabbitConfig.PRIORITY_QUEUE)
    public void countPriority(InvoiceEvent event,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        long total = prioritySeen.incrementAndGet();
        log.info("METRICS counted {} (total {})", event.getInvoiceNumber(), total);
        channel.basicAck(deliveryTag, false);
    }

    public long getPrioritySeen() {
        return prioritySeen.get();
    }
}
