package com.yumg.starter.modules.storage.application;

import com.yumg.starter.entities.StorageObject;
import com.yumg.starter.entities.StorageObjectState;
import com.yumg.starter.modules.storage.infrastructure.LocalStorageBackend;
import com.yumg.starter.modules.storage.infrastructure.StorageObjectRepository;
import com.yumg.starter.modules.storage.infrastructure.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageService {
    private final StorageObjectRepository objects;
    private final LocalStorageBackend backend;

    public StorageService(StorageObjectRepository objects, LocalStorageBackend backend) {
        this.objects = objects;
        this.backend = backend;
    }

    @Transactional
    public StoredObjectRef put(StorageNamespace namespace, String contentType,
                               String extension, byte[] content) {
        StoredFile file = backend.write(namespace, extension, content);
        try {
            StorageObject object = objects.save(new StorageObject(namespace, file.objectKey(),
                    contentType, file.sizeBytes(), file.sha256()));
            return reference(object);
        } catch (RuntimeException exception) {
            try { backend.delete(file.objectKey()); } catch (RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public StoredObjectContent open(String id) {
        StorageObject object = objects.findById(id).orElseThrow(
                () -> new IllegalArgumentException("storage object not found"));
        if (object.getState() != StorageObjectState.READY) {
            throw new IllegalStateException("storage object is not ready");
        }
        byte[] content = backend.read(object.getObjectKey());
        return new StoredObjectContent(object.getContentType(), object.getSizeBytes(),
                object.getSha256(), content);
    }

    @Transactional
    public void delete(String id) {
        objects.findById(id).ifPresent(object -> {
            try {
                backend.delete(object.getObjectKey());
                objects.delete(object);
            } catch (RuntimeException exception) {
                object.markDeletePending();
                throw exception;
            }
        });
    }

    @Transactional(readOnly = true)
    public StoredObjectRef metadata(String id) {
        return objects.findById(id).map(this::reference).orElseThrow(
                () -> new IllegalArgumentException("storage object not found"));
    }

    public LocalStorageBackend backend() { return backend; }

    private StoredObjectRef reference(StorageObject object) {
        return new StoredObjectRef(object.getId(), object.getContentType(),
                object.getSizeBytes(), object.getSha256());
    }
}
