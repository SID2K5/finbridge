package com.finbridge.invoice;

import com.finbridge.common.ApiResponse;
import com.finbridge.common.PageQuery;
import com.finbridge.integration.odoo.history.SyncHistoryResponse;
import com.finbridge.invoice.dto.InvoiceDetailResponse;
import com.finbridge.invoice.dto.InvoiceSummaryResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
@Tag(name = "Invoices", description = "Read-only. Invoices are created by Odoo synchronization")
public class InvoiceController {

    private static final Map<String, String> SORTS = Map.of(
            "invoiceNumber", "invoiceNumber",
            "invoiceDate", "invoiceDate",
            "dueDate", "dueDate",
            "totalAmount", "totalAmount",
            "status", "invoiceStatus",
            "supplierName", "supplier.name");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "invoiceDate").and(Sort.by("id"));

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public ApiResponse<List<InvoiceSummaryResponse>> list(
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) SyncStatus syncStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        InvoiceFilter filter = new InvoiceFilter(invoiceNumber, supplierId, status, syncStatus, dateFrom, dateTo);
        Pageable pageable = PageQuery.of(page, size, sort, SORTS, DEFAULT_SORT);
        return ApiResponse.page(invoiceService.search(filter, pageable), invoice -> invoice);
    }

    @GetMapping("/{id}")
    public ApiResponse<InvoiceDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.of(invoiceService.get(id));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<SyncHistoryResponse>> history(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageQuery.of(page, size, null, Map.of(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.page(invoiceService.history(id, pageable), entry -> entry);
    }
}
