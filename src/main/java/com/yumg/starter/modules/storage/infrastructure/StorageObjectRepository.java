package com.yumg.starter.modules.storage.infrastructure;

import com.yumg.starter.entities.StorageObject;
import com.yumg.starter.entities.StorageObjectState;
import com.yumg.starter.modules.storage.application.StorageNamespace;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageObjectRepository extends JpaRepository<StorageObject, String> {
    List<StorageObject> findAllByNamespaceAndState(
            StorageNamespace namespace, StorageObjectState state);
}
