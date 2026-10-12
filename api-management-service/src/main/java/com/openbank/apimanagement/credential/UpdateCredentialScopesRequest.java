package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.scope.ValidScopes;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateCredentialScopesRequest(

        @NotNull(message = "scopes is required")
        @ValidScopes
        List<String> scopes
) {
}
