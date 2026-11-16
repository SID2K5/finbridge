package com.finbridge.dashboard;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only figures for the dashboard. Every number is computed from the tables at the
 * moment it is asked for; nothing is cached and nothing is invented. When there is no data
 * the answer says so (null or an empty list) instead of a zero that would look real.
 *
 * This is the one module that reads across the others' tables, and it only ever reads.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    public record CurrencyAmount(String currency, BigDecimal amount) {
    }

    public record LastSync(Long runId, String status, Instant startedAt, Instant finishedAt) {
    }

    public record Summary(long invoiceCount, List<CurrencyAmount> invoiceValue, List<CurrencyAmount> openPayable,
                          List<CurrencyAmount> paidAmount, Map<String, Long> invoicesByStatus,
                          Map<String, Long> paymentsByStatus, long paymentCount, long paymentsNeedingAttention,
                          Long openDiscrepancies, Long latestReconciliationRunId, LastSync lastSync) {
    }

    public record StatusCount(String status, long count, BigDecimal requestedAmount) {
    }

    public record ReconciliationSummary(Long runId, String runStatus, LocalDate periodFrom, LocalDate periodTo,
                                        Instant finishedAt, int totalRecords, Map<String, Long> byStatus,
                                        Map<String, Long> byReviewStatus) {
    }

    public record DayCount(LocalDate date, long count) {
    }

    public record Activity(Long id, Instant at, String type, String operation, String outcome, int durationMs,
                           String reference, String errorCode) {
    }

    private final EntityManager entityManager;
    private final Clock clock;

    public DashboardService(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public Summary summary() {
        long invoiceCount = entityManager.createQuery("select count(i) from Invoice i", Long.class)
                .getSingleResult();
        Map<String, Long> invoicesByStatus = counts(
                "select cast(i.invoiceStatus as string), count(i) from Invoice i group by i.invoiceStatus");
        Map<String, Long> paymentsByStatus = counts(
                "select cast(p.status as string), count(p) from PaymentTransaction p group by p.status");
        long paymentCount = paymentsByStatus.values().stream().mapToLong(Long::longValue).sum();
        long needingAttention = paymentsByStatus.getOrDefault("PENDING", 0L)
                + paymentsByStatus.getOrDefault("TIMEOUT_UNKNOWN", 0L) + paymentsByStatus.getOrDefault("FAILED", 0L);

        List<Long> latestRun = entityManager.createQuery(
                "select r.id from ReconciliationRun r where r.status = 'COMPLETED' order by r.id desc", Long.class)
                .setMaxResults(1).getResultList();
        Long latestRunId = latestRun.isEmpty() ? null : latestRun.get(0);
        Long openDiscrepancies = latestRunId == null ? null : entityManager.createQuery(
                        "select count(r) from ReconciliationRecord r where r.runId = :run "
                                + "and cast(r.reviewStatus as string) in ('OPEN', 'NEEDS_INVESTIGATION')", Long.class)
                .setParameter("run", latestRunId).getSingleResult();

        List<Tuple> sync = entityManager.createQuery(
                "select s.id as id, s.status as status, s.startedAt as startedAt, s.finishedAt as finishedAt "
                        + "from SyncRun s order by s.id desc", Tuple.class).setMaxResults(1).getResultList();
        LastSync lastSync = sync.isEmpty() ? null : new LastSync(sync.get(0).get("id", Long.class),
                sync.get(0).get("status", String.class), sync.get(0).get("startedAt", Instant.class),
                sync.get(0).get("finishedAt", Instant.class));

        return new Summary(invoiceCount,
                amounts("select i.currency, sum(i.totalAmount) from Invoice i group by i.currency order by i.currency"),
                amounts("select i.currency, sum(i.totalAmount) from Invoice i "
                        + "where cast(i.invoiceStatus as string) in ('OPEN', 'PAYMENT_FAILED') "
                        + "group by i.currency order by i.currency"),
                amounts("select p.currency, sum(p.processedAmount) from PaymentTransaction p "
                        + "where cast(p.status as string) = 'SUCCESS' group by p.currency order by p.currency"),
                invoicesByStatus, paymentsByStatus, paymentCount, needingAttention, openDiscrepancies, latestRunId,
                lastSync);
    }

    /** Payments per status, optionally limited to those requested in [from, to]. */
    public List<StatusCount> transactions(Instant from, Instant to) {
        StringBuilder jpql = new StringBuilder(
                "select cast(p.status as string), count(p), sum(p.requestedAmount) from PaymentTransaction p where 1 = 1");
        if (from != null) {
            jpql.append(" and p.requestedAt >= :from");
        }
        if (to != null) {
            jpql.append(" and p.requestedAt <= :to");
        }
        jpql.append(" group by p.status order by count(p) desc");
        var query = entityManager.createQuery(jpql.toString(), Object[].class);
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        return query.getResultList().stream()
                .map(row -> new StatusCount((String) row[0], (Long) row[1], (BigDecimal) row[2])).toList();
    }

    /** The latest run (whatever its status) with its record counts; null when no run exists. */
    public ReconciliationSummary reconciliationSummary() {
        List<Object[]> run = entityManager.createQuery(
                "select r.id, r.status, r.periodFrom, r.periodTo, r.finishedAt, r.totalRecords "
                        + "from ReconciliationRun r order by r.id desc", Object[].class)
                .setMaxResults(1).getResultList();
        if (run.isEmpty()) {
            return null;
        }
        Object[] row = run.get(0);
        Long runId = (Long) row[0];
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byReview = new LinkedHashMap<>();
        for (Object[] count : entityManager.createQuery(
                "select cast(r.status as string), cast(r.reviewStatus as string), count(r) "
                        + "from ReconciliationRecord r where r.runId = :run group by r.status, r.reviewStatus",
                Object[].class).setParameter("run", runId).getResultList()) {
            byStatus.merge((String) count[0], (Long) count[2], Long::sum);
            byReview.merge((String) count[1], (Long) count[2], Long::sum);
        }
        return new ReconciliationSummary(runId, (String) row[1], (LocalDate) row[2], (LocalDate) row[3],
                (Instant) row[4], (Integer) row[5], byStatus, byReview);
    }

    /** Invoices first synchronized on each of the last N days (UTC), oldest first, days without any included. */
    public List<DayCount> invoiceTrend(int days) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate first = today.minusDays(days - 1L);
        Map<LocalDate, Long> perDay = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            perDay.put(day, 0L);
        }
        List<Instant> created = entityManager.createQuery(
                        "select i.createdAt from Invoice i where i.createdAt >= :from", Instant.class)
                .setParameter("from", first.atStartOfDay().toInstant(ZoneOffset.UTC)).getResultList();
        for (Instant instant : created) {
            perDay.computeIfPresent(LocalDate.ofInstant(instant, ZoneOffset.UTC), (day, count) -> count + 1);
        }
        List<DayCount> trend = new ArrayList<>(perDay.size());
        perDay.forEach((day, count) -> trend.add(new DayCount(day, count)));
        return trend;
    }

    /** The latest 20 calls to external systems: summary fields only, never a payload. */
    public List<Activity> recentActivity() {
        return entityManager.createQuery(
                        "select l.id, l.createdAt, l.integrationType, l.operation, l.outcome, l.durationMs, "
                                + "l.requestReference, l.errorCode from IntegrationLog l order by l.id desc",
                        Object[].class)
                .setMaxResults(20).getResultList().stream()
                .map(row -> new Activity((Long) row[0], (Instant) row[1], (String) row[2], (String) row[3],
                        (String) row[4], (Integer) row[5], (String) row[6], (String) row[7]))
                .toList();
    }

    private Map<String, Long> counts(String jpql) {
        Map<String, Long> counts = new LinkedHashMap<>();
        entityManager.createQuery(jpql, Object[].class).getResultList()
                .forEach(row -> counts.put((String) row[0], (Long) row[1]));
        return counts;
    }

    private List<CurrencyAmount> amounts(String jpql) {
        return entityManager.createQuery(jpql, Object[].class).getResultList().stream()
                .filter(row -> row[1] != null)
                .map(row -> new CurrencyAmount((String) row[0], (BigDecimal) row[1])).toList();
    }
}
