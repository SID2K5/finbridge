package com.finbridge.supplier;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    Optional<Supplier> findByOdooPartnerId(Long odooPartnerId);

    List<Supplier> findByOdooPartnerIdIn(Collection<Long> odooPartnerIds);
}
