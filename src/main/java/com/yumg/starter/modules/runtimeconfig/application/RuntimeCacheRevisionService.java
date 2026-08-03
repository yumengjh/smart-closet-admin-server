package com.yumg.starter.modules.runtimeconfig.application;

import com.yumg.starter.entities.RuntimeCacheRevision;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeCacheRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeCacheRevisionService {
    private final RuntimeCacheRevisionRepository revisions;

    public RuntimeCacheRevisionService(RuntimeCacheRevisionRepository revisions) {
        this.revisions = revisions;
    }

    @Transactional
    public long ensure(String cacheCode) {
        return revisions.findByCacheCode(cacheCode)
                .orElseGet(() -> revisions.save(new RuntimeCacheRevision(cacheCode)))
                .getRevision();
    }

    @Transactional(readOnly = true)
    public long current(String cacheCode) {
        return revisions.findByCacheCode(cacheCode)
                .map(RuntimeCacheRevision::getRevision)
                .orElse(0L);
    }

    @Transactional
    public long increment(String cacheCode) {
        RuntimeCacheRevision revision = revisions.findByCacheCode(cacheCode)
                .orElseGet(() -> revisions.save(new RuntimeCacheRevision(cacheCode)));
        return revision.increment();
    }
}
