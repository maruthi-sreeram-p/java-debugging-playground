package com.debuglab.checkout.service;

import com.debuglab.checkout.exception.CheckoutDeclinedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Orders above the review threshold cannot be completed automatically; the
 * finance team approves them by hand the next working day.
 */
@Service
public class RiskScreeningService {

    private static final Logger log = LoggerFactory.getLogger(RiskScreeningService.class);

    private final BigDecimal reviewThreshold;

    public RiskScreeningService(@Value("${checkout.risk.review-threshold:100000}") BigDecimal reviewThreshold) {
        this.reviewThreshold = reviewThreshold;
    }

    public void screen(String orderRef, BigDecimal amount) throws CheckoutDeclinedException {
        if (amount.compareTo(reviewThreshold) > 0) {
            log.warn("Order {} for {} is above the review threshold {}", orderRef, amount,
                    reviewThreshold);
            throw new CheckoutDeclinedException("Order " + orderRef + " needs manual approval");
        }
    }
}
