package ru.shedevrograd.backup_service.tool;

import org.jetbrains.annotations.Nullable;
import ru.shedevrograd.backup_service.archive.DiffManifest;
import ru.shedevrograd.backup_service.sbk.SbkChecksum;
import ru.shedevrograd.backup_service.sbk.SbkGroup;
import ru.shedevrograd.backup_service.sbk.SbkIndexEntry;
import ru.shedevrograd.backup_service.sbk.SbkInfo;
import ru.shedevrograd.backup_service.sbk.SbkProgress;
import ru.shedevrograd.backup_service.sbk.SbkReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Standalone command-line tool for SBK backups; runs without Minecraft.
 *
 * <pre>
 *   java -jar sbk-tool.jar info    &lt;backup.sbk&gt;
 *   java -jar sbk-tool.jar list    &lt;backup.sbk&gt;
 *   java -jar sbk-tool.jar extract &lt;backup.sbk&gt; &lt;output dir&gt;
 * </pre>
 */
public final class SbkTool {
    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;
    private static final int EXIT_USAGE = 2;

    private SbkTool() {}

    public static void main(String[] args) {
        int code;
        try {
            code = SbkTool.run(args);
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                System.err.println("  причина: " + cause);
            }
            code = EXIT_ERROR;
        }
        System.exit(code);
    }

    private static int run(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("info")) {
            return SbkTool.info(Path.of(args[1]));
        }
        if (args.length == 2 && args[0].equals("list")) {
            return SbkTool.list(Path.of(args[1]));
        }
        if (args.length == 3 && args[0].equals("extract")) {
            return SbkTool.extract(Path.of(args[1]), null, Path.of(args[2]));
        }
        if (args.length == 4 && args[0].equals("extract")) {
            return SbkTool.extract(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        }

        System.err.println("""
                SBK — утилита для бэкапов Shedevrograd

                  java -jar sbk-tool.jar info    <бэкап.sbk>                        сводка: тип, мир, число файлов, размеры
                  java -jar sbk-tool.jar list    <бэкап.sbk>                        список файлов в архиве
                  java -jar sbk-tool.jar extract <full-....sbk> <папка>             восстановить из полного бэкапа
                  java -jar sbk-tool.jar extract <full-....sbk> <diff-....sbk> <папка>
                                                                                    восстановить из diff (нужен и его полный)
                """);
        return EXIT_USAGE;
    }

    private static int info(Path archive) throws IOException {
        SbkInfo info = SbkReader.info(SbkTool.requireFile(archive));
        List<SbkIndexEntry> entries = info.entries();
        Optional<DiffManifest> manifest = SbkTool.readManifest(archive, info);

        Map<SbkGroup, long[]> byGroup = new EnumMap<>(SbkGroup.class);
        long totalSize = 0;
        for (SbkIndexEntry entry : entries) {
            long[] stats = byGroup.computeIfAbsent(entry.group(), g -> new long[2]);
            stats[0]++;
            stats[1] += entry.originalSize();
            totalSize += entry.originalSize();
        }

        System.out.println("Архив:      " + archive.toAbsolutePath());
        if (manifest.isPresent()) {
            System.out.println("Тип:        дифференциальный, только изменения с полного бэкапа");
            System.out.println("Нужен:      " + manifest.get().baseFileName());
            System.out.println("Мир имел:   " + manifest.get().paths().size() + " файлов на момент бэкапа");
        } else {
            System.out.println("Тип:        полный");
        }
        System.out.println("Миры:       " + String.join(", ", SbkTool.topLevelNames(entries)));
        System.out.println("Файлов:     " + entries.size());
        System.out.println("Размер:     " + SbkTool.formatSize(Files.size(archive)) + " в архиве, "
                + SbkTool.formatSize(totalSize) + " после распаковки");
        System.out.println("По типам:");
        byGroup.forEach((group, stats) -> System.out.printf("  %-5s %6d файлов, %s%n",
                group, stats[0], SbkTool.formatSize(stats[1])));
        return EXIT_OK;
    }

    private static int list(Path archive) throws IOException {
        for (SbkIndexEntry entry : SbkReader.info(SbkTool.requireFile(archive)).entries()) {
            System.out.printf("%12d  %s%n", entry.originalSize(), entry.path());
        }
        return EXIT_OK;
    }

    private static int extract(Path full, @Nullable Path diff, Path outputDir) throws IOException {
        SbkInfo fullInfo = SbkReader.info(SbkTool.requireFile(full));
        Optional<DiffManifest> fullManifest = SbkTool.readManifest(full, fullInfo);
        if (fullManifest.isPresent()) {
            System.err.println(full.getFileName() + " — дифференциальный бэкап: в нём только изменения.");
            System.err.println("Для восстановления нужен ещё полный бэкап " + fullManifest.get().baseFileName() + ":");
            System.err.println("  java -jar sbk-tool.jar extract " + fullManifest.get().baseFileName() + " " + full.getFileName() + " " + outputDir);
            return EXIT_ERROR;
        }

        SbkInfo diffInfo = null;
        DiffManifest manifest = null;
        if (diff != null) {
            diffInfo = SbkReader.info(SbkTool.requireFile(diff));
            Optional<DiffManifest> diffManifest = SbkTool.readManifest(diff, diffInfo);
            if (diffManifest.isEmpty()) {
                System.err.println(diff.getFileName() + " — полный бэкап, а не diff. Для него достаточно: extract " + diff.getFileName() + " " + outputDir);
                return EXIT_ERROR;
            }
            manifest = diffManifest.get();
            if (!manifest.baseFileName().equals(full.getFileName().toString())) {
                System.err.println(diff.getFileName() + " сделан от " + manifest.baseFileName() + ", а указан " + full.getFileName() + ".");
                System.err.println("Diff можно накладывать только на тот полный бэкап, от которого он сделан.");
                return EXIT_ERROR;
            }
            if (!SbkTool.topLevelNames(diffInfo.entries()).equals(SbkTool.topLevelNames(fullInfo.entries()))) {
                System.err.println("В " + full.getFileName() + " и " + diff.getFileName() + " разные миры.");
                return EXIT_ERROR;
            }
        }

        Set<String> worlds = SbkTool.topLevelNames(fullInfo.entries());
        Path output = outputDir.toAbsolutePath().normalize();

        // Never overwrite: a restore must not destroy whatever is already there
        for (String world : worlds) {
            Path target = output.resolve(world);
            if (Files.exists(target)) {
                System.err.println("Папка уже существует: " + target);
                System.err.println("Укажите другую папку или переименуйте/удалите существующую.");
                return EXIT_ERROR;
            }
        }

        Files.createDirectories(output);
        // Extract next to the target and move into place only when everything succeeded,
        // so an interrupted run never leaves a half-restored world under the real name
        Path staging = Files.createTempDirectory(output, ".sbk-extract-");
        try {
            System.out.println("Распаковка " + fullInfo.entries().size() + " файлов из " + full.getFileName() + "...");
            SbkReader.extractAll(full, staging, new ProgressPrinter());

            Map<String, SbkIndexEntry> restored = new HashMap<>();
            fullInfo.entries().forEach(entry -> restored.put(entry.path(), entry));

            if (diff != null) {
                System.out.println("Наложение " + diffInfo.entries().size() + " изменённых файлов из " + diff.getFileName() + "...");
                SbkReader.extractAll(diff, staging, new ProgressPrinter());
                diffInfo.entries().forEach(entry -> restored.put(entry.path(), entry));

                int removed = SbkTool.removeFilesMissingFrom(manifest, staging);
                restored.keySet().retainAll(Set.copyOf(manifest.paths()));
                if (removed > 0) {
                    System.out.println("Удалено файлов, которых на момент diff уже не было: " + removed);
                }
            }

            int verified = SbkTool.verifyVerbatimFiles(restored.values(), staging);

            for (String world : worlds) {
                Files.move(staging.resolve(world), output.resolve(world));
            }
            System.out.println("Готово. Проверено контрольных сумм: " + verified + ".");
        } finally {
            SbkTool.deleteRecursively(staging);
        }

        System.out.println();
        for (String world : worlds) {
            System.out.println("Мир восстановлен в: " + output.resolve(world));
        }
        System.out.println("""

                Дальше:
                  1. Остановите сервер.
                  2. Переименуйте текущую папку мира на сервере (например, world -> world.old), не удаляйте её сразу.
                  3. Загрузите восстановленную папку на место мира. Имя должно совпадать с level-name в server.properties.
                  4. Запустите сервер и проверьте мир. Старую папку удалите, только когда убедитесь, что всё в порядке.""");
        return EXIT_OK;
    }

    /**
     * RAW files are restored byte for byte, so their stored checksum must match.
     * Other groups are re-encoded on restore (region chunks recompressed, NBT re-gzipped),
     * their content is protected by the per-frame checksums verified during extraction.
     */
    private static int verifyVerbatimFiles(Collection<SbkIndexEntry> entries, Path staging) throws IOException {
        int verified = 0;
        for (SbkIndexEntry entry : entries) {
            if (entry.group() != SbkGroup.RAW) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(staging.resolve(entry.path()));
            if (SbkChecksum.xxHash32(bytes) != entry.fileChecksum()) {
                throw new IOException("Контрольная сумма не совпала: " + entry.path() + ". Архив повреждён.");
            }
            verified++;
        }
        return verified;
    }

    /**
     * Brings the extracted tree to the exact file set of the diff: deletes files the world no longer
     * had at diff time (and the manifest itself), and fails if a listed file is missing.
     */
    private static int removeFilesMissingFrom(DiffManifest manifest, Path staging) throws IOException {
        Set<String> expected = Set.copyOf(manifest.paths());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(staging)) {
            files = walk.filter(Files::isRegularFile).toList();
        }

        int removed = 0;
        Set<String> present = new HashSet<>();
        for (Path file : files) {
            String path = staging.relativize(file).toString().replace('\\', '/');
            if (expected.contains(path)) {
                present.add(path);
            } else {
                Files.delete(file);
                if (!path.endsWith("/" + DiffManifest.FILE_NAME)) {
                    removed++;
                }
            }
        }

        for (String path : expected) {
            if (!present.contains(path)) {
                throw new IOException("Файла " + path + " нет ни в полном бэкапе, ни в diff. Архивы не соответствуют друг другу.");
            }
        }
        return removed;
    }

    /** The diff manifest stored in a diff archive; empty for a full backup. */
    private static Optional<DiffManifest> readManifest(Path archive, SbkInfo info) throws IOException {
        Optional<String> manifestPath = info.entries().stream()
                .map(SbkIndexEntry::path)
                .filter(path -> path.indexOf('/') == path.lastIndexOf('/') && path.endsWith("/" + DiffManifest.FILE_NAME))
                .findFirst();
        if (manifestPath.isEmpty()) {
            return Optional.empty();
        }

        Path temp = Files.createTempDirectory("sbk-manifest-");
        try {
            SbkReader.extract(archive, temp, Set.of(manifestPath.get()), SbkProgress.SILENT);
            return Optional.of(DiffManifest.parse(Files.readAllBytes(temp.resolve(manifestPath.get()))));
        } finally {
            SbkTool.deleteRecursively(temp);
        }
    }

    private static Set<String> topLevelNames(List<SbkIndexEntry> entries) {
        Set<String> names = new TreeSet<>();
        for (SbkIndexEntry entry : entries) {
            int slash = entry.path().indexOf('/');
            names.add(slash < 0 ? entry.path() : entry.path().substring(0, slash));
        }
        return names;
    }

    private static Path requireFile(Path archive) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new IOException("Файл не найден: " + archive.toAbsolutePath());
        }
        return archive;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format("%.1f КБ", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f МБ", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.2f ГБ", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private static final class ProgressPrinter implements SbkProgress {
        private int lastDecile = 0;

        @Override
        public void onFile(int completed, int total, String path) {
            int decile = total == 0 ? 10 : completed * 10 / total;
            if (decile > this.lastDecile) {
                this.lastDecile = decile;
                System.out.println("  " + decile * 10 + "% (" + completed + "/" + total + ")");
            }
        }
    }
}
