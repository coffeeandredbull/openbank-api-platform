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
@RequestMapping("/apis")
public class ApiController {

    private final ApiService apiService;

    public ApiController(ApiService apiService) {
        this.apiService = apiService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> create(@Valid @RequestBody CreateApiRequest request) {
        ApiResponse created = apiService.create(request);
        return ResponseEntity.created(URI.create("/apis/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ApiResponse get(@PathVariable Long id) {
        return apiService.get(id);
    }

    @GetMapping
    public List<ApiResponse> list() {
        return apiService.list();
    }
}