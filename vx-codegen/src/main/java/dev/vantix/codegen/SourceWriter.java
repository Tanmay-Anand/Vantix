/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes {@link GeneratedFile}s to disk without disturbing incremental builds.
 *
 * <ul>
 *   <li>A generated file whose content is byte-identical to what is already on disk is not written,
 *       so its timestamp does not change and the compiler does not rebuild everything that depends on
 *       it.
 *   <li>Changed files are written to a temporary sibling and moved into place, so a concurrent reader
 *       (an IDE, a watching compiler) never sees half a file.
 *   <li>Scaffolds (Generation Gap) are written only if absent — never overwritten.
 *   <li>Generated files left over from a previous run (an entity was removed or renamed) are deleted,
 *       but only if they start with Vantix's generated-file marker.
 * </ul>
 */
public final class SourceWriter {

    private static final Pattern TABLE = Pattern.compile("@Table\\(name = \"[^\"]*\"\\)");

    private SourceWriter() {}

    /**
     * What a write did. {@code conflicts} are problems that will break the user's compile (e.g. a
     * hand-written class with the same name as a generated one); {@code warnings} will not.
     */
    public record Report(
            List<Path> written,
            List<Path> unchanged,
            List<Path> scaffolded,
            List<Path> deleted,
            List<String> warnings,
            List<String> conflicts) {

        public Report {
            written = List.copyOf(written);
            unchanged = List.copyOf(unchanged);
            scaffolded = List.copyOf(scaffolded);
            deleted = List.copyOf(deleted);
            warnings = List.copyOf(warnings);
            conflicts = List.copyOf(conflicts);
        }

        public int total() {
            return written.size() + unchanged.size() + scaffolded.size();
        }
    }

    /**
     * @param outputDirectory where regenerated sources go (e.g. {@code target/generated-sources/vantix})
     * @param scaffoldDirectory the user's source root for write-once scaffolds (e.g. {@code src/main/java})
     */
    public static Report write(List<GeneratedFile> files, Path outputDirectory, Path scaffoldDirectory)
            throws IOException {
        List<Path> written = new ArrayList<>();
        List<Path> unchanged = new ArrayList<>();
        List<Path> scaffolded = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Set<Path> produced = new HashSet<>();

        for (GeneratedFile file : files) {
            byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
            if (file.scaffold()) {
                Path target = scaffoldDirectory.resolve(file.relativePath());
                if (Files.exists(target)) {
                    checkScaffold(file, target, warnings);
                } else {
                    writeAtomically(target, content);
                    scaffolded.add(target);
                }
                continue;
            }
            Path target = outputDirectory.resolve(file.relativePath());
            produced.add(target.toAbsolutePath().normalize());
            Path handWritten = scaffoldDirectory.resolve(file.relativePath());
            if (Files.exists(handWritten) && !isGenerated(handWritten)) {
                conflicts.add(handWritten + " also declares " + file.packageName() + "." + file.simpleName()
                        + ", which Vantix generates; delete it, or set `useGenerationGap = true` to keep"
                        + " custom code in a subclass");
            }
            if (Files.exists(target) && sameContent(target, content)) {
                unchanged.add(target);
            } else {
                writeAtomically(target, content);
                written.add(target);
            }
        }
        List<Path> deleted = pruneStale(outputDirectory, produced);
        conflicts.addAll(orphanedScaffolds(files, scaffoldDirectory));
        return new Report(written, unchanged, scaffolded, deleted, warnings, conflicts);
    }

    /**
     * A Generation Gap scaffold ({@code class Book extends BookBase}) whose base class is no longer
     * generated — its entity was removed or renamed in the schema. Without this check the user would
     * only see javac's "cannot find symbol" with no hint of why.
     */
    private static List<String> orphanedScaffolds(List<GeneratedFile> files, Path scaffoldDirectory)
            throws IOException {
        Set<String> generatedNames = new HashSet<>();
        Set<Path> packageDirs = new HashSet<>();
        for (GeneratedFile f : files) {
            generatedNames.add(f.packageName() + "." + f.simpleName());
            packageDirs.add(scaffoldDirectory.resolve(f.relativePath()).getParent());
        }
        List<String> orphans = new ArrayList<>();
        for (Path dir : packageDirs.stream().sorted().toList()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> sources;
            try (Stream<Path> list = Files.list(dir)) {
                sources = list.filter(p -> p.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }
            String pkg = files.stream()
                    .filter(f -> scaffoldDirectory
                            .resolve(f.relativePath())
                            .getParent()
                            .equals(dir))
                    .findFirst()
                    .orElseThrow()
                    .packageName();
            for (Path source : sources) {
                String className = source.getFileName().toString().replace(".java", "");
                String base = className + "Base";
                if (generatedNames.contains(pkg + "." + base) || Files.exists(dir.resolve(base + ".java"))) {
                    continue;
                }
                String text = Files.readString(source, StandardCharsets.ISO_8859_1);
                boolean scaffoldShape = Pattern.compile("\\bclass\\s+" + className + "\\s+extends\\s+" + base + "\\b")
                        .matcher(text)
                        .find();
                boolean importsItsBase = Pattern.compile(
                                "^\\s*import\\s+[\\w.]+\\." + base + "\\s*;", Pattern.MULTILINE)
                        .matcher(text)
                        .find();
                if (scaffoldShape && !importsItsBase) {
                    orphans.add(source + " extends " + base + ", which Vantix no longer generates (was entity `"
                            + className + "` removed or renamed in schema.vx?); delete the file, or restore the"
                            + " entity");
                }
            }
        }
        return orphans;
    }

    private static boolean sameContent(Path target, byte[] content) throws IOException {
        return Files.size(target) == content.length && Arrays.equals(Files.readAllBytes(target), content);
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".vantix-", ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The scaffold's {@code @Table} is the one thing in it that must track the schema. */
    private static void checkScaffold(GeneratedFile file, Path existing, List<String> warnings) throws IOException {
        Matcher expected = TABLE.matcher(file.content());
        if (expected.find()
                && !Files.readString(existing, StandardCharsets.UTF_8).contains(expected.group())) {
            warnings.add(existing + " should declare " + expected.group() + " to match schema.vx");
        }
    }

    private static List<Path> pruneStale(Path outputDirectory, Set<Path> produced) throws IOException {
        List<Path> deleted = new ArrayList<>();
        if (!Files.isDirectory(outputDirectory)) {
            return deleted;
        }
        List<Path> candidates;
        try (Stream<Path> walk = Files.walk(outputDirectory)) {
            candidates = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !produced.contains(p.toAbsolutePath().normalize()))
                    .sorted()
                    .toList();
        }
        for (Path p : candidates) {
            if (isGenerated(p)) {
                Files.delete(p);
                deleted.add(p);
            }
        }
        return deleted;
    }

    /** Latin-1 so a user file in any encoding can be checked; the marker itself is ASCII. */
    private static boolean isGenerated(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String first = reader.readLine();
            return first != null && first.startsWith(Sources.MARKER);
        }
    }
}
