package com.finbridge.integration.monitoring;

import com.finbridge.common.ApiResponse;
import com.finbridge.common.PageQuery;
import com.finbridge.logging.IntegrationLogEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
@Tag(name = "Integration monitoring", description = "Connection status, live health checks and the integration log")
public class IntegrationController {

    private static final String OPERATORS = "hasAnyRole('ADMIN', 'INTEGRATION_OPERATOR')";

    private static final Map<String, String> LOG_SORTS = Map.of(
            "createdAt", "createdAt",
            "durationMs", "durationMs",
            "operation", "operation",
            "outcome", "outcome");

    private static final Sort DEFAULT_LOG_SORT = Sort.by(Sort.Direction.DESC, "id");

    private final IntegrationStatusService statusService;
    private final IntegrationLogQueryService logQueryService;
    private final IntegrationRetryService retryService;

    public IntegrationController(IntegrationStatusService statusService,
                                 IntegrationLogQueryService logQueryService,
                                 IntegrationRetryService retryService) {
        this.statusService = statusService;
        this.logQueryService = logQueryService;
        this.retryService = retryService;
    }

    @GetMapping("/status")
    @Operation(summary = "Stored status of each integration; makes no outbound call")
    public ApiResponse<IntegrationStatusService.StatusResponse> status() {
        return ApiResponse.of(statusService.status());
    }

    @PostMapping("/health-check")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Call Odoo now and report the result")
    public ApiResponse<IntegrationStatusService.HealthCheckResponse> healthCheck() {
        return ApiResponse.of(statusService.check());
    }

    @GetMapping("/logs")
    @PreAuthorize(OPERATORS)
    public ApiResponse<List<IntegrationLogQueryService.Summary>> logs(
            @RequestParam(required = false) IntegrationLogEntry.Type type,
            @RequestParam(required = false) IntegrationLogEntry.Outcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) String correlationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        IntegrationLogQueryService.Filter filter =
                new IntegrationLogQueryService.Filter(type, outcome, from, to, reference, correlationId);
        Pageable pageable = PageQuery.of(page, size, sort, LOG_SORTS, DEFAULT_LOG_SORT);
        return ApiResponse.page(logQueryService.search(filter, pageable), row -> row);
    }

    @PostMapping("/logs/{id}/retry")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Run again the operation behind a retryable failed log entry",
            description = "Starts the business operation the entry belongs to (a sync run, the payment's own "
                    + "retry or status check, or a health check). The stored request is never replayed. "
                    + "422 if the entry succeeded or is not retryable.")
    public ApiResponse<IntegrationRetryService.RetryResult> retry(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.finbridge.security.AuthenticatedUser user, @PathVariable Long id) {
        return ApiResponse.of(retryService.retry(id, user.id()));
    }

    @GetMapping("/logs/{id}")
    @PreAuthorize(OPERATORS)
    public ApiResponse<IntegrationLogQueryService.Detail> log(@PathVariable Long id) {
        return ApiResponse.of(logQueryService.get(id));
    }
}
