package com.mineuno.packhost;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishedPackTest {
    @TempDir Path directory;

    @Test
    void openedDownloadKeepsItsBytesAndMetadataAcrossReplacement() throws Exception {
        PublishedPack pack = new PublishedPack(directory.resolve("pack.zip"));
        assertNull(pack.open());
        byte[] old = "old pack".getBytes();
        publish(pack, old);
        try (PublishedPack.Download download = pack.open()) {
            publish(pack, "a much larger replacement pack".getBytes());
            assertArrayEquals(old, download.input().readAllBytes());
            assertEquals(old.length, download.length());
            assertArrayEquals(digest(old), download.hash());
        }
        verifyDownload(pack);
    }

    @Test
    void concurrentDownloadsAlwaysMatchTheirHashAndLength() throws Exception {
        PublishedPack pack = new PublishedPack(directory.resolve("pack.zip"));
        publish(pack, new byte[1]);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> writer = executor.submit(() -> {
                try {
                    for (int i = 1; i <= 200; i++) {
                        byte[] bytes = new byte[i * 37];
                        java.util.Arrays.fill(bytes, (byte) i);
                        publish(pack, bytes);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Future<?> first = executor.submit(() -> verifyRepeatedly(pack));
            Future<?> second = executor.submit(() -> verifyRepeatedly(pack));
            writer.get();
            first.get();
            second.get();
        }
    }

    @Test
    void failedPublicationRetainsPreviousPack() throws Exception {
        PublishedPack pack = new PublishedPack(directory.resolve("pack.zip"));
        byte[] original = "original".getBytes();
        publish(pack, original);
        assertThrows(java.io.IOException.class,
                () -> pack.publish(directory.resolve("missing.zip"), digest(new byte[0])));
        try (PublishedPack.Download download = pack.open()) {
            assertArrayEquals(original, download.input().readAllBytes());
            assertArrayEquals(digest(original), download.hash());
        }
    }

    private void publish(PublishedPack pack, byte[] bytes) throws Exception {
        Path temp = Files.createTempFile(directory, "build-", ".zip");
        Files.write(temp, bytes);
        pack.publish(temp, digest(bytes));
    }

    private static byte[] digest(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(bytes);
    }

    private static void verifyRepeatedly(PublishedPack pack) {
        try {
            for (int i = 0; i < 400; i++) verifyDownload(pack);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void verifyDownload(PublishedPack pack) throws Exception {
        try (PublishedPack.Download download = pack.open()) {
            byte[] bytes = download.input().readAllBytes();
            assertEquals(bytes.length, download.length());
            assertArrayEquals(digest(bytes), download.hash());
        }
    }
}
