package com.finbridge.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finbridge.exception.BusinessRuleException;
import com.finbridge.exception.ConflictException;
import com.finbridge.exception.ResourceNotFoundException;
import com.finbridge.user.dto.CreateUserRequest;
import com.finbridge.user.dto.UpdateUserRequest;
import com.finbridge.user.dto.UserResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private UserService userService;
    private User admin;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, roleRepository, passwordEncoder);
        admin = new User("admin@test.example", "Only Admin", "hash");
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.getRoles().add(role(RoleName.ADMIN));
    }

    private static Role role(RoleName name) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "name", name);
        return role;
    }

    @Test
    void theLastEnabledAdminCannotBeDisabled() {
        when(userRepository.findWithRolesById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countEnabledByRole(RoleName.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> userService.update(1L, new UpdateUserRequest(null, false)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(admin.isEnabled()).isTrue();
    }

    @Test
    void theLastEnabledAdminCannotLoseTheAdminRole() {
        when(userRepository.findWithRolesById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countEnabledByRole(RoleName.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> userService.replaceRoles(1L, Set.of(RoleName.FINANCE_MANAGER)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(admin.hasRole(RoleName.ADMIN)).isTrue();
    }

    @Test
    void anAdminCanBeDemotedWhenAnotherEnabledAdminExists() {
        when(userRepository.findWithRolesById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.countEnabledByRole(RoleName.ADMIN)).thenReturn(2L);
        when(roleRepository.findByNameIn(Set.of(RoleName.FINANCE_MANAGER)))
                .thenReturn(List.of(role(RoleName.FINANCE_MANAGER)));

        UserResponse response = userService.replaceRoles(1L, Set.of(RoleName.FINANCE_MANAGER));

        assertThat(response.roles()).containsExactly("FINANCE_MANAGER");
    }

    @Test
    void creatingAUserWithAnExistingEmailIsAConflict() {
        when(userRepository.existsByEmail("taken@test.example")).thenReturn(true);
        CreateUserRequest request = new CreateUserRequest(" Taken@Test.Example ", "Someone",
                "a-long-password-1", Set.of(RoleName.FINANCE_MANAGER));

        assertThatThrownBy(() -> userService.create(request)).isInstanceOf(ConflictException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void passwordIsStoredAsABcryptHashNeverAsPlainText() {
        when(userRepository.existsByEmail("new@test.example")).thenReturn(false);
        when(roleRepository.findByNameIn(Set.of(RoleName.INTEGRATION_OPERATOR)))
                .thenReturn(List.of(role(RoleName.INTEGRATION_OPERATOR)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.create(new CreateUserRequest("new@test.example", "New User",
                "a-long-password-1", Set.of(RoleName.INTEGRATION_OPERATOR)));

        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2").doesNotContain("a-long-password-1");
        assertThat(passwordEncoder.matches("a-long-password-1", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void unknownUserIdIsNotFound() {
        when(userRepository.findWithRolesById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.get(99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
