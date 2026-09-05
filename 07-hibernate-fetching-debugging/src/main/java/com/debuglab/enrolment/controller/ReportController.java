package com.debuglab.enrolment.controller;

import com.debuglab.enrolment.service.CatalogueReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final CatalogueReportService catalogueReportService;

    public ReportController(CatalogueReportService catalogueReportService) {
        this.catalogueReportService = catalogueReportService;
    }

    @GetMapping("/catalogue")
    public ResponseEntity<Map<String, Object>> catalogue() {
        return ResponseEntity.ok(catalogueReportService.buildReport());
    }
}
