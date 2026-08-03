package com.yumg.starter.entities;

import com.yumg.starter.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "runtime_cache_revisions")
public class RuntimeCacheRevision extends AuditedEntity {
    @Column(name = "cache_code", nullable = false, unique = true, length = 100)
    private String cacheCode;

    @Column(name = "revision", nullable = false)
    private long revision;

    protected RuntimeCacheRevision() {}

    public RuntimeCacheRevision(String cacheCode) {
        this.cacheCode = cacheCode;
    }

    public String getCacheCode() { return cacheCode; }
    public long getRevision() { return revision; }
    public long increment() { return ++revision; }
}
