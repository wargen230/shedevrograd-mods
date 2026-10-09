// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and extracts SBK archives.
 *
 * <p>{@link #info} loads only the header and index - no frame data is decompressed -
 * making it suitable for displaying backup details.
 *
 * <p>{@link #extractAll} decompresses all frames and reconstructs every file,
 * restoring original file formats and last-modified timestamps.
 */
public final class SbkReader {

    private SbkReader() {}

    /**
     * Returns archive metadata and the full file index without decompressing frame data.
     *
     * @param archivePath path to the {@code .sbk} file
     * @throws SbkException if the header or index checksum is invalid
     */
    public static SbkInfo info(Path archivePath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(archivePath.toFile(), "r")) {
            SbkHeader header = SbkHeader.read(raf);

            raf.seek(header.frameDirOffset);
            byte[] frameDirBytes = new byte[(int) header.frameDirSize];
            raf.readFully(frameDirBytes);
            SbkFrameDir frameDir = SbkFrameDir.read(
                    new DataInputStream(new ByteArrayInputStream(frameDirBytes)));

            raf.seek(header.indexOffset);
            byte[] indexBytes = new byte[(int) header.indexCompressedSize];
            raf.readFully(indexBytes);
            List<SbkIndexEntry> entries = SbkIndex.read(
                    new ByteArrayInputStream(indexBytes),
                    header.algorithm,
                    header.indexCompressedSize,
                    header.indexChecksum);

            int[] groupFrames = new int[SbkGroup.values().length];
            long[] groupSizes = new long[SbkGroup.values().length];
            for (SbkGroup g : SbkGroup.values()) {
                List<SbkFrameEntry> gFrames = frameDir.framesFor(g);
                groupFrames[g.id] = gFrames.size();
                long total = 0;
                for (SbkFrameEntry fe : gFrames) total += fe.compressedSize();
                groupSizes[g.id] = total;
            }

            return new SbkInfo(header.formatVersion, header.fileCount, header.frameSizeBytes,
                    groupFrames, groupSizes, entries);
        }
    }

    public static long extract(Path archivePath, Path outputDir, Set<String> toExtract, SbkProgress progress) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(archivePath.toFile(), "r")) {
            SbkHeader header = SbkHeader.read(raf);

            raf.seek(header.frameDirOffset);
            byte[] frameDirBytes = new byte[(int) header.frameDirSize];
            raf.readFully(frameDirBytes);
            SbkFrameDir frameDir = SbkFrameDir.read(
                    new DataInputStream(new ByteArrayInputStream(frameDirBytes)));

            raf.seek(header.indexOffset);
            byte[] indexBytes = new byte[(int) header.indexCompressedSize];
            raf.readFully(indexBytes);
            List<SbkIndexEntry> entries = SbkIndex.read(
                    new ByteArrayInputStream(indexBytes),
                    header.algorithm,
                    header.indexCompressedSize,
                    header.indexChecksum);

            long frameSize = header.frameSizeBytes;
            int total = toExtract.size();

            // Frame cache: frameKey → decompressed frame bytes
            Map<Long, byte[]> frameCache = new HashMap<>();
            int extracted = 0;
            for (int i = 0; i < entries.size(); i++) {
                SbkIndexEntry entry = entries.get(i);
                if (!toExtract.contains(entry.path())) {
                    continue;
                }

                Set<Long> requiredKeys = SbkFrameExtractor.requiredFrames(entry, frameSize);
                for (long key : requiredKeys) {
                    if (!frameCache.containsKey(key)) {
                        int groupId = (int) (key >>> 32);
                        long frameIndex = key & 0xFFFFFFFFL;
                        SbkGroup group = SbkGroup.fromId(groupId);
                        if (group == null) throw new SbkException("Unknown group id: " + groupId);

                        List<SbkFrameEntry> gFrames = frameDir.framesFor(group);
                        if (frameIndex >= gFrames.size()) {
                            throw new SbkException("Frame index " + frameIndex
                                    + " out of range for group " + group
                                    + " (size=" + gFrames.size() + ")");
                        }
                        SbkFrameEntry fe = gFrames.get((int) frameIndex);

                        raf.seek(fe.frameOffset());
                        byte[] compData = new byte[fe.compressedSize()];
                        raf.readFully(compData);

                        int actualChecksum = SbkChecksum.xxHash32(compData);
                        if (actualChecksum != fe.checksum()) {
                            throw new SbkException("Frame checksum mismatch for group "
                                    + group + " frame " + frameIndex);
                        }

                        byte[] decompressed = SbkFrameCompressor.decompress(
                                compData, header.algorithm, fe.rawSize());
                        frameCache.put(key, decompressed);
                    }
                }

                byte[] preprocessed = SbkFrameExtractor.slice(frameCache, entry, frameSize);
                byte[] fileBytes = SbkReader.postprocess(entry.group(), preprocessed);

                // Shedevrograd: upstream resolved index paths as-is; a crafted or corrupt index with
                // "../" or an absolute path could write outside outputDir
                Path baseDir = outputDir.toAbsolutePath().normalize();
                Path outFile = baseDir.resolve(entry.path()).normalize();
                if (!outFile.startsWith(baseDir)) {
                    throw new SbkException("Archive entry escapes the output directory: " + entry.path());
                }
                Files.createDirectories(outFile.getParent());
                Files.write(outFile, fileBytes);
                Files.setLastModifiedTime(outFile, FileTime.fromMillis(entry.mtimeMs()));

                extracted++;
                progress.onFile(extracted, total, entry.path());
                SbkReader.evictUnneededFrames(frameCache, entries, i, frameSize);
            }

            return extracted;
        }
    }

    /**
     * Extracts all files from the archive into {@code outputDir}, preserving directory
     * structure and last-modified timestamps.
     *
     * @param archivePath path to the {@code .sbk} file
     * @param outputDir   destination directory; created if absent
     * @param progress    progress callback
     * @return number of files extracted
     */
    public static long extractAll(Path archivePath, Path outputDir, SbkProgress progress) throws IOException {
        SbkInfo info = SbkReader.info(archivePath);
        Set<String> toExtract = new HashSet<>();
        info.entries().forEach(entry -> toExtract.add(entry.path()));

        return SbkReader.extract(archivePath, outputDir, toExtract, progress);
    }

    private static void evictUnneededFrames(Map<Long, byte[]> frameCache, List<SbkIndexEntry> entries, int currentIndex, long frameSize) {
        Set<Long> stillNeeded = new HashSet<>();
        for (int j = currentIndex + 1; j < entries.size(); j++) {
            stillNeeded.addAll(SbkFrameExtractor.requiredFrames(entries.get(j), frameSize));
        }

        frameCache.keySet().retainAll(stillNeeded);
    }

    private static byte[] postprocess(SbkGroup group, byte[] preprocessed) throws IOException {
        return switch(group) {
            case MCA -> McapProcessor.fromMcap(preprocessed);
            case NBT -> SbkReader.wrapGzip(preprocessed);
            case JSON, RAW -> preprocessed;
        };
    }

    private static byte[] wrapGzip(byte[] raw) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(outputStream)) {
            gzip.write(raw);
        }

        return outputStream.toByteArray();
    }
}
