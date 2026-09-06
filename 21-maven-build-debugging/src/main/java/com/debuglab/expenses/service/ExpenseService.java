package com.debuglab.expenses.service;

import com.debuglab.expenses.dto.CreateExpenseRequest;
import com.debuglab.expenses.dto.ExpenseSummary;
import com.debuglab.expenses.entity.Expense;
import com.debuglab.expenses.repository.ExpenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final ExpenseRepository expenseRepository;

    public ExpenseService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    @Transactional
    public Expense record(CreateExpenseRequest request) {
        Expense expense = new Expense(request.getDescription(), request.getCategory(),
                request.getAmount(), request.getSpentOn(), request.getSubmittedBy());
        Expense saved = expenseRepository.save(expense);
        log.info("Recorded expense {} of {} in {}", saved.getId(), saved.getAmount(),
                saved.getCategory());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Expense> findAll() {
        return expenseRepository.findAllByOrderBySpentOnAsc();
    }

    @Transactional(readOnly = true)
    public ExpenseSummary summarise(LocalDate from, LocalDate to) {
        List<Expense> expenses = expenseRepository.findBySpentOnBetweenOrderBySpentOnAsc(from, to);

        Map<String, BigDecimal> totalByCategory = new LinkedHashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (Expense expense : expenses) {
            totalByCategory.merge(expense.getCategory(), expense.getAmount(), BigDecimal::add);
            grandTotal.add(expense.getAmount());
        }

        log.info("Summarised {} expenses between {} and {}", expenses.size(), from, to);
        return new ExpenseSummary(from, to, expenses.size(), totalByCategory, grandTotal);
    }
}
