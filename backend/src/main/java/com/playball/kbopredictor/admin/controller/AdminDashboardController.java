package com.playball.kbopredictor.admin.controller;

import com.playball.kbopredictor.admin.dto.AdminDashboardSummaryResponse;
import com.playball.kbopredictor.admin.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<AdminDashboardSummaryResponse> summary() {
        return ResponseEntity.ok(dashboardService.getSummary());
    }
}
