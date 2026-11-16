package com.finbridge.reconciliation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    Optional<ReconciliationRun> findFirstByOrderByIdDesc();
}
