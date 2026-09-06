package com.debuglab.invoicing;

import com.debuglab.invoicing.config.RabbitConfig;
import com.debuglab.invoicing.entity.ProcessedInvoice;
import com.debuglab.invoicing.repository.AuditedInvoiceRepository;
import com.debuglab.invoicing.repository.ProcessedInvoiceRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the RabbitMQ container from docker-compose.yml:
 *
 *     docker compose up -d
 *
 * They cover the paths that are cheap to assert automatically. The dead-letter
 * path and the poison-invoice path are exercised by hand with rabbitmqctl and the
 * management UI - see the README.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvoicePipelineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProcessedInvoiceRepository processedInvoiceRepository;

    @Autowired
    private AuditedInvoiceRepository auditedInvoiceRepository;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private List<Queue> declaredQueues;

    @Autowired
    private List<Binding> declaredBindings;

    private void submit(String tier, int count) throws Exception {
        mockMvc.perform(post("/api/invoices/submit")
                        .param("tier", tier)
                        .param("count", String.valueOf(count)))
                .andExpect(status().isOk());
    }

    @Test
    void aBatchCanBeSubmitted() throws Exception {
        mockMvc.perform(post("/api/invoices/submit")
                        .param("tier", "standard")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("standard"))
                .andExpect(jsonPath("$.submitted").value(3));
    }

    @Test
    void everyStandardInvoiceIsProcessed() throws Exception {
        long before = processedInvoiceRepository.countByTier("standard");
        submit("standard", 10);

        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertEquals(before + 10,
                        processedInvoiceRepository.countByTier("standard"),
                        "all ten standard invoices should have been processed"));
    }

    @Test
    void everyPriorityInvoiceIsProcessedByTheInvoiceProcessor() throws Exception {
        submit("priority", 8);

        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<ProcessedInvoice> priority = processedInvoiceRepository.findAllByOrderByIdAsc()
                            .stream()
                            .filter(invoice -> "priority".equals(invoice.getTier()))
                            .collect(Collectors.toList());
                    assertEquals(8, priority.size(),
                            "all eight priority invoices should reach the priority processor");
                });
    }

    @Test
    void everySubmittedInvoiceIsAudited() throws Exception {
        long before = auditedInvoiceRepository.count();
        submit("standard", 4);

        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertEquals(before + 4, auditedInvoiceRepository.count(),
                        "the audit trail should hold a copy of every invoice"));
    }

    @Test
    void everyWorkQueueHasExactlyOneConsumer() {
        for (String name : List.of(RabbitConfig.STANDARD_QUEUE, RabbitConfig.PRIORITY_QUEUE,
                RabbitConfig.RETRY_QUEUE, RabbitConfig.AUDIT_QUEUE)) {

            QueueInformation info = amqpAdmin.getQueueInfo(name);
            assertNotNull(info, "queue '" + name + "' was not found on the broker");
            assertEquals(1, info.getConsumerCount(),
                    "queue '" + name + "' should be served by exactly one listener, but the broker "
                            + "reports " + info.getConsumerCount());
        }
    }

    @Test
    void rejectedInvoicesCanReachTheDeadLetterQueue() {
        Map<String, Object> deadLetterKeys = declaredBindings.stream()
                .filter(binding -> RabbitConfig.DEAD_LETTER_EXCHANGE.equals(binding.getExchange()))
                .collect(Collectors.toMap(Binding::getRoutingKey, Binding::getDestination,
                        (first, second) -> first));

        for (Queue queue : declaredQueues) {
            Object key = queue.getArguments().get("x-dead-letter-routing-key");
            if (key == null) {
                continue;
            }
            assertTrue(deadLetterKeys.containsKey(key.toString()),
                    "queue '" + queue.getName() + "' dead-letters with routing key '" + key
                            + "', but nothing on '" + RabbitConfig.DEAD_LETTER_EXCHANGE
                            + "' is bound to that key, so rejected messages are discarded");
        }
    }
}
