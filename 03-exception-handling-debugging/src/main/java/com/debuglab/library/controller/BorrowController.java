package com.debuglab.library.controller;

import com.debuglab.library.dto.BorrowRequest;
import com.debuglab.library.dto.BorrowResponse;
import com.debuglab.library.service.BorrowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BorrowController {

    private final BorrowService borrowService;

    public BorrowController(BorrowService borrowService) {
        this.borrowService = borrowService;
    }

    @PostMapping("/api/borrow")
    public ResponseEntity<BorrowResponse> borrow(@Valid @RequestBody BorrowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(borrowService.borrow(request));
    }

    @PostMapping("/api/return/{borrowId}")
    public ResponseEntity<BorrowResponse> returnBook(@PathVariable Long borrowId) {
        return ResponseEntity.ok(borrowService.returnBook(borrowId));
    }

    @GetMapping("/api/borrow/history")
    public ResponseEntity<List<BorrowResponse>> history(@RequestParam(required = false) String member) {
        return ResponseEntity.ok(borrowService.historyFor(member == null ? "" : member));
    }
}
