// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

@FunctionalInterface
public interface SbkProgress {

    void onFile(int completed, int total, String currentPath);

    SbkProgress SILENT = (completed, total, path) -> {};
}
