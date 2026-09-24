package com.openbank.apimanagement.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "subscription_tiers",
        uniqueConstraints = @UniqueConstraint(
                name = "uc_subscription_tier_name",
                columnNames = "name"
        )
)
public class SubscriptionTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "requests_per_window", nullable = false, columnDefinition = "integer not null default 100")
    private int requestsPerWindow;

    @Column(name = "window_seconds", nullable = false, columnDefinition = "integer not null default 60")
    private int windowSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SubscriptionTier() {
    }

    public SubscriptionTier(String name, String description, int requestsPerWindow, int windowSeconds) {
        this.name = name;
        this.description = description;
        this.requestsPerWindow = requestsPerWindow;
        this.windowSeconds = windowSeconds;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getRequestsPerWindow() {
        return requestsPerWindow;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}