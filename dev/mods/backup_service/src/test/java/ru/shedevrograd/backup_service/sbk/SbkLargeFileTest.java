package ru.shedevrograd.backup_service.sbk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SbkLargeFileTest {

    @Test
    void streamingChecksumMatchesOneShot() {
        Random random = new Random(42);
        for (int length : new int[]{0, 1, 3, 15, 16, 17, 31, 32, 33, 100, 4096, 100_003}) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            for (int chunk : new int[]{1, 3, 7, 16, 17, 1000}) {
                SbkChecksum.Streaming streaming = new SbkChecksum.Streaming();
                for (int offset = 0; offset < length; offset += chunk) {
                    streaming.update(data, offset, Math.min(chunk, length - offset));
                }
                assertEquals(SbkChecksum.xxHash32(data), streaming.digest(), "length " + length + ", chunk " + chunk);
            }
        }
    }

    /**
     * A file above the streaming threshold, surrounded by small files sharing its frames, must
     * survive a round trip byte for byte. Uses ~100 MiB of temp disk, not 2 GiB: the code path is
     * the same, only the array-size limit is not reached.
     */
    @Test
    void largeFileRoundTrip(@TempDir Path temp) throws IOException {
        Path world = Files.createDirectories(temp.resolve("world/data"));
        Path large = world.resolve("mod-database.sqlite");
        long size = SbkWriter.LARGE_FILE_SIZE + 37 * 1024 * 1024 + 12345;
        Random random = new Random(7);
        byte[] block = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(large)) {
            for (long written = 0; written < size; written += block.length) {
                random.nextBytes(block);
                out.write(block, 0, (int) Math.min(block.length, size - written));
            }
        }
        Files.writeString(world.resolve("a-before.bin"), "small file before");
        Files.writeString(world.resolve("z-after.bin"), "small file after");

        Path worldDir = temp.resolve("world");
        List<Path> files;
        try (var walk = Files.walk(worldDir)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        Path archive = temp.resolve("full.sbk");
        SbkWriter.compress(worldDir, files, worldDir.getFileName(), archive,
                SbkWriteOptions.builder().algorithm(SbkAlgorithm.LZMA2).lzmaPreset(0).build(), SbkProgress.SILENT);

        Path out = temp.resolve("out");
        assertEquals(3, SbkReader.extractAll(archive, out, SbkProgress.SILENT));
        for (Path file : files) {
            Path restored = out.resolve("world").resolve(worldDir.relativize(file));
            assertEquals(Files.size(file), Files.size(restored), file.toString());
            assertTrue(SbkLargeFileTest.sameContent(file, restored), file.toString());
        }

        SbkIndexEntry entry = SbkReader.info(archive).entries().stream()
                .filter(e -> e.path().endsWith("mod-database.sqlite")).findFirst().orElseThrow();
        assertEquals(SbkGroup.RAW, entry.group());
        assertEquals(size, entry.originalSize());
    }

    private static boolean sameContent(Path a, Path b) throws IOException {
        try (InputStream inA = Files.newInputStream(a); InputStream inB = Files.newInputStream(b)) {
            byte[] bufA = new byte[1 << 20];
            byte[] bufB = new byte[1 << 20];
            while (true) {
                int readA = inA.readNBytes(bufA, 0, bufA.length);
                int readB = inB.readNBytes(bufB, 0, bufB.length);
                if (readA != readB || !java.util.Arrays.equals(bufA, 0, readA, bufB, 0, readB)) {
                    return false;
                }
                if (readA == 0) {
                    return true;
                }
            }
        }
    }
}
