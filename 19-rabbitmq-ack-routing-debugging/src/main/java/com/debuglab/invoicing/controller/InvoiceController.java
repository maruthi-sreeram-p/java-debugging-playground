package com.debuglab.invoicing.controller;

import com.debuglab.invoicing.config.RabbitConfig;
import com.debuglab.invoicing.entity.AuditedInvoice;
import com.debuglab.invoicing.entity.ProcessedInvoice;
import com.debuglab.invoicing.repository.AuditedInvoiceRepository;
import com.debuglab.invoicing.repository.ProcessedInvoiceRepository;
import com.debuglab.invoicing.service.InvoiceMetricsListener;
import com.debuglab.invoicing.service.InvoicePublisher;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InvoiceController {

    private final InvoicePublisher invoicePublisher;
    private final ProcessedInvoiceRepository processedInvoiceRepository;
    private final AuditedInvoiceRepository auditedInvoiceRepository;
    private final InvoiceMetricsListener metricsListener;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    public InvoiceController(InvoicePublisher invoicePublisher,
                             ProcessedInvoiceRepository processedInvoiceRepository,
                             AuditedInvoiceRepository auditedInvoiceRepository,
                             InvoiceMetricsListener metricsListener,
                             RabbitTemplate rabbitTemplate,
                             AmqpAdmin amqpAdmin) {
        this.invoicePublisher = invoicePublisher;
        this.processedInvoiceRepository = processedInvoiceRepository;
        this.auditedInvoiceRepository = auditedInvoiceRepository;
        this.metricsListener = metricsListener;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    @PostMapping("/invoices/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestParam(defaultValue = "standard") String tier,
            @RequestParam(defaultValue = "10") int count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tier", tier);
        body.put("submitted", invoicePublisher.submitBatch(tier, count));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/invoices/unprocessable")
    public ResponseEntity<Map<String, Object>> unprocessable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoiceNumber", invoicePublisher.submitUnprocessable());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/invoices/processed")
    public ResponseEntity<List<ProcessedInvoice>> processed() {
        return ResponseEntity.ok(processedInvoiceRepository.findAllByOrderByIdAsc());
    }

    @GetMapping("/invoices/audited")
    public ResponseEntity<List<AuditedInvoice>> audited() {
        return ResponseEntity.ok(auditedInvoiceRepository.findAllByOrderByIdAsc());
    }

    @GetMapping("/invoices/dead-letter")
    public ResponseEntity<List<Object>> deadLettered() {
        List<Object> drained = new ArrayList<>();
        Object next;
        while ((next = rabbitTemplate.receiveAndConvert(RabbitConfig.DEAD_LETTER_QUEUE)) != null) {
            drained.add(next);
        }
        return ResponseEntity.ok(drained);
    }

    @GetMapping("/queues/stats")
    public ResponseEntity<List<Map<String, Object>>> queueStats() {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (String name : List.of(RabbitConfig.STANDARD_QUEUE, RabbitConfig.PRIORITY_QUEUE,
                RabbitConfig.RETRY_QUEUE, RabbitConfig.AUDIT_QUEUE, RabbitConfig.DEAD_LETTER_QUEUE)) {

            QueueInformation info = amqpAdmin.getQueueInfo(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queue", name);
            row.put("messages", info == null ? null : info.getMessageCount());
            row.put("consumers", info == null ? null : info.getConsumerCount());
            stats.add(row);
        }
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/pipeline/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processedStandard", processedInvoiceRepository.countByTier("standard"));
        body.put("processedPriority", processedInvoiceRepository.countByTier("priority"));
        body.put("processedRetry", processedInvoiceRepository.countByTier("retry"));
        body.put("audited", auditedInvoiceRepository.count());
        body.put("countedByMetrics", metricsListener.getPrioritySeen());
        return ResponseEntity.ok(body);
    }
}
