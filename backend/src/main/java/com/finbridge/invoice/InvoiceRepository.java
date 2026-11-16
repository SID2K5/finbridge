package com.finbridge.invoice;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    /** Loads each invoice's supplier in the same query, avoiding one extra query per row. */
    @Override
    @EntityGraph(attributePaths = "supplier")
    Page<Invoice> findAll(Specification<Invoice> spec, Pageable pageable);

    @EntityGraph(attributePaths = "supplier")
    Optional<Invoice> findWithSupplierById(Long id);

    Optional<Invoice> findByOdooMoveId(Long odooMoveId);

    /** Invoices whose supplier and vendor reference also appear on at least one other invoice. */
    @Query("select i from Invoice i where i.vendorReference is not null and exists ("
            + "select 1 from Invoice j where j.supplier = i.supplier "
            + "and j.vendorReference = i.vendorReference and j.id <> i.id) order by i.id")
    java.util.List<Invoice> findWithSharedVendorReference();

    /**
     * SELECT ... FOR UPDATE on the invoice row. Two requests to pay the same invoice queue up
     * here, so the second one sees the transaction the first one created.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "supplier")
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findForUpdateById(@Param("id") Long id);
}
