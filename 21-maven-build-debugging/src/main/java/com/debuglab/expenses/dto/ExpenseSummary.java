package com.debuglab.expenses.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public class ExpenseSummary {

    private final LocalDate from;
    private final LocalDate to;
    private final int expenseCount;
    private final Map<String, BigDecimal> totalByCategory;
    private final BigDecimal grandTotal;

    public ExpenseSummary(LocalDate from, LocalDate to, int expenseCount,
                          Map<String, BigDecimal> totalByCategory, BigDecimal grandTotal) {
        this.from = from;
        this.to = to;
        this.expenseCount = expenseCount;
        this.totalByCategory = totalByCategory;
        this.grandTotal = grandTotal;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    public int getExpenseCount() {
        return expenseCount;
    }

    public Map<String, BigDecimal> getTotalByCategory() {
        return totalByCategory;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }
}
