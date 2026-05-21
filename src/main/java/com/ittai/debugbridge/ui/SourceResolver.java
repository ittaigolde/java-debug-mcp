package com.ittai.debugbridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class SourceResolver {

    public record Result(boolean available, String path, int hitLine, List<Line> lines, String message) {}
    public record Line(int number, String text) {}

    private static final int CONTEXT_BEFORE = 10;
    private static final int CONTEXT_AFTER = 10;

    private final List<Path> roots = new ArrayList<>();
    private final Map<String, Path> resolveCache = new ConcurrentHashMap<>();

    public SourceResolver() {
        String prop = System.getProperty("debug-bridge.sourcepath");
        if (prop != null && !prop.isBlank()) {
            for (String part : prop.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) addRoot(part.trim());
            }
        }
        if (roots.isEmpty()) addHeuristicRoots();
    }

    public synchronized List<String> rootsAsStrings() {
        List<String> out = new ArrayList<>();
        for (Path p : roots) out.add(p.toString());
        return out;
    }

    public synchronized List<String> setRoots(List<String> paths) {
        roots.clear();
        resolveCache.clear();
        for (String p : paths) addRoot(p);
        if (roots.isEmpty()) addHeuristicRoots();
        return rootsAsStrings();
    }

    public synchronized List<String> addRoots(List<String> paths) {
        for (String p : paths) addRoot(p);
        resolveCache.clear();
        return rootsAsStrings();
    }

    private void addRoot(String p) {
        Path path = Paths.get(p).toAbsolutePath().normalize();
        if (Files.isDirectory(path) && !roots.contains(path)) roots.add(path);
    }

    private void addHeuristicRoots() {
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        String[] candidates = {"src/main/java", "src/test/java", "src", "examples"};
        for (String c : candidates) {
            Path p = cwd.resolve(c);
            if (Files.isDirectory(p)) roots.add(p);
        }
        // Also scan one level deep for multi-module projects.
        try (Stream<Path> children = Files.list(cwd)) {
            children.filter(Files::isDirectory).forEach(child -> {
                for (String c : new String[]{"src/main/java", "src/test/java"}) {
                    Path p = child.resolve(c);
                    if (Files.isDirectory(p) && !roots.contains(p)) roots.add(p);
                }
            });
        } catch (IOException ignored) {}
    }

    public Result resolve(String classFqn, String sourceName, int hitLine) {
        String cacheKey = classFqn + "|" + sourceName;
        Path resolved = resolveCache.get(cacheKey);
        if (resolved == null) {
            resolved = search(classFqn, sourceName);
            if (resolved != null) resolveCache.put(cacheKey, resolved);
        }
        if (resolved == null) {
            return new Result(false, null, hitLine, List.of(),
                "source not found in roots: " + rootsAsStrings());
        }
        try {
            List<String> all = Files.readAllLines(resolved);
            int from = Math.max(0, hitLine - 1 - CONTEXT_BEFORE);
            int to = Math.min(all.size(), hitLine - 1 + CONTEXT_AFTER + 1);
            List<Line> window = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                window.add(new Line(i + 1, all.get(i)));
            }
            return new Result(true, resolved.toString(), hitLine, window, null);
        } catch (IOException e) {
            return new Result(false, resolved.toString(), hitLine, List.of(),
                "read failed: " + e.getMessage());
        }
    }

    private Path search(String classFqn, String sourceName) {
        String pkgPath = packageFromFqn(classFqn).replace('.', '/');
        for (Path root : roots) {
            Path candidate = pkgPath.isEmpty()
                ? root.resolve(sourceName)
                : root.resolve(pkgPath).resolve(sourceName);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        // Last-ditch: bare filename search under each root (shallow).
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root, 6)) {
                Path found = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(sourceName))
                    .findFirst().orElse(null);
                if (found != null) return found;
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static String packageFromFqn(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
