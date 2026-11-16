package com.finbridge.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbridge.invoice.Invoice;
import com.finbridge.invoice.InvoiceRepository;
import com.finbridge.security.JwtService;
import com.finbridge.supplier.Supplier;
import com.finbridge.supplier.SupplierRepository;
import com.finbridge.user.RoleName;
import com.finbridge.user.RoleRepository;
import com.finbridge.user.User;
import com.finbridge.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base class for integration tests (*IT). Starts ONE real MySQL 8.4 container for the whole
 * test run, lets Flyway build the schema in it, and boots the full application against it.
 * Nothing is mocked: security filters, validation, JPA and the database are all real.
 *
 * Tests share the database, so each test creates its own uniquely named data instead of
 * assuming empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "Correct-Horse-9!";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicLong SEQUENCE = new AtomicLong(1000);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected RoleRepository roleRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected SupplierRepository supplierRepository;
    @Autowired
    protected InvoiceRepository invoiceRepository;

    protected static long nextNumber() {
        return SEQUENCE.incrementAndGet();
    }

    protected User createUser(RoleName... roles) {
        User user = new User("user" + nextNumber() + "@test.example", "Test User", passwordEncoder.encode(PASSWORD));
        user.getRoles().addAll(roleRepository.findByNameIn(List.of(roles)));
        return userRepository.save(user);
    }

    /** An Authorization header value for the given user, signed with the real JwtService. */
    protected String bearer(User user) {
        return "Bearer " + jwtService.issueToken(user.getId(), user.getEmail(), List.of());
    }

    protected Supplier createSupplier(String name) {
        return supplierRepository.save(new Supplier(nextNumber(), name, Instant.parse("2026-10-01T09:00:00Z")));
    }

    protected Invoice createInvoice(Supplier supplier, String invoiceNumber, LocalDate invoiceDate, String total) {
        BigDecimal totalAmount = new BigDecimal(total);
        Invoice invoice = new Invoice(nextNumber(), invoiceNumber, supplier, invoiceDate,
                totalAmount, BigDecimal.ZERO, totalAmount, "INR",
                Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T10:05:00Z"));
        return invoiceRepository.save(invoice);
    }
}
