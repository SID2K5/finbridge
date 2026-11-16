package com.finbridge.simulator.rest;

import com.finbridge.simulator.domain.PaymentProcessor;
import com.finbridge.simulator.domain.Scenario;
import com.finbridge.simulator.domain.ScenarioRepository;
import com.finbridge.simulator.domain.ScenarioSetting;
import com.finbridge.simulator.domain.SimPayment;
import com.finbridge.simulator.domain.SimPaymentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controls of the simulator. Every call needs the X-Sim-Api-Key header.
 *
 * Invoice references contain slashes (BILL/2026/11/0001), so the scenario paths take the
 * whole rest of the URL as the reference: PUT /sim/scenarios/BILL/2026/11/0001
 */
@RestController
@RequestMapping("/sim")
public class SimController {

    private static final Instant STARTED = Instant.now();

    public record ScenarioRequest(@NotNull Scenario scenario) {
    }

    public record ScenarioResponse(String invoiceReference, Scenario scenario) {
    }

    public record OrphanRequest(
            @NotBlank @Size(max = 64) String invoiceReference,
            @NotBlank @Size(max = 64) String supplierReference,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record PaymentView(String externalTransactionId, String transactionReference, String invoiceReference,
                              String supplierReference, BigDecimal requestedAmount, BigDecimal processedAmount,
                              String currency, String status, String failureReason, Scenario scenario,
                              int submissions, int inquiryCount, boolean orphan, Instant responseTimestamp) {

        static PaymentView from(SimPayment payment) {
            return new PaymentView(payment.getExternalTransactionId(), payment.getTransactionReference(),
                    payment.getInvoiceReference(), payment.getSupplierReference(), payment.getRequestedAmount(),
                    payment.getProcessedAmount(), payment.getCurrency(), payment.getStatus(),
                    payment.getFailureReason(), payment.getScenario(), payment.getSubmissions(),
                    payment.getInquiryCount(), payment.isOrphan(), payment.getResponseTimestamp());
        }
    }

    private final PaymentProcessor processor;
    private final ScenarioRepository scenarios;
    private final SimPaymentRepository payments;

    public SimController(PaymentProcessor processor, ScenarioRepository scenarios, SimPaymentRepository payments) {
        this.processor = processor;
        this.scenarios = scenarios;
        this.payments = payments;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "startedAt", STARTED, "payments", payments.count(),
                "scenarios", scenarios.count());
    }

    @GetMapping("/scenarios")
    public List<ScenarioResponse> scenarios() {
        return scenarios.findAll().stream()
                .map(setting -> new ScenarioResponse(setting.getInvoiceReference(), setting.getScenario())).toList();
    }

    @PutMapping("/scenarios/{*invoiceReference}")
    @Transactional
    public ScenarioResponse setScenario(@PathVariable String invoiceReference,
                                        @Valid @RequestBody ScenarioRequest request) {
        String reference = reference(invoiceReference);
        ScenarioSetting setting = scenarios.findById(reference).orElseGet(
                () -> new ScenarioSetting(reference, request.scenario()));
        setting.setScenario(request.scenario());
        scenarios.save(setting);
        return new ScenarioResponse(reference, request.scenario());
    }

    /** Back to the default (SUCCESS) for this invoice reference. */
    @DeleteMapping("/scenarios/{*invoiceReference}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void clearScenario(@PathVariable String invoiceReference) {
        String reference = reference(invoiceReference);
        if (scenarios.existsById(reference)) {
            scenarios.deleteById(reference);
        }
    }

    /** A settlement with no FinBridge counterpart, for the MISSING_INTERNAL_RECORD case. */
    @PostMapping("/orphans")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView createOrphan(@Valid @RequestBody OrphanRequest request) {
        return PaymentView.from(processor.createOrphan(request.invoiceReference(), request.supplierReference(),
                request.amount(), request.currency()));
    }

    /** What the simulator has recorded; with invoiceReference, only that invoice's payments. */
    @GetMapping("/payments")
    public List<PaymentView> payments(@RequestParam(required = false) String invoiceReference) {
        List<SimPayment> found = invoiceReference == null
                ? payments.findAll()
                : payments.findByInvoiceReferenceOrderById(invoiceReference);
        return found.stream().map(PaymentView::from).toList();
    }

    private static String reference(String pathRemainder) {
        String reference = pathRemainder.startsWith("/") ? pathRemainder.substring(1) : pathRemainder;
        if (reference.isBlank() || reference.length() > 64) {
            throw new IllegalArgumentException("invoiceReference must be 1 to 64 characters");
        }
        return reference;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex) {
        String message = ex instanceof IllegalArgumentException ? ex.getMessage()
                : "The request body is invalid. scenario must be one of " + List.of(Scenario.values());
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "INVALID_REQUEST", "message", message)));
    }
}
