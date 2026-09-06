package com.debuglab.loyalty.controller;

import com.debuglab.loyalty.dto.EarnRequest;
import com.debuglab.loyalty.dto.RedeemRequest;
import com.debuglab.loyalty.entity.LoyaltyAccount;
import com.debuglab.loyalty.service.LoyaltyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/loyalty")
public class LoyaltyController {

    private final LoyaltyService loyaltyService;

    public LoyaltyController(LoyaltyService loyaltyService) {
        this.loyaltyService = loyaltyService;
    }

    @GetMapping
    public ResponseEntity<List<LoyaltyAccount>> all() {
        return ResponseEntity.ok(loyaltyService.findAll());
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<LoyaltyAccount> account(@PathVariable String customerId) {
        return ResponseEntity.ok(loyaltyService.find(customerId).orElse(null));
    }

    @PostMapping("/{customerId}/earn")
    public ResponseEntity<LoyaltyAccount> earn(@PathVariable String customerId,
                                               @Valid @RequestBody EarnRequest request) {
        return ResponseEntity.ok(loyaltyService.earn(customerId, request.getAmount()));
    }

    @PostMapping("/{customerId}/redeem")
    public ResponseEntity<LoyaltyAccount> redeem(@PathVariable String customerId,
                                                 @Valid @RequestBody RedeemRequest request) {
        return ResponseEntity.ok(loyaltyService.redeem(customerId, request.getPoints()));
    }
}
