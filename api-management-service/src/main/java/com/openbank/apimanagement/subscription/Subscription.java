package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.api.ApiVersion;
import com.openbank.apimanagement.application.Application;
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
        name = "subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uc_subscription_application_api_version",
                columnNames = {"application_id", "api_version_id"}
        )
)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_version_id", nullable = false, updatable = false)
    private ApiVersion apiVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tier_id", nullable = false, updatable = false)
    private SubscriptionTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubscriptionStatus status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {
    }

    public Subscription(Application application, ApiVersion apiVersion, SubscriptionTier tier) {
        this.application = application;
        this.apiVersion = apiVersion;
        this.tier = tier;
        this.status = SubscriptionStatus.PENDING;
        this.revokedAt = null;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Application getApplication() {
        return application;
    }

    public ApiVersion getApiVersion() {
        return apiVersion;
    }

    public SubscriptionTier getTier() {
        return tier;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeStatus(SubscriptionStatus newStatus) {
        this.status = newStatus;
        if (newStatus == SubscriptionStatus.REVOKED) {
            this.revokedAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }
}