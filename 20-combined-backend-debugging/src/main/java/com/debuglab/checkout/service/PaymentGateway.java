package com.debuglab.checkout.service;

import com.debuglab.checkout.exception.PaymentDeclinedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Stands in for the payment provider's SDK. The card token comes from the
 * checkout widget in the browser and always begins with "tok_".
 */
@Service
public class PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);

    public void authorise(String cardToken, BigDecimal amount) {
        if (cardToken == null || !cardToken.startsWith("tok_")) {
            throw new PaymentDeclinedException("The card token was rejected by the provider");
        }
        log.info("Authorised {} against {}", amount, cardToken);
    }
}
