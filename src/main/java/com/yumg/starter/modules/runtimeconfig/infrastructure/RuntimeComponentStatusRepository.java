package com.yumg.starter.modules.runtimeconfig.infrastructure;

import com.yumg.starter.entities.RuntimeComponentStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeComponentStatusRepository extends JpaRepository<RuntimeComponentStatus, String> {
    Optional<RuntimeComponentStatus> findByComponentCode(String componentCode);
}
