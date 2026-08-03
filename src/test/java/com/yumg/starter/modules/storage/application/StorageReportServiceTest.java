package com.yumg.starter.modules.storage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumg.starter.entities.StorageObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StorageReportServiceTest {
    @Test
    void classifiesUserCandidateOrphanAndMissingObjectsWithoutDoubleCounting() {
        StorageObject user = object(StorageNamespace.AVATAR_UPLOADED, "objects/avatar-uploaded/a.png", 100);
        StorageObject candidate = object(StorageNamespace.AVATAR_CANDIDATE, "objects/avatar-candidate/b.svg", 40);
        StorageObject orphan = object(StorageNamespace.AVATAR_GENERATED, "objects/avatar-generated/c.svg", 30);

        StorageReportSummary result = StorageReportService.summarize(
                List.of(user, candidate, orphan), Set.of(user.getId()), Set.of(candidate.getId()),
                key -> !key.endsWith("b.svg"));

        assertThat(result.totalObjects()).isEqualTo(3);
        assertThat(result.totalBytes()).isEqualTo(170);
        assertThat(result.userReferencedObjects()).isEqualTo(1);
        assertThat(result.candidateReferencedObjects()).isEqualTo(1);
        assertThat(result.orphanObjects()).isEqualTo(1);
        assertThat(result.missingFileObjects()).isEqualTo(1);
    }

    private StorageObject object(StorageNamespace namespace, String key, long size) {
        return new StorageObject(namespace, key, namespace == StorageNamespace.AVATAR_UPLOADED
                ? "image/png" : "image/svg+xml", size, "a".repeat(64));
    }
}
