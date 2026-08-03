package com.yumg.starter.entities;

import com.yumg.starter.common.entity.AuditedEntity;
import com.yumg.starter.modules.storage.application.StorageNamespace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "storage_objects")
public class StorageObject extends AuditedEntity {
    @Column(name = "backend_code", nullable = false, length = 32)
    private String backendCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private StorageNamespace namespace;
    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;
    @Column(name = "content_type", nullable = false, length = 160)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StorageObjectState state;

    protected StorageObject() {}

    public StorageObject(StorageNamespace namespace, String objectKey, String contentType,
                         long sizeBytes, String sha256) {
        ensureId();
        this.backendCode = "LOCAL";
        this.namespace = namespace;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.state = StorageObjectState.READY;
    }

    public String getBackendCode() { return backendCode; }
    public StorageNamespace getNamespace() { return namespace; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public StorageObjectState getState() { return state; }
    public void markDeletePending() { state = StorageObjectState.DELETE_PENDING; }
    public void markMissing() { state = StorageObjectState.MISSING; }
}
