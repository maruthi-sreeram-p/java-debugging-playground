package com.debuglab.onboarding.controller;

import com.debuglab.onboarding.dto.AddressRequest;
import com.debuglab.onboarding.dto.CustomerRequest;
import com.debuglab.onboarding.dto.CustomerResponse;
import com.debuglab.onboarding.service.CustomerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> update(@PathVariable Long id,
                                                   @RequestBody CustomerRequest request) {
        return ResponseEntity.ok(customerService.update(id, request));
    }

    @PostMapping("/{id}/address")
    public ResponseEntity<CustomerResponse> updateAddress(@PathVariable Long id,
                                                          @Valid @RequestBody AddressRequest request) {
        return ResponseEntity.ok(customerService.updateAddress(id, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.findById(id));
    }

    @GetMapping
    public ResponseEntity<List<CustomerResponse>> search(
            @RequestParam(defaultValue = "18") @Min(value = 18, message = "minAge must be at least 18") int minAge) {
        return ResponseEntity.ok(customerService.findAtLeastAged(minAge));
    }
}
