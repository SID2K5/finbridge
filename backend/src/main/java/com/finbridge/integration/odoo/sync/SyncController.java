package com.finbridge.integration.odoo.sync;

import com.finbridge.common.ApiResponse;
import com.finbridge.common.PageQuery;
import com.finbridge.integration.odoo.history.SyncHistoryResponse;
import com.finbridge.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('ADMIN', 'INTEGRATION_OPERATOR')")
@Tag(name = "Odoo synchronization", description = "Start a run and inspect what each run did")
public class SyncController {

    private static final Map<String, String> RUN_SORTS = Map.of(
            "id", "id",
            "startedAt", "startedAt",
            "status", "status");

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /** Optional body. {"fullResync": true} ignores the cursor and reads everything again. */
    public record StartRequest(Boolean fullResync) {
    }

    @PostMapping({"/api/integrations/odoo/sync", "/api/invoices/sync"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start a synchronization run",
            description = "Returns 202 with the run; poll GET /api/integrations/odoo/sync-runs/{id} until its "
                    + "status is no longer RUNNING. 409 if a run is already in progress.")
    public ApiResponse<SyncRunResponse> start(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestBody(required = false) StartRequest request) {
        boolean fullResync = request != null && Boolean.TRUE.equals(request.fullResync());
        return ApiResponse.of(syncService.start(user.id(), fullResync));
    }

    @GetMapping("/api/integrations/odoo/sync-runs")
    public ApiResponse<List<SyncRunResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageQuery.of(page, size, sort, RUN_SORTS, Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.page(syncService.list(pageable), run -> run);
    }

    /** The run plus one page of its per-record history. "meta" describes the history paging. */
    public record RunDetail(SyncRunResponse run, List<SyncHistoryResponse> history) {
    }

    @GetMapping("/api/integrations/odoo/sync-runs/{id}")
    public ApiResponse<RunDetail> get(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        SyncRunResponse run = syncService.get(id);
        Pageable pageable = PageQuery.of(page, size, null, Map.of(), Sort.by("id"));
        Page<SyncHistoryResponse> history = syncService.history(id, pageable);
        ApiResponse.PageMeta meta = new ApiResponse.PageMeta(history.getNumber(), history.getSize(),
                history.getTotalElements(), history.getTotalPages());
        return ApiResponse.of(new RunDetail(run, history.getContent()), meta);
    }
}
