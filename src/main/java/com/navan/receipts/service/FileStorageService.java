package com.navan.receipts.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Writes uploaded bytes to a local directory, named by caller (we use the content hash). */
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(@Value("${storage.dir:data/files}") String dir) {
        this.root = Paths.get(dir);
    }

    /** Stores the bytes under {@code name}; skips the write if that file already exists. */
    public String store(String name, byte[] bytes) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve(name);
            if (!Files.exists(target)) {
                Files.write(target, bytes);
            }
            return target.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file " + name, e);
        }
    }

    /** Deletes the file at {@code path} if present. */
    public void delete(String path) {
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file " + path, e);
        }
    }
}
