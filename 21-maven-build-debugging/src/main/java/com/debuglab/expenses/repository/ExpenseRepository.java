package com.debuglab.expenses.repository;

import com.debuglab.expenses.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findBySpentOnBetweenOrderBySpentOnAsc(LocalDate from, LocalDate to);

    List<Expense> findAllByOrderBySpentOnAsc();
}
