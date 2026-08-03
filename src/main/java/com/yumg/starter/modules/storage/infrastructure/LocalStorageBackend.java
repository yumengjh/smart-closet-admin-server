package com.yumg.starter.modules.storage.infrastructure;

import com.yumg.starter.modules.storage.application.StorageNamespace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalStorageBackend {
    private final Path root;

    @Autowired
    public LocalStorageBackend(@Value("${app.storage.local.root:${APP_DATA_DIR:./data}/storage}") String root) {
        this(Path.of(root));
    }

    public LocalStorageBackend(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public StoredFile write(StorageNamespace namespace, String extension, byte[] content) {
        if (content == null) throw new IllegalArgumentException("content is required");
        if (extension == null || !extension.matches("[a-z0-9]{1,10}")) {
            throw new IllegalArgumentException("invalid extension");
        }
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        String key = "objects/" + namespace.directory() + "/" + date.getYear() + "/"
                + "%02d".formatted(date.getMonthValue()) + "/" + UUID.randomUUID() + "." + extension;
        Path target = safePath(key);
        Path temporary = safePath("tmp/" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.createDirectories(temporary.getParent());
            rejectSymbolicLinks(target.getParent());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            moveAtomically(temporary, target);
            return new StoredFile(key, content.length, sha256(content));
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("Local storage write failed", exception);
        }
    }

    public byte[] read(String objectKey) {
        Path path = safePath(objectKey);
        try {
            rejectSymbolicLinks(path.getParent());
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Local storage read failed", exception);
        }
    }

    public boolean delete(String objectKey) {
        Path path = safePath(objectKey);
        try {
            rejectSymbolicLinks(path.getParent());
            return Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Local storage delete failed", exception);
        }
    }

    public boolean exists(String objectKey) {
        return Files.isRegularFile(safePath(objectKey));
    }

    public StorageHealthResult checkHealth() {
        Path probe = null;
        try {
            Files.createDirectories(root.resolve("tmp"));
            probe = safePath("tmp/health-" + UUID.randomUUID() + ".tmp");
            byte[] content = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(probe, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!java.util.Arrays.equals(content, Files.readAllBytes(probe))) {
                throw new IOException("probe content mismatch");
            }
            Files.delete(probe);
            var store = Files.getFileStore(root);
            return new StorageHealthResult(true, "local storage is writable",
                    store.getUsableSpace(), store.getTotalSpace());
        } catch (IOException exception) {
            if (probe != null) deleteQuietly(probe);
            return new StorageHealthResult(false, "local storage probe failed", 0, 0);
        }
    }

    public long temporaryFileCount() {
        Path temporary = root.resolve("tmp");
        if (!Files.isDirectory(temporary)) return 0;
        try (Stream<Path> files = Files.list(temporary)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect storage temporary directory", exception);
        }
    }

    public List<String> untrackedObjectKeys(Set<String> knownKeys, Instant olderThan, int limit) {
        Path objects = root.resolve("objects");
        if (!Files.isDirectory(objects)) return List.of();
        try (Stream<Path> files = Files.walk(objects)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> {
                        try { return Files.getLastModifiedTime(path).toInstant().isBefore(olderThan); }
                        catch (IOException exception) { return false; }
                    })
                    .map(path -> root.relativize(path).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(key -> !knownKeys.contains(key))
                    .limit(limit)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect local storage objects", exception);
        }
    }

    public List<String> staleTemporaryKeys(Instant olderThan, int limit) {
        Path temporary = root.resolve("tmp");
        if (!Files.isDirectory(temporary)) return List.of();
        try (Stream<Path> files = Files.list(temporary)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> {
                        try { return Files.getLastModifiedTime(path).toInstant().isBefore(olderThan); }
                        catch (IOException exception) { return false; }
                    })
                    .map(path -> root.relativize(path).toString().replace(java.io.File.separatorChar, '/'))
                    .limit(limit)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect local storage temporary files", exception);
        }
    }

    public StorageHealthResult capacity() {
        try {
            Files.createDirectories(root);
            var store = Files.getFileStore(root);
            return new StorageHealthResult(true, "local storage capacity",
                    store.getUsableSpace(), store.getTotalSpace());
        } catch (IOException exception) {
            return new StorageHealthResult(false, "local storage capacity unavailable", 0, 0);
        }
    }

    public Path root() { return root; }

    private Path safePath(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("object key is required");
        Path relative = Path.of(objectKey);
        if (relative.isAbsolute()) throw new IllegalArgumentException("absolute object key is forbidden");
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("object key escapes storage root");
        }
        return resolved;
    }

    private void rejectSymbolicLinks(Path directory) throws IOException {
        if (directory == null) return;
        Path current = root;
        Path relative = root.relativize(directory.toAbsolutePath().normalize());
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("symbolic link is forbidden");
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
