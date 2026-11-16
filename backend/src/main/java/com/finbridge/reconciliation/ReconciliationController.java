package com.finbridge.reconciliation;

import com.finbridge.common.ApiResponse;
import com.finbridge.common.PageQuery;
import com.finbridge.reconciliation.ReconciliationRecord.ReviewStatus;
import com.finbridge.reconciliation.ReconciliationRecord.Status;
import com.finbridge.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE_MANAGER')")
@Tag(name = "Reconciliation",
        description = "Compare FinBridge's payments with the payment service's statement and review the differences")
public class ReconciliationController {

    private static final Map<String, String> RUN_SORTS = Map.of(
            "id", "id", "startedAt", "startedAt", "periodFrom", "periodFrom", "status", "status");

    private static final Map<String, String> RECORD_SORTS = Map.of(
            "id", "id", "status", "status", "reviewStatus", "reviewStatus", "expectedAmount", "expectedAmount");

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    public record RunRequest(@NotNull(message = "periodFrom is required") LocalDate periodFrom,
                             @NotNull(message = "periodTo is required") LocalDate periodTo) {
    }

    public record ReviewRequest(
            @NotNull(message = "reviewStatus is required (RESOLVED or NEEDS_INVESTIGATION)") ReviewStatus reviewStatus,
            @NotBlank(message = "a note is required") @Size(max = 1000) String note) {
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reconcile a period (dates are UTC, both inclusive)",
            description = "Returns 202 with the run; poll GET /api/reconciliation/runs/{id} until its status is "
                    + "COMPLETED or FAILED.")
    public ApiResponse<ReconciliationService.RunResponse> run(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @Valid @RequestBody RunRequest request) {
        return ApiResponse.of(service.start(user.id(), request.periodFrom(), request.periodTo()));
    }

    @GetMapping("/runs")
    public ApiResponse<List<ReconciliationService.RunResponse>> runs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageQuery.of(page, size, sort, RUN_SORTS, Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.page(service.listRuns(pageable), run -> run);
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "One run with its counts per reconciliation status")
    public ApiResponse<ReconciliationService.RunResponse> run(@PathVariable Long id) {
        return ApiResponse.of(service.getRun(id));
    }

    @GetMapping
    @Operation(summary = "Records of a run (the latest run when runId is left out)")
    public ApiResponse<List<ReconciliationService.RecordResponse>> records(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) ReviewStatus reviewStatus,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageQuery.of(page, size, sort, RECORD_SORTS, Sort.by("id"));
        return ApiResponse.page(service.search(
                new ReconciliationService.Filter(runId, status, reviewStatus, invoiceId), pageable), record -> record);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One record with FinBridge's side and the payment service's side, field by field")
    public ApiResponse<ReconciliationService.RecordDetail> record(@PathVariable Long id) {
        return ApiResponse.of(service.getRecord(id));
    }

    @PatchMapping("/{id}/review")
    @Operation(summary = "Record a decision on a discrepancy",
            description = "RESOLVED or NEEDS_INVESTIGATION, with a note. Changes only the record: no invoice and "
                    + "no payment is touched. 422 for a MATCHED record.")
    public ApiResponse<ReconciliationService.RecordDetail> review(@AuthenticationPrincipal AuthenticatedUser user,
                                                                  @PathVariable Long id,
                                                                  @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.of(service.review(id, request.reviewStatus(), request.note(), user.id()));
    }
}
