package com.mineuno.packhost;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 原子发布资源包，并让每次下载的摘要、长度与已打开文件保持一致。 */
final class PublishedPack {
    private final Path path;
    private byte[] hash = new byte[0];
    private long length;

    PublishedPack(Path path) {
        this.path = path;
    }

    synchronized void publish(Path temp, byte[] hash) throws IOException {
        long size = Files.size(temp);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        this.hash = hash.clone();
        length = size;
    }

    synchronized Download open() throws IOException {
        // 未成功构建时不提供磁盘上可能遗留的旧包（它没有已验证的摘要）。
        if (hash.length == 0) return null;
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new Download(Channels.newInputStream(channel), channel.size(), hash.clone());
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    synchronized byte[] hash() {
        return hash.clone();
    }

    synchronized long length() {
        return length;
    }

    record Download(InputStream input, long length, byte[] hash) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
