package com.finbridge.integration.odoo.history;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

    boolean existsByStatus(String status);

    List<SyncRun> findByStatus(String status);

    /** The run whose cursor_to is the starting point of the next incremental run. */
    Optional<SyncRun> findFirstByStatusInAndCursorToIsNotNullOrderByIdDesc(Collection<String> statuses);

    Optional<SyncRun> findFirstByOrderByIdDesc();
}
