package com.debuglab.expenses.controller;

import com.debuglab.expenses.dto.CreateExpenseRequest;
import com.debuglab.expenses.dto.ExpenseSummary;
import com.debuglab.expenses.entity.Expense;
import com.debuglab.expenses.service.ExpenseService;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExpenseController {

    private final ExpenseService expenseService;
    private final Environment environment;

    public ExpenseController(ExpenseService expenseService, Environment environment) {
        this.expenseService = expenseService;
        this.environment = environment;
    }

    @PostMapping("/expenses")
    public ResponseEntity<Expense> record(@Valid @RequestBody CreateExpenseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(expenseService.record(request));
    }

    @GetMapping("/expenses")
    public ResponseEntity<List<Expense>> all() {
        return ResponseEntity.ok(expenseService.findAll());
    }

    @GetMapping("/expenses/summary")
    public ResponseEntity<ExpenseSummary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(expenseService.summarise(from, to));
    }

    @GetMapping("/meta")
    public ResponseEntity<Map<String, Object>> meta() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", environment.getProperty("spring.application.name"));
        body.put("buildVersion", environment.getProperty("app.build-version"));
        return ResponseEntity.ok(body);
    }
}
