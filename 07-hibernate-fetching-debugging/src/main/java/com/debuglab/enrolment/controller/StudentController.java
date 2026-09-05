package com.debuglab.enrolment.controller;

import com.debuglab.enrolment.dto.TranscriptResponse;
import com.debuglab.enrolment.service.TranscriptService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final TranscriptService transcriptService;

    public StudentController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @GetMapping("/{id}/transcript")
    public ResponseEntity<TranscriptResponse> transcript(@PathVariable Long id) {
        return ResponseEntity.ok(transcriptService.transcriptFor(id));
    }
}
