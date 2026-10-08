package ru.shedevrograd.backup_service.sbk;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.UnsupportedOptionsException;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stream wrappers for the SBK frame codecs.
 *
 * <p>Replaces upstream {@code ToolsLoader}: upstream loads xz / zstd-jni reflectively from an
 * external-dependencies directory, here both libraries are bundled into the mod jar via jarJar
 * and used directly.
 */
public final class SbkCodecs {

    private SbkCodecs() {}

    public static boolean isLzmaAvailable() {
        return SbkCodecs.isClassAvailable("org.tukaani.xz.XZ");
    }

    public static boolean isZstdAvailable() {
        // zstd-jni loads a native library on first use; a missing native for the current
        // platform surfaces as LinkageError, not ClassNotFoundException
        try {
            com.github.luben.zstd.util.Native.load();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isClassAvailable(String className) {
        try {
            Class.forName(className, false, SbkCodecs.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static OutputStream wrapWithXz(OutputStream out, int preset) throws IOException {
        LZMA2Options options = new LZMA2Options();
        try {
            options.setPreset(Math.clamp(preset, 0, 9));
        } catch (UnsupportedOptionsException ignored) {
            // unsupported preset → keep the LZMA2Options default
        }

        return new XZOutputStream(out, options);
    }

    public static InputStream wrapXzInput(InputStream in) throws IOException {
        return new XZInputStream(in);
    }

    public static OutputStream wrapWithZstd(OutputStream out, int level) throws IOException {
        return level < 0 ? new ZstdOutputStream(out) : new ZstdOutputStream(out, level);
    }

    public static InputStream wrapZstdInput(InputStream in) throws IOException {
        return new ZstdInputStream(in);
    }
}
