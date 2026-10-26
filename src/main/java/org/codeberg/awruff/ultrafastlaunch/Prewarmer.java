package org.codeberg.awruff.ultrafastlaunch;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class Prewarmer {
    private static final long MAX_TOTAL_BYTES = 2L << 30;
    private static final int MAX_WALK_DEPTH = 32;

    private Prewarmer() {
    }

    static void start() {
        List<Path> roots = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.getOrigin().getKind() == ModOrigin.Kind.PATH) roots.addAll(mod.getOrigin().getPaths());
        }
        roots.addAll(FabricLauncherBase.getLauncher().getClassPath());

        Thread thread = new Thread(() -> warm(roots), "UltraFastLaunch prewarm");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void warm(List<Path> roots) {
        try {
            Set<Path> files = new LinkedHashSet<>();
            for (Path root : roots) collect(root, files);
            ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
            long remaining = MAX_TOTAL_BYTES;
            for (Path p : files) {
                if (remaining <= 0) break;
                remaining -= read(p, buf, remaining);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collect(Path p, Set<Path> out) {
        Path abs = p.toAbsolutePath().normalize();
        if (Files.isRegularFile(abs)) {
            out.add(abs);
        } else if (Files.isDirectory(abs)) {
            try (Stream<Path> walk = Files.walk(abs, MAX_WALK_DEPTH)) {
                walk.filter(Files::isRegularFile).forEach(out::add);
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static long read(Path p, ByteBuffer buf, long limit) {
        long read = 0;
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            int n;
            while (read < limit) {
                buf.clear();
                if ((n = ch.read(buf)) < 0) break;
                read += n;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return read;
    }
}
