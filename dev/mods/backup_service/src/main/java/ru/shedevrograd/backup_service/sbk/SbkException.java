// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

public final class SbkException extends RuntimeException {

    public SbkException(String message) {
        super(message);
    }

    public SbkException(String message, Throwable cause) {
        super(message, cause);
    }
}
