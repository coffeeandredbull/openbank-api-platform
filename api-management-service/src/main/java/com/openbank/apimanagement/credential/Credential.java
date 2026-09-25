package com.openbank.apimanagement.credential;

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
        name = "credentials",
        uniqueConstraints = @UniqueConstraint(
                name = "uc_credential_client_id",
                columnNames = "client_id"
        )
)
public class Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private Application application;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, length = 255)
    private String clientSecretHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Credential() {
    }

    public Credential(Application application, String clientId, String clientSecretHash) {
        this(application, clientId, clientSecretHash, null);
    }

    public Credential(Application application, String clientId, String clientSecretHash, Instant expiresAt) {
        this.application = application;
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.expiresAt = expiresAt;
        this.status = CredentialStatus.ACTIVE;
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

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public CredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeStatus(CredentialStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void rotate(String newClientId, String newClientSecretHash) {
        this.clientId = newClientId;
        this.clientSecretHash = newClientSecretHash;
        this.updatedAt = Instant.now();
    }
}