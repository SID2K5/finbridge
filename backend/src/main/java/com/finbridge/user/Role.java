package com.finbridge.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Reference data: the three rows are inserted by Flyway migration V1 and never change at runtime. */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    private Short id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "name", nullable = false, unique = true, length = 40)
    private RoleName name;

    protected Role() {
    }

    public Short getId() {
        return id;
    }

    public RoleName getName() {
        return name;
    }
}
