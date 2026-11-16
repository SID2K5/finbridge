package com.finbridge.user;

import com.finbridge.common.ApiResponse;
import com.finbridge.common.PageQuery;
import com.finbridge.user.dto.ResetPasswordRequest;
import com.finbridge.user.dto.UpdateRolesRequest;
import com.finbridge.user.dto.UpdateUserRequest;
import com.finbridge.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users and roles", description = "ADMIN only")
public class UserController {

    private static final Map<String, String> SORTS = Map.of(
            "email", "email",
            "fullName", "fullName",
            "createdAt", "createdAt",
            "lastLoginAt", "lastLoginAt");

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) RoleName role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageQuery.of(page, size, sort, SORTS, Sort.by("email"));
        return ApiResponse.page(userService.search(q, role, enabled, pageable), user -> user);
    }

    @GetMapping("/users/{id}")
    public ApiResponse<UserResponse> get(@PathVariable Long id) {
        return ApiResponse.of(userService.get(id));
    }

    @PatchMapping("/users/{id}")
    public ApiResponse<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.of(userService.update(id, request));
    }

    @PutMapping("/users/{id}/roles")
    public ApiResponse<UserResponse> replaceRoles(@PathVariable Long id, @Valid @RequestBody UpdateRolesRequest request) {
        return ApiResponse.of(userService.replaceRoles(id, request.roles()));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles")
    public ApiResponse<List<String>> roles() {
        return ApiResponse.of(userService.listRoleNames());
    }
}
