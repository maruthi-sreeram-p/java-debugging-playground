package com.debuglab.settlement.controller;

import com.debuglab.settlement.entity.Settlement;
import com.debuglab.settlement.repository.ProcessedMessageRepository;
import com.debuglab.settlement.repository.SettlementRepository;
import com.debuglab.settlement.service.PaymentPublisher;
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
public class SettlementController {

    private final PaymentPublisher paymentPublisher;
    private final SettlementRepository settlementRepository;
    private final ProcessedMessageRepository processedMessageRepository;

    public SettlementController(PaymentPublisher paymentPublisher,
                                SettlementRepository settlementRepository,
                                ProcessedMessageRepository processedMessageRepository) {
        this.paymentPublisher = paymentPublisher;
        this.settlementRepository = settlementRepository;
        this.processedMessageRepository = processedMessageRepository;
    }

    @PostMapping("/payments/publish")
    public ResponseEntity<Map<String, Object>> publish(@RequestParam(defaultValue = "5") int count) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("published", paymentPublisher.publishBatch(count));
        body.put("topic", paymentPublisher.getTopic());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/payments/unprocessable")
    public ResponseEntity<Map<String, Object>> unprocessable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", paymentPublisher.publishUnprocessable());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/settlements")
    public ResponseEntity<List<Settlement>> settlements() {
        return ResponseEntity.ok(settlementRepository.findAllByOrderByIdAsc());
    }

    @GetMapping("/settlements/duplicates")
    public ResponseEntity<Map<String, Object>> duplicates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        long extra = 0;

        for (Object[] row : settlementRepository.findDuplicateReferences()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("paymentReference", row[0]);
            entry.put("times", row[1]);
            rows.add(entry);
            extra += ((Number) row[1]).longValue() - 1;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("distinctReferences", settlementRepository.count() - extra);
        body.put("settlementRows", settlementRepository.count());
        body.put("duplicateReferences", rows);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/health/consumer")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", paymentPublisher.getTopic());
        body.put("settlements", settlementRepository.count());
        body.put("processedMarkers", processedMessageRepository.count());
        return ResponseEntity.ok(body);
    }
}
