package com.finbridge.user;

import com.finbridge.exception.BusinessRuleException;
import com.finbridge.exception.ConflictException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.user.dto.CreateUserRequest;
import com.finbridge.user.dto.UpdateUserRequest;
import com.finbridge.user.dto.UserResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("A user with this email already exists");
        }
        User user = new User(email, request.fullName().trim(), passwordEncoder.encode(request.password()));
        user.getRoles().addAll(resolveRoles(request.roles()));
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> search(String q, RoleName role, Boolean enabled, Pageable pageable) {
        List<Specification<User>> filters = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filters.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.<String>get("email")), pattern, '!'),
                    cb.like(cb.lower(root.<String>get("fullName")), pattern, '!')));
        }
        if (role != null) {
            filters.add((root, query, cb) -> {
                query.distinct(true);
                return cb.equal(root.join("roles").get("name"), role);
            });
        }
        if (enabled != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("enabled"), enabled));
        }
        return userRepository.findAll(Specification.allOf(filters), pageable).map(UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return UserResponse.from(load(id));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = load(id);
        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            if (!request.enabled()) {
                assertNotLastEnabledAdmin(user, "The last enabled administrator cannot be disabled");
            }
            user.setEnabled(request.enabled());
        }
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse replaceRoles(Long id, Set<RoleName> roleNames) {
        User user = load(id);
        if (!roleNames.contains(RoleName.ADMIN)) {
            assertNotLastEnabledAdmin(user, "The last enabled administrator cannot lose the ADMIN role");
        }
        user.getRoles().clear();
        user.getRoles().addAll(resolveRoles(roleNames));
        return UserResponse.from(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        User user = load(id);
        // Also revokes every access token issued before this moment (see JwtAuthenticationFilter).
        user.changePassword(passwordEncoder.encode(newPassword), java.time.Instant.now());
        user.clearLock();
    }

    @Transactional(readOnly = true)
    public List<String> listRoleNames() {
        return roleRepository.findAll().stream().map(role -> role.getName().name()).sorted().toList();
    }

    private void assertNotLastEnabledAdmin(User user, String message) {
        boolean isEnabledAdmin = user.isEnabled() && user.hasRole(RoleName.ADMIN);
        if (isEnabledAdmin && userRepository.countEnabledByRole(RoleName.ADMIN) <= 1) {
            throw new BusinessRuleException(message);
        }
    }

    private Set<Role> resolveRoles(Set<RoleName> roleNames) {
        return new HashSet<>(roleRepository.findByNameIn(roleNames));
    }

    private User load(Long id) {
        return userRepository.findWithRolesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " was not found"));
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
