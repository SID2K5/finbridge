package com.finbridge.integration.settings;

import com.finbridge.common.ApiResponse;
import com.finbridge.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Settings", description = "Non-secret integration settings. Secrets are shown only as configured or missing")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public record UpdateRequest(@NotBlank(message = "value is required") String value) {
    }

    @GetMapping
    public ApiResponse<SettingsService.ConfigView> get() {
        return ApiResponse.of(settingsService.view());
    }

    @PutMapping("/{key}")
    @Operation(summary = "Change one setting; it applies from the next sync run or payment retry")
    public ApiResponse<SettingsService.SettingView> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable String key,
                                                          @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.of(settingsService.update(key, request.value(), user.id()));
    }
}
