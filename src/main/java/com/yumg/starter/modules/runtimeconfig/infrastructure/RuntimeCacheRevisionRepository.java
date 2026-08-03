package com.yumg.starter.modules.runtimeconfig.infrastructure;

import com.yumg.starter.entities.RuntimeCacheRevision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeCacheRevisionRepository extends JpaRepository<RuntimeCacheRevision, String> {
    Optional<RuntimeCacheRevision> findByCacheCode(String cacheCode);
}
