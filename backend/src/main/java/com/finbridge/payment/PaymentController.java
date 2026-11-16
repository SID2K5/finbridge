package com.finbridge.payment;

import com.finbridge.common.ApiResponse;
import com.finbridge.common.PageQuery;
import com.finbridge.payment.dto.PaymentDetailResponse;
import com.finbridge.payment.dto.PaymentResponse;
import com.finbridge.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Pay an invoice through the SOAP payment service and follow the transaction")
public class PaymentController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replay";

    private static final String OPERATORS = "hasAnyRole('ADMIN', 'INTEGRATION_OPERATOR')";

    private static final Map<String, String> SORTS = Map.of(
            "requestedAt", "requestedAt",
            "status", "status",
            "requestedAmount", "requestedAmount",
            "transactionReference", "transactionReference");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "id");

    private final PaymentService paymentService;
    private final PaymentQueryService queryService;

    public PaymentController(PaymentService paymentService, PaymentQueryService queryService) {
        this.paymentService = paymentService;
        this.queryService = queryService;
    }

    /** The amount and currency are not in the request: they always come from the invoice. */
    public record CreatePaymentRequest(@NotNull(message = "invoiceId is required") Long invoiceId) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE_MANAGER')")
    @Operation(summary = "Pay an invoice in full",
            description = "Needs an Idempotency-Key header (generate one UUID per payment intention). "
                    + "201 with the transaction in whatever state the payment service left it, including FAILED "
                    + "and TIMEOUT_UNKNOWN. Sending the same key and body again returns the same transaction "
                    + "(header Idempotent-Replay: true) and sends nothing. 409 if the invoice already has a "
                    + "successful or in-progress payment, or the key was used for another invoice.")
    public ResponseEntity<ApiResponse<PaymentResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentService.CreateResult result = paymentService.create(request.invoiceId(), idempotencyKey, user.id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(REPLAY_HEADER, String.valueOf(result.replayed()))
                .body(ApiResponse.of(result.payment()));
    }

    @GetMapping
    public ApiResponse<List<PaymentResponse>> list(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PaymentQueryService.Filter filter =
                new PaymentQueryService.Filter(status, invoiceId, supplierId, from, to, reference);
        Pageable pageable = PageQuery.of(page, size, sort, SORTS, DEFAULT_SORT);
        return ApiResponse.page(queryService.search(filter, pageable), payment -> payment);
    }

    @GetMapping("/{id}")
    @Operation(summary = "The transaction with every call made to the payment service for it")
    public ApiResponse<PaymentDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.of(queryService.detail(id));
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "The stored status only; makes no outbound call")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long id) {
        PaymentResponse payment = queryService.get(id);
        return ApiResponse.of(Map.of("id", payment.id(), "transactionReference", payment.transactionReference(),
                "status", payment.status()));
    }

    @PostMapping("/{id}/status-check")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Ask the payment service what happened (PENDING and TIMEOUT_UNKNOWN only)",
            description = "A POST because it can change the transaction. 422 in any other status; 502 or 504 "
                    + "if the inquiry itself fails, in which case nothing changes.")
    public ApiResponse<PaymentResponse> statusCheck(@PathVariable Long id) {
        return ApiResponse.of(paymentService.statusCheck(id));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize(OPERATORS)
    @Operation(summary = "Send a FAILED payment again, or resolve a TIMEOUT_UNKNOWN one by inquiry",
            description = "FAILED: a new attempt, up to the attempt limit. TIMEOUT_UNKNOWN: a status inquiry "
                    + "first; the request is sent again only if the payment service never received it.")
    public ApiResponse<PaymentResponse> retry(@PathVariable Long id) {
        return ApiResponse.of(paymentService.retry(id));
    }
}
