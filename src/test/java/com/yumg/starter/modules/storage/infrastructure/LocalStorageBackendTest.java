package com.yumg.starter.modules.storage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumg.starter.modules.storage.application.StorageNamespace;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageBackendTest {

    @TempDir Path root;

    @Test
    void atomicallyWritesReadsAndDeletesAnObjectUnderTheConfiguredRoot() {
        LocalStorageBackend storage = new LocalStorageBackend(root);
        byte[] content = "safe-avatar".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        StoredFile written = storage.write(StorageNamespace.AVATAR_UPLOADED, "png", content);

        assertThat(written.objectKey()).startsWith("objects/avatar-uploaded/").endsWith(".png");
        assertThat(written.sizeBytes()).isEqualTo(content.length);
        assertThat(written.sha256()).hasSize(64);
        assertThat(storage.read(written.objectKey())).containsExactly(content);
        assertThat(storage.delete(written.objectKey())).isTrue();
        assertThat(storage.delete(written.objectKey())).isFalse();
    }

    @Test
    void rejectsKeysThatEscapeTheStorageRoot() {
        LocalStorageBackend storage = new LocalStorageBackend(root);

        assertThatThrownBy(() -> storage.read("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read(root.resolve("absolute").toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void healthProbeExercisesTheTemporaryDirectoryWithoutLeavingFiles() {
        LocalStorageBackend storage = new LocalStorageBackend(root);

        var result = storage.checkHealth();

        assertThat(result.up()).isTrue();
        assertThat(result.message()).contains("writable");
        assertThat(storage.temporaryFileCount()).isZero();
    }
}
