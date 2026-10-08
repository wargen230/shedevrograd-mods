// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;


/**
 * Compression parameters for {@link SbkWriter}.
 *
 * <p>Build with {@link #builder()}:
 * <pre>{@code
 * SbkWriteOptions opts = SbkWriteOptions.builder()
 *     .lzmaPreset(6)
 *     .algorithm(SbkAlgorithm.LZMA2)
 *     .build();
 * }</pre>
 */
public final class SbkWriteOptions {

    /**
     * Default LZMA2 preset / base compression level when none is specified.
     */
    public static final int DEFAULT_PRESET = 3;

    /**
     * LZMA2 preset (0–9) or base compression level passed to the selected algorithm.
     * For zstd this is mapped to zstd levels 1–19 by {@link SbkFrameCompressor}.
     */
    public final int lzmaPreset;

    /**
     * Absolute timestamp filter for direct callers that do not pre-filter files.
     * Files with {@code mtime < sinceMs} are excluded. {@code 0} means no filter.
     * Shedevrograd: not applied by {@link SbkWriter} (neither upstream); callers pass a pre-filtered file list.
     */
    public final long sinceMs;

    /**
     * Preferred compression algorithm.
     * If {@link SbkAlgorithm#ZSTD} is requested but zstd-jni is unavailable,
     * {@link SbkWriter} falls back to {@link SbkAlgorithm#LZMA2} with a warning.
     */
    public final SbkAlgorithm preferredAlgorithm;

    private SbkWriteOptions(int lzmaPreset, long sinceMs, SbkAlgorithm preferredAlgorithm) {
        this.lzmaPreset = lzmaPreset;
        this.sinceMs = sinceMs;
        this.preferredAlgorithm = preferredAlgorithm;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int lzmaPreset = SbkWriteOptions.DEFAULT_PRESET;
        private long sinceMs = 0L;
        private SbkAlgorithm preferredAlgorithm = SbkCodecs.isLzmaAvailable() ? SbkAlgorithm.LZMA2 : SbkAlgorithm.ZSTD;

        public Builder lzmaPreset(int preset) {
            this.lzmaPreset = preset;

            return this;
        }

        public Builder sinceMs(long ms) {
            this.sinceMs = ms;

            return this;
        }

        public Builder algorithm(SbkAlgorithm algorithm) {
            this.preferredAlgorithm = algorithm;

            return this;
        }

        public SbkWriteOptions build() {
            return new SbkWriteOptions(this.lzmaPreset, this.sinceMs, this.preferredAlgorithm);
        }
    }
}
