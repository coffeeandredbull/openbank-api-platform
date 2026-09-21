package com.openbank.apimanagement.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/apis/{apiId}/versions")
public class ApiVersionController {

    private final ApiVersionService apiVersionService;

    public ApiVersionController(ApiVersionService apiVersionService) {
        this.apiVersionService = apiVersionService;
    }

    @PostMapping
    public ResponseEntity<ApiVersionResponse> create(
            @PathVariable Long apiId,
            @Valid @RequestBody CreateApiVersionRequest request) {
        ApiVersionResponse created = apiVersionService.create(apiId, request);
        return ResponseEntity.created(URI.create("/apis/" + apiId + "/versions/" + created.id())).body(created);
    }

    @GetMapping("/{versionId}")
    public ApiVersionResponse get(@PathVariable Long apiId, @PathVariable Long versionId) {
        return apiVersionService.get(apiId, versionId);
    }

    @GetMapping
    public List<ApiVersionResponse> list(@PathVariable Long apiId) {
        return apiVersionService.list(apiId);
    }
}