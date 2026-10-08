package ru.shedevrograd.backup_service.sbk;

import java.io.IOException;

/**
 * Thrown by a preprocessor when a file is valid but uses a layout it cannot convert losslessly
 * (external chunks, unknown chunk compression, a {@code .dat} that is not gzip...).
 *
 * <p>{@link SbkWriter} reacts by storing the file verbatim in the {@link SbkGroup#RAW} group
 * instead of dropping it from the archive.
 */
public class SbkUnsupportedFileException extends IOException {

    public SbkUnsupportedFileException(String message) {
        super(message);
    }

    public SbkUnsupportedFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
