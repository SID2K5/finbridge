package com.finbridge.dashboard;

import com.finbridge.common.ApiResponse;
import com.finbridge.exception.InvalidRequestException;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Read-only figures computed from live data")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardService.Summary> summary() {
        return ApiResponse.of(dashboardService.summary());
    }

    @GetMapping("/transactions")
    public ApiResponse<List<DashboardService.StatusCount>> transactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("from must not be after to");
        }
        return ApiResponse.of(dashboardService.transactions(from, to));
    }

    /** data is absent when no reconciliation has ever been run. */
    @GetMapping("/reconciliation-summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE_MANAGER')")
    public ApiResponse<DashboardService.ReconciliationSummary> reconciliationSummary() {
        return ApiResponse.of(dashboardService.reconciliationSummary());
    }

    @GetMapping("/invoice-trend")
    public ApiResponse<List<DashboardService.DayCount>> invoiceTrend(@RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > 365) {
            throw new InvalidRequestException("days must be between 1 and 365");
        }
        return ApiResponse.of(dashboardService.invoiceTrend(days));
    }

    @GetMapping("/recent-activity")
    public ApiResponse<List<DashboardService.Activity>> recentActivity() {
        return ApiResponse.of(dashboardService.recentActivity());
    }
}
