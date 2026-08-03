package com.yumg.starter.modules.storage.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.yumg.starter.entities.StorageObject;
import com.yumg.starter.modules.storage.infrastructure.LocalStorageBackend;
import com.yumg.starter.modules.storage.infrastructure.StorageObjectRepository;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {

    @TempDir Path root;

    @Test
    void storesOpaqueMetadataAndReturnsTheOriginalContent() {
        Map<String, StorageObject> objects = new HashMap<>();
        StorageObjectRepository repository = inMemoryRepository(objects);
        LocalStorageBackend backend = new LocalStorageBackend(root);
        StorageService storage = new StorageService(repository, backend);
        byte[] bytes = "avatar".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        StoredObjectRef reference = storage.put(
                StorageNamespace.AVATAR_GENERATED, "image/svg+xml", "svg", bytes);
        StoredObjectContent content = storage.open(reference.id());

        assertThat(reference.id()).isNotBlank();
        assertThat(objects.get(reference.id()).getObjectKey())
                .startsWith("objects/avatar-generated/");
        assertThat(content.contentType()).isEqualTo("image/svg+xml");
        assertThat(content.bytes()).containsExactly(bytes);
    }

    private StorageObjectRepository inMemoryRepository(Map<String, StorageObject> objects) {
        return (StorageObjectRepository) Proxy.newProxyInstance(
                StorageObjectRepository.class.getClassLoader(),
                new Class<?>[]{StorageObjectRepository.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "save" -> {
                            StorageObject object = (StorageObject) arguments[0];
                            objects.put(object.getId(), object);
                            yield object;
                        }
                        case "findById" -> Optional.ofNullable(objects.get(arguments[0]));
                        case "delete" -> { objects.remove(((StorageObject) arguments[0]).getId()); yield null; }
                        case "toString" -> "InMemoryStorageObjectRepository";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
