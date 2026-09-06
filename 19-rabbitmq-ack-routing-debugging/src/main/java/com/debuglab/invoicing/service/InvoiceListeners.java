package com.debuglab.invoicing.service;

import com.debuglab.invoicing.config.RabbitConfig;
import com.debuglab.invoicing.entity.AuditedInvoice;
import com.debuglab.invoicing.entity.ProcessedInvoice;
import com.debuglab.invoicing.event.InvoiceEvent;
import com.debuglab.invoicing.repository.AuditedInvoiceRepository;
import com.debuglab.invoicing.repository.ProcessedInvoiceRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;

@Service
public class InvoiceListeners {

    private static final Logger log = LoggerFactory.getLogger(InvoiceListeners.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ProcessedInvoiceRepository processedInvoiceRepository;
    private final AuditedInvoiceRepository auditedInvoiceRepository;

    public InvoiceListeners(ProcessedInvoiceRepository processedInvoiceRepository,
                            AuditedInvoiceRepository auditedInvoiceRepository) {
        this.processedInvoiceRepository = processedInvoiceRepository;
        this.auditedInvoiceRepository = auditedInvoiceRepository;
    }

    @RabbitListener(queues = RabbitConfig.STANDARD_QUEUE)
    public void onStandardInvoice(InvoiceEvent event,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("STANDARD received {}", event.getInvoiceNumber());
        processedInvoiceRepository.save(new ProcessedInvoice(event.getInvoiceNumber(),
                event.getCustomer(), event.getTier(), event.getAmount(), "standard-processor"));
    }

    @RabbitListener(queues = RabbitConfig.PRIORITY_QUEUE)
    public void onPriorityInvoice(InvoiceEvent event,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
            throws IOException {

        log.info("PRIORITY received {}", event.getInvoiceNumber());
        processedInvoiceRepository.save(new ProcessedInvoice(event.getInvoiceNumber(),
                event.getCustomer(), event.getTier(), event.getAmount(), "priority-processor"));
        channel.basicAck(deliveryTag, false);
    }

    @RabbitListener(queues = RabbitConfig.RETRY_QUEUE)
    public void onRetryInvoice(InvoiceEvent event,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                               @Header(name = "x-retry-count", required = false) Integer retryCount)
            throws IOException {

        log.info("RETRY received {} (x-retry-count header: {}, limit {})",
                event.getInvoiceNumber(), retryCount, MAX_ATTEMPTS);

        if (event.getAmount() == null || event.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("RETRY rejecting {} - amount is not positive", event.getInvoiceNumber());
            channel.basicNack(deliveryTag, false, true);
            return;
        }

        processedInvoiceRepository.save(new ProcessedInvoice(event.getInvoiceNumber(),
                event.getCustomer(), event.getTier(), event.getAmount(), "retry-processor"));
        channel.basicAck(deliveryTag, false);
    }

    @RabbitListener(queues = RabbitConfig.AUDIT_QUEUE)
    public void onAuditCopy(InvoiceEvent event,
                            Channel channel,
                            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                            @Header(name = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey)
            throws IOException {

        channel.basicAck(deliveryTag, false);

        log.info("AUDIT received {} via '{}'", event.getInvoiceNumber(), routingKey);
        auditedInvoiceRepository.save(new AuditedInvoice(event.getInvoiceNumber(), routingKey));
    }
}
