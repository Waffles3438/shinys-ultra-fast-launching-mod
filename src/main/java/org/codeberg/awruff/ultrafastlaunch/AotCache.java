package org.codeberg.awruff.ultrafastlaunch;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Manages a JDK AOT cache (JEP 483/514, Java 25+) for the next launch.
 *
 * <p>JVM flags can't be changed from inside a running JVM, so the user adds one argument once:
 * {@code -XX:VMOptionsFile=<config>/ultrafastlaunch/jvm.options}. Every launch, this class rewrites that file for the
 * next launch: {@code -XX:AOTCache=...} when a cache matching the current JDK, class path and mod set exists, or
 * {@code -XX:AOTCacheOutput=...} to build one (the JVM writes it when the game exits normally).
 *
 * <p>The file always starts with {@code -XX:+IgnoreUnrecognizedVMOptions} so that a JDK without the AOT flags still
 * starts. The JVM refuses to start if the options file is missing, so this class only ever rewrites it in place.
 */
final class AotCache {
    static final String DISABLE_PROPERTY = "ultrafastlaunch.aot";
    private static final String MARKER_PROPERTY = "ultrafastlaunch.vmoptions";
    private static final int MIN_JAVA = 25;
    private static final int MAX_FAILED_TRAININGS = 2;
    private static final String CACHE_PREFIX = "aot-";
    private static final String CACHE_SUFFIX = ".aot";

    private AotCache() {
    }

