"""Generates docs/database.md from the live MySQL schema in the finbridge-mysql container.

    python scripts/db_reference.py

Tables, columns, keys, indexes, CHECK constraints and the migration history are read from
information_schema and flyway_schema_history, so the document describes the schema that
Flyway actually built, not what somebody remembers. Only the one-line purpose of each table
is kept here. The database password never leaves the container.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTAINER = os.environ.get("FINBRIDGE_MYSQL_CONTAINER", "finbridge-mysql")

PURPOSE = {
    "roles": "The three roles. Filled by the baseline migration; never changed at runtime.",
    "users": "People who can sign in. Passwords are BCrypt hashes.",
    "user_roles": "Which user has which role.",
    "suppliers": "Vendors copied from Odoo (`res.partner`). `odoo_partner_id` is unique, so a partner can never become two rows.",
    "invoices": "Posted vendor bills copied from Odoo (`account.move`). `odoo_move_id` is unique: the last guard against duplicate synchronization.",
    "payment_transactions": "One row per payment of an invoice, created before anything is sent. `idempotency_key` and `transaction_reference` are unique.",
    "integration_logs": "One row per call to Odoo, the SOAP payment service or the simulator's REST endpoint, with masked payloads. Written once, never updated.",
    "sync_runs": "One row per Odoo synchronization run: its cursor window, counters and outcome.",
    "synchronization_history": "What one synchronization run did with one Odoo record.",
    "reconciliation_runs": "One row per reconciliation run: its period, counters and outcome.",
    "reconciliation_records": "One finding of one run, with both sides as they were compared and the review decision.",
    "integration_configurations": "Runtime settings an administrator changed. Non-secret values only.",
}
ORDER = list(PURPOSE)


def query(sql):
    command = ["docker", "exec", CONTAINER, "sh", "-c",
               'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B --default-character-set=utf8mb4 "$MYSQL_DATABASE" -e "$0"', sql]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        sys.exit("Could not query MySQL in container %s: %s" % (CONTAINER, result.stderr.strip()[-300:]))
    return [line.split("\t") for line in result.stdout.splitlines() if line.strip()]


def main():
    version = query("SELECT VERSION()")[0][0]
    tables = [row[0] for row in query(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
        "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' ORDER BY table_name")]
    unknown = [t for t in tables if t not in PURPOSE]
    gone = [t for t in PURPOSE if t not in tables]
    if unknown or gone:
        sys.exit("scripts/db_reference.py is out of date. New tables: %s. Missing tables: %s" % (unknown, gone))

    columns = {}
    for table, name, ctype, nullable, default, key, extra in query(
            "SELECT table_name, column_name, column_type, is_nullable, IFNULL(column_default, ''), column_key, extra "
            "FROM information_schema.columns WHERE table_schema = DATABASE() ORDER BY table_name, ordinal_position"):
        columns.setdefault(table, []).append((name, ctype, nullable, default, key, extra))

    indexes = {}
    for table, index, unique, cols in query(
            "SELECT table_name, index_name, IF(non_unique = 0, 'unique', ''), GROUP_CONCAT(column_name ORDER BY seq_in_index) "
            "FROM information_schema.statistics WHERE table_schema = DATABASE() GROUP BY table_name, index_name, non_unique "
            "ORDER BY table_name, index_name"):
        indexes.setdefault(table, []).append((index, unique, cols))

    foreign = query(
        "SELECT k.table_name, k.column_name, k.referenced_table_name, k.referenced_column_name, r.delete_rule "
        "FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r "
        "ON r.constraint_schema = k.constraint_schema AND r.constraint_name = k.constraint_name "
        "WHERE k.table_schema = DATABASE() AND k.referenced_table_name IS NOT NULL "
        "ORDER BY k.table_name, k.column_name")

    checks = {}
    for table, name, clause in query(
            "SELECT t.table_name, c.constraint_name, c.check_clause FROM information_schema.check_constraints c "
            "JOIN information_schema.table_constraints t ON t.constraint_schema = c.constraint_schema "
            "AND t.constraint_name = c.constraint_name WHERE c.constraint_schema = DATABASE() "
            "ORDER BY t.table_name, c.constraint_name"):
        checks.setdefault(table, []).append((name, clause))

    history = query("SELECT IFNULL(version, 'repeatable'), description, type, success FROM flyway_schema_history "
                    "ORDER BY installed_rank")

    out = ["# FinBridge database", "",
           "Generated by `scripts/db_reference.py` from the live schema (MySQL %s, %d tables, %d foreign keys). "
           "The schema is owned by Flyway; Hibernate only validates it (`ddl-auto: validate`)." % (
               version, len(tables), len(foreign)), "",
           "## Conventions", "",
           "- InnoDB, `utf8mb4`.",
           "- Money is `DECIMAL(19,4)`, never a floating-point type.",
           "- Timestamps are `DATETIME(3)` holding UTC.",
           "- Status columns are `VARCHAR`, guarded twice: by a Java enum and by a `CHECK` constraint.",
           "- `version` columns are optimistic locks: a concurrent change is refused with 409, not silently overwritten.",
           "- Secrets are never stored: no API key, no JWT secret, no plain password.", "",
           "## Migrations", "", "| Version | Description | Type | Applied |", "| --- | --- | --- | --- |"]
    for version_no, description, kind, success in history:
        out.append("| %s | %s | %s | %s |" % (version_no, description, kind, "yes" if success == "1" else "FAILED"))
    out += ["",
            "`V1__baseline.sql` creates the twelve tables. `V2__reconciliation_details.sql` adds the run's error "
            "message and the two compared snapshots. `V3__credentials_changed_at.sql` lets a password reset end "
            "existing sessions. A repeatable migration with demo rows is loaded only under the `dev` profile "
            "(`db/dev/R__dev_seed_data.sql`); with the `prod` profile it does not exist, and the backend refuses to "
            "start on a database that already contains it.", "",
            "## Entity relationships", "", "```mermaid", "erDiagram"]
    for table, column, ref_table, ref_column, _ in foreign:
        optional = any(c[0] == column and c[2] == "YES" for c in columns[table])
        left = "|o" if optional else "||"
        out.append("    %s %s--o{ %s : \"%s\"" % (ref_table, left, table, column))
    out += ["```", "",
            "Every line is a foreign key that exists in the database. `|o` means the referencing column may be null."
            "", ""]

    for table in ORDER:
        out += ["## %s" % table, "", PURPOSE[table], "",
                "| Column | Type | Null | Default | Notes |", "| --- | --- | --- | --- | --- |"]
        refs = {c: (rt, rc, rule) for t, c, rt, rc, rule in foreign if t == table}
        for name, ctype, nullable, default, key, extra in columns[table]:
            notes = []
            if key == "PRI":
                notes.append("primary key")
            if key == "UNI":
                notes.append("unique")
            if "auto_increment" in extra:
                notes.append("auto increment")
            if name in refs:
                rt, rc, rule = refs[name]
                notes.append("references `%s.%s`%s" % (rt, rc, ", delete cascades" if rule == "CASCADE" else ""))
            out.append("| `%s` | `%s` | %s | %s | %s |" % (name, ctype, "yes" if nullable == "YES" else "no",
                                                         ("`%s`" % default) if default else "", ", ".join(notes)))
        secondary = [i for i in indexes.get(table, []) if i[0] != "PRIMARY"]
        if secondary:
            out += ["", "Indexes: " + "; ".join("`%s` (%s%s)" % (name, cols.replace(",", ", "), ", unique" if unique else "")
                                               for name, unique, cols in secondary) + "."]
        if table in checks:
            out += ["", "Checks:"] + ["- `%s`: `%s`" % (name, clause.replace("`", "")) for name, clause in checks[table]]
        out.append("")

    open(os.path.join(ROOT, "docs", "database.md"), "w", encoding="utf-8", newline="\n").write("\n".join(out))
    print("wrote docs/database.md: %d tables, %d foreign keys, %d migrations" % (len(tables), len(foreign), len(history)))


if __name__ == "__main__":
    main()
