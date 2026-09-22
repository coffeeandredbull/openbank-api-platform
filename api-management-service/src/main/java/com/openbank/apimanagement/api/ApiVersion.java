package com.openbank.apimanagement.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "api_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_api_versions_api_id_version",
                columnNames = {"api_id", "version"}
        )
)
public class ApiVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_id", nullable = false)
    private Api api;

    @Column(nullable = false, length = 64)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32) not null default 'CREATED'")
    private ApiVersionLifecycle lifecycle;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ApiVersion() {
    }

    public ApiVersion(Api api, String version) {
        this.api = api;
        this.version = version;
        this.lifecycle = ApiVersionLifecycle.CREATED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Api getApi() {
        return api;
    }

    public String getVersion() {
        return version;
    }

    public ApiVersionLifecycle getLifecycle() {
        return lifecycle;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeLifecycle(ApiVersionLifecycle newLifecycle) {
        this.lifecycle = newLifecycle;
        this.updatedAt = Instant.now();
    }
}