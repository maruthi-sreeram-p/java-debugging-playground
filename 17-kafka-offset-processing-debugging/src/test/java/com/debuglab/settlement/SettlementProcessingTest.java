package com.debuglab.settlement;

import com.debuglab.settlement.repository.SettlementRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests. These require the Kafka container from docker-compose.yml:
 *
 *     docker compose up -d
 *
 * Start from a clean broker if a previous run left messages behind:
 *
 *     docker compose down -v && docker compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementProcessingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SettlementRepository settlementRepository;

    @Value("${app.kafka.topic-partitions}")
    private int partitions;

    @Value("${app.kafka.consumer-concurrency}")
    private int concurrency;

    private void publish(int count) throws Exception {
        mockMvc.perform(post("/api/payments/publish").param("count", String.valueOf(count)))
                .andExpect(status().isOk());
    }

    private void awaitSettlements(long expected, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertEquals(expected, settlementRepository.count(),
                        "expected exactly " + expected + " settlement rows"));
    }

    private void assertNoDuplicates() {
        List<Object[]> duplicates = settlementRepository.findDuplicateReferences();
        assertTrue(duplicates.isEmpty(),
                "every payment must be settled exactly once, but " + duplicates.size()
                        + " reference(s) were settled more than once");
    }

    @Test
    void everyConsumerThreadHasPartitionsToRead() {
        assertTrue(concurrency <= partitions,
                "consumer concurrency (" + concurrency + ") exceeds the partition count ("
                        + partitions + "), so " + (concurrency - partitions)
                        + " consumer(s) can never be assigned any work");
    }

    @Test
    void aSmallBatchIsSettledExactlyOnce() throws Exception {
        long before = settlementRepository.count();
        publish(5);

        awaitSettlements(before + 5, Duration.ofSeconds(30));
        assertNoDuplicates();
    }

    @Test
    void aBurstIsSettledExactlyOnce() throws Exception {
        long before = settlementRepository.count();
        publish(50);

        awaitSettlements(before + 50, Duration.ofSeconds(60));
        assertNoDuplicates();
    }

    @Test
    void anUnprocessablePaymentDoesNotBlockThePaymentsBehindIt() throws Exception {
        long before = settlementRepository.count();

        mockMvc.perform(post("/api/payments/unprocessable")).andExpect(status().isOk());
        publish(9);

        awaitSettlements(before + 9, Duration.ofSeconds(45));
    }
}