    static Path optionsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("ultrafastlaunch").resolve("jvm.options");
    }

    /**
     * Writes the options for the next launch and returns a short label describing this launch, for the benchmark.
     */
    static String update() {
        Path options = optionsFile().toAbsolutePath().normalize();
        boolean argumentSet = System.getProperty(MARKER_PROPERTY) != null;
        String using = vmOption("AOTCache");
        String training = vmOption("AOTCacheOutput");

        if ("false".equals(System.getProperty(DISABLE_PROPERTY))) {
            writeOptions(options, null);
            return "aot-off";
        }
        if (javaVersion() < MIN_JAVA || using == null || training == null) {
            writeOptions(options, null);
            if (argumentSet) {
                Log.info(LogCategory.GENERAL, "[UltraFastLaunch] The AOT cache needs Java %d or newer, this is Java %s", MIN_JAVA, System.getProperty("java.version"));
            }
            return "aot-unsupported";
        }

        Path dir = TransformCache.directory().resolve("aot").toAbsolutePath().normalize();
        Path target = dir.resolve(CACHE_PREFIX + key() + CACHE_SUFFIX);
        Path stateFile = dir.resolve("state.properties");
        boolean trainingNow = !training.isEmpty();
        boolean trainingTarget = trainingNow && samePath(training, target);
        String label = trainingNow ? "aot-train" : !using.isEmpty() && Files.isRegularFile(Paths.get(using)) ? "aot-use" : "aot-none";

        Properties state = loadState(stateFile);
        int failures = target.toString().equals(state.getProperty("target")) ? parseInt(state.getProperty("failures")) : 0;
        String pending = state.getProperty("pending");
        if (pending != null && !trainingNow && pending.equals(target.toString())) {
            // The previous launch was a training run for this cache. If it didn't produce the file, the game crashed or
            // was killed before the JVM could write it, or cache creation failed.
            failures = Files.isRegularFile(target) ? 0 : failures + 1;
            pending = null;
        }

        String next;
        if (Files.isRegularFile(target)) {
            next = "-XX:AOTCache=" + target;
            failures = 0;
        } else if (trainingTarget) {
            next = "-XX:AOTCache=" + target;
            pending = target.toString();
            Log.info(LogCategory.GENERAL, "[UltraFastLaunch] Building the AOT cache during this launch. Quit the game normally to save it; quitting takes longer and briefly needs extra memory");
        } else if (failures >= MAX_FAILED_TRAININGS) {
            next = null;
            Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] The AOT cache wasn't created after %d attempts (the game has to be quit normally for it to be saved). Not retrying until Java, the class path or mods change, or %s is deleted", failures, stateFile);
        } else {
            next = "-XX:AOTCacheOutput=" + target;
            if (argumentSet) {
                Log.info(LogCategory.GENERAL, "[UltraFastLaunch] The next launch will build the AOT cache");
            }
        }

        if (next != null && target.toString().indexOf('"') >= 0) next = null; // can't be quoted in the options file
        writeOptions(options, next);

        state.setProperty("target", target.toString());
        state.setProperty("failures", Integer.toString(failures));
        if (pending != null) state.setProperty("pending", pending);
        else state.remove("pending");
        saveState(stateFile, state);

        deleteStaleCaches(dir, target, using, training);

        if (!argumentSet) {
            Log.info(LogCategory.GENERAL, "[UltraFastLaunch] To speed up launches with the Java AOT cache (Java %d+), add this JVM argument: -XX:VMOptionsFile=%s", MIN_JAVA, quoteIfNeeded(options.toString()));
        }
        return label;
    }

    /**
     * Identifies everything an AOT cache depends on. A change makes the next launch build a new cache. The JDK build and
     * class path must match exactly or the JVM ignores the cache, mod changes invalidate the cached Mixin output, and
     * GC/object layout flags change what the JVM can map from the cache.
     */
    private static String key() {
        List<String> lines = new ArrayList<>();
        lines.add("vm=" + System.getProperty("java.vm.vendor") + "|" + System.getProperty("java.vm.version") + "|" + System.getProperty("java.home"));
        lines.add("cp=" + System.getProperty("java.class.path"));
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.matches("-XX:[+-]Use\\w*GC") || arg.matches("-XX:[+-]Use(CompressedOops|CompressedClassPointers|CompactObjectHeaders)")) {
                lines.add("arg=" + arg);
            }
        }
        try {
            lines.addAll(TransformCache.computeFingerprint());
        } catch (IOException e) {
            lines.add("fingerprint=unavailable");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : Arrays.copyOf(digest.digest(), 8)) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // The JVM splits options file lines on whitespace unless quoted and has no comment syntax, so every line is a single
    // option and paths are quoted. Forward slashes avoid any backslash handling.
    private static void writeOptions(Path file, String aotOption) {
        StringBuilder text = new StringBuilder();
        text.append("-XX:+IgnoreUnrecognizedVMOptions\n");
        text.append("-D").append(MARKER_PROPERTY).append("=1\n");
        if (aotOption != null) text.append('"').append(aotOption.replace('\\', '/')).append("\"\n");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), bytes)) return;
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.warn(LogCategory.GENERAL, "[UltraFastLaunch] Could not write %s", file, e);
        }
    }

    private static void deleteStaleCaches(Path dir, Path keep, String using, String training) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, CACHE_PREFIX + "*")) {
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (!name.endsWith(CACHE_SUFFIX) && !name.endsWith(CACHE_SUFFIX + ".config")) continue;
                if (p.equals(keep) || samePath(using, p) || samePath(training, p)) continue;
                // The JVM writes <cache>.config during a training run's exit; anything else is left over from a killed run.
                if (name.endsWith(".config") && samePath(training, p.resolveSibling(name.substring(0, name.length() - ".config".length())))) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // still mapped by this JVM on Windows, or otherwise in use; retried next launch
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Reads a HotSpot flag. Returns null if the flag doesn't exist in this JVM and "" if it's unset.
     */
    private static String vmOption(String name) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            CompositeData option = (CompositeData) server.invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption", new Object[]{name}, new String[]{String.class.getName()});
            Object value = option.get("value");
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int javaVersion() {
        String spec = System.getProperty("java.specification.version", "");
        if (spec.startsWith("1.")) spec = spec.substring(2);
        return parseInt(spec);
    }

    private static boolean samePath(String a, Path b) {
        if (a == null || a.isEmpty()) return false;
        try {
            return Paths.get(a).toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String quoteIfNeeded(String path) {
        return path.indexOf(' ') >= 0 ? "\"" + path + "\"" : path;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Properties loadState(Path file) {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                p.clear();
            }
        }
        return p;
    }

    private static void saveState(Path file, Properties state) {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                state.store(out, null);
            }
        } catch (IOException e) {
            Log.debug(LogCategory.GENERAL, "[UltraFastLaunch] Could not save %s", file, e);
        }
    }
}
