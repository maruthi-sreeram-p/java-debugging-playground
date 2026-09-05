package com.debuglab.settlement.service;

import com.debuglab.settlement.entity.ProcessedMessage;
import com.debuglab.settlement.entity.Settlement;
import com.debuglab.settlement.event.PaymentEvent;
import com.debuglab.settlement.repository.ProcessedMessageRepository;
import com.debuglab.settlement.repository.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class SettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);

    private final SettlementRepository settlementRepository;
    private final ProcessedMessageRepository processedMessageRepository;

    public SettlementConsumer(SettlementRepository settlementRepository,
                              ProcessedMessageRepository processedMessageRepository) {
        this.settlementRepository = settlementRepository;
        this.processedMessageRepository = processedMessageRepository;
    }

    @KafkaListener(topics = "${app.kafka.payments-topic}")
    public void onPayment(PaymentEvent event, Acknowledgment acknowledgment) {
        if (processedMessageRepository.existsByMessageKey(event.getReference())) {
            log.debug("Already settled {}", event.getReference());
            return;
        }

        if (event.getAmount() == null || event.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Payment " + event.getReference() + " has a non-positive amount");
        }

        settleSlowly();

        Settlement settlement = new Settlement();
        settlement.setPaymentReference(event.getReference());
        settlement.setPayer(event.getPayer());
        settlement.setAmount(event.getAmount());
        settlement.setSettledAt(LocalDateTime.now());
        settlementRepository.save(settlement);

        processedMessageRepository.save(
                new ProcessedMessage(event.getReference() + "-" + System.nanoTime()));

        log.info("SETTLED {} for {}", event.getReference(), event.getPayer());
    }

    private void settleSlowly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
