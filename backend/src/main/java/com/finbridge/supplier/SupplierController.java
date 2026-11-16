package com.finbridge.supplier;

import com.finbridge.common.ApiResponse;
import com.finbridge.common.PageQuery;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suppliers")
@Tag(name = "Suppliers", description = "Read-only. Suppliers are created by Odoo synchronization")
public class SupplierController {

    private static final Map<String, String> SORTS = Map.of(
            "name", "name",
            "odooPartnerId", "odooPartnerId",
            "updatedAt", "updatedAt");

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    public ApiResponse<List<SupplierResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageQuery.of(page, size, sort, SORTS, Sort.by("name"));
        return ApiResponse.page(supplierService.search(q, pageable), supplier -> supplier);
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierResponse> get(@PathVariable Long id) {
        return ApiResponse.of(supplierService.get(id));
    }
}
