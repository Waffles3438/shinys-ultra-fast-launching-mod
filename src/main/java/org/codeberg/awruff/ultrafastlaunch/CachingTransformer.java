package org.codeberg.awruff.ultrafastlaunch;

import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

final class CachingTransformer implements InvocationHandler {
    private static final byte[][] UNCACHEABLE_MARKERS = {
            "org/spongepowered/asm/synthetic/".getBytes(StandardCharsets.US_ASCII),
            "mixinextras/sugar/".getBytes(StandardCharsets.US_ASCII),
    };
    private static final int GENERATED_SUFFIX_HEX_DIGITS = 32;

    private static final String OWN_PACKAGE = "org.codeberg.awruff.ultrafastlaunch.";
    private static final String OWN_PACKAGE_INTERNAL = OWN_PACKAGE.replace('.', '/');

    private final IMixinTransformer delegate;
    private final TransformCache cache;
    private final Benchmark benchmark;

    CachingTransformer(IMixinTransformer delegate, TransformCache cache, Benchmark benchmark) {
        this.delegate = delegate;
        this.cache = cache;
        this.benchmark = benchmark;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (args != null && args.length == 3 && name.equals("transformClassBytes")) {
            return transformClassBytes((String) args[0], (String) args[1], (byte[]) args[2]);
        }
        if (method.getDeclaringClass() == Object.class) {
            switch (name) {
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "UltraFastLaunch(" + delegate + ")";
            }
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private byte[] transformClassBytes(String name, String transformedName, byte[] input) {
        long start = System.nanoTime();
        int outcome = Benchmark.UNCACHED;
        try {
            if (input == null || cache == null || isOwnClass(name)) return delegate.transformClassBytes(name, transformedName, input);

            long crc = TransformCache.crc(input);
            byte[] cached = cache.lookup(name, input, crc);
            if (cached != null) {
                outcome = Benchmark.HIT;
                return cached;
            }

            byte[] output = delegate.transformClassBytes(name, transformedName, input);
            if (output != null && isCacheable(output)) {
                cache.store(name, input, crc, output);
                outcome = Benchmark.MISS;
            }
            return output;
        } finally {
            benchmark.record(System.nanoTime() - start, outcome);
        }
    }

    private static boolean isOwnClass(String name) {
        return name != null && (name.startsWith(OWN_PACKAGE) || name.startsWith(OWN_PACKAGE_INTERNAL));
    }

    private static boolean isCacheable(byte[] output) {
        for (byte[] marker : UNCACHEABLE_MARKERS) {
            if (contains(output, marker)) return false;
        }
        return !referencesGeneratedInnerClass(output);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        byte first = needle[0];
        outer:
        for (int i = 0, end = haystack.length - needle.length; i <= end; i++) {
            if (haystack[i] != first) continue;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // Mixin names the classes it generates from a mixin's inner classes Target$Name$<32 hex digits>, and registers them
    // only while it transforms Target. If Target came from the cache, Mixin can't generate that class.
    private static boolean referencesGeneratedInnerClass(byte[] bytes) {
        int run = -1;
        for (byte b : bytes) {
            if (b == '$') {
                run = 0;
            } else if (run >= 0 && ((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f'))) {
                run++;
            } else {
                if (run == GENERATED_SUFFIX_HEX_DIGITS) return true;
                run = -1;
            }
        }
        return run == GENERATED_SUFFIX_HEX_DIGITS;
    }
}
