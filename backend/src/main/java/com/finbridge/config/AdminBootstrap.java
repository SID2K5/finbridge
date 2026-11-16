package com.finbridge.config;

import com.finbridge.user.Role;
import com.finbridge.user.RoleName;
import com.finbridge.user.RoleRepository;
import com.finbridge.user.User;
import com.finbridge.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates the first ADMIN from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD when the
 * system has no enabled admin. No credential is ever stored in a migration or in source code.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(AppProperties properties, UserRepository userRepository,
                          RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.countEnabledByRole(RoleName.ADMIN) > 0) {
            return;
        }
        AppProperties.Bootstrap bootstrap = properties.bootstrap();
        if (bootstrap == null || !StringUtils.hasText(bootstrap.adminEmail())
                || !StringUtils.hasText(bootstrap.adminPassword())) {
            log.warn("No enabled ADMIN exists and BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD "
                    + "are not set. Nobody can log in until they are provided.");
            return;
        }
        if (bootstrap.adminPassword().length() < 12) {
            log.warn("BOOTSTRAP_ADMIN_PASSWORD is shorter than 12 characters; first admin was NOT created.");
            return;
        }
        String email = bootstrap.adminEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            log.warn("Bootstrap admin {} already exists but is not an enabled ADMIN; leaving it unchanged.", email);
            return;
        }
        Role adminRole = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role is missing; check Flyway migration V1"));
        String fullName = StringUtils.hasText(bootstrap.adminFullName())
                ? bootstrap.adminFullName().trim() : "System Administrator";
        User admin = new User(email, fullName, passwordEncoder.encode(bootstrap.adminPassword()));
        admin.getRoles().add(adminRole);
        userRepository.save(admin);
        log.info("Created first ADMIN user {}. Change its password after first login.", email);
    }
}
