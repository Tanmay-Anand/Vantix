/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceWriterTest {

    @TempDir
    Path project;

    private Path out;
    private Path src;

    @BeforeEach
    void dirs() {
        out = project.resolve("target/generated-sources/vantix");
        src = project.resolve("src/main/java");
    }

    private static GeneratedFile generated(String name, String body) {
        return new GeneratedFile("com.example", name, Sources.MARKER + " x\n" + body, false);
    }

    private static GeneratedFile scaffold(String name, String table) {
        return new GeneratedFile(
                "com.example", name, "@Entity\n@Table(name = \"" + table + "\")\nclass " + name + " {}\n", true);
    }

    @Test
    void writesNewFilesUnderThePackagePath() throws IOException {
        SourceWriter.Report report = SourceWriter.write(List.of(generated("User", "a")), out, src);

        Path file = out.resolve("com/example/User.java");
        assertThat(report.written()).containsExactly(file);
        assertThat(file).content().endsWith("a");
    }

    @Test
    void leavesIdenticalContentUntouchedToPreserveIncrementalBuilds() throws IOException {
        SourceWriter.write(List.of(generated("User", "a")), out, src);
        Path file = out.resolve("com/example/User.java");
        FileTime old = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, old);

        SourceWriter.Report report = SourceWriter.write(List.of(generated("User", "a")), out, src);

        assertThat(report.written()).isEmpty();
        assertThat(report.unchanged()).containsExactly(file);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(old);
    }

    @Test
    void rewritesChangedContent() throws IOException {
        SourceWriter.write(List.of(generated("User", "a")), out, src);
        SourceWriter.Report report = SourceWriter.write(List.of(generated("User", "b")), out, src);

        assertThat(report.written()).hasSize(1);
        assertThat(out.resolve("com/example/User.java")).content().endsWith("b");
    }

    @Test
    void deletesStaleGeneratedFilesButNeverForeignOnes() throws IOException {
        SourceWriter.write(List.of(generated("User", "a"), generated("Post", "p")), out, src);
        Path foreign = out.resolve("com/example/Handwritten.java");
        Files.writeString(foreign, "class Handwritten {}\n");

        SourceWriter.Report report = SourceWriter.write(List.of(generated("User", "a")), out, src);

        assertThat(report.deleted()).containsExactly(out.resolve("com/example/Post.java"));
        assertThat(out.resolve("com/example/Post.java")).doesNotExist();
        assertThat(foreign).exists();
    }

    @Test
    void writesScaffoldsOnceAndNeverOverwritesThem() throws IOException {
        SourceWriter.Report first = SourceWriter.write(List.of(scaffold("User", "users")), out, src);
        Path file = src.resolve("com/example/User.java");
        assertThat(first.scaffolded()).containsExactly(file);

        String edited = Files.readString(file).replace("{}", "{ String greet() { return \"hi\"; } }");
        Files.writeString(file, edited);
        SourceWriter.Report second = SourceWriter.write(List.of(scaffold("User", "users")), out, src);

        assertThat(second.scaffolded()).isEmpty();
        assertThat(second.warnings()).isEmpty();
        assertThat(file).hasContent(edited);
    }

    @Test
    void warnsWhenAScaffoldsTableNoLongerMatchesTheSchema() throws IOException {
        SourceWriter.write(List.of(scaffold("User", "users")), out, src);

        SourceWriter.Report report = SourceWriter.write(List.of(scaffold("User", "accounts")), out, src);

        assertThat(report.warnings()).singleElement().asString().contains("@Table(name = \"accounts\")");
    }

    @Test
    void reportsAScaffoldWhoseEntityWasRemoved() throws IOException {
        Path scaffold = src.resolve("com/example/Book.java");
        Files.createDirectories(scaffold.getParent());
        Files.writeString(scaffold, "package com.example;\n@Entity\npublic class Book extends BookBase {}\n");
        Files.writeString(src.resolve("com/example/Shelf.java"), "package com.example;\nclass Shelf {}\n");

        // Book is gone from the schema; only User is still generated.
        SourceWriter.Report report = SourceWriter.write(List.of(generated("User", "a")), out, src);

        assertThat(report.conflicts())
                .singleElement()
                .asString()
                .contains("Book.java extends BookBase")
                .contains("removed or renamed");
    }

    @Test
    void aClassExtendingItsOwnHandWrittenBaseIsNotAnOrphan() throws IOException {
        Path dir = src.resolve("com/example");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Report.java"), "package com.example;\nclass Report extends ReportBase {}\n");
        Files.writeString(dir.resolve("ReportBase.java"), "package com.example;\nclass ReportBase {}\n");
        Files.writeString(
                dir.resolve("Audit.java"),
                "package com.example;\nimport com.other.AuditBase;\nclass Audit extends AuditBase {}\n");

        assertThat(SourceWriter.write(List.of(generated("User", "a")), out, src).conflicts())
                .isEmpty();
    }

    @Test
    void reportsAHandWrittenClassThatWouldDuplicateAGeneratedOne() throws IOException {
        Path handWritten = src.resolve("com/example/User.java");
        Files.createDirectories(handWritten.getParent());
        Files.writeString(handWritten, "package com.example;\nclass User {}\n");

        SourceWriter.Report report = SourceWriter.write(List.of(generated("User", "a")), out, src);

        assertThat(report.conflicts()).singleElement().asString().contains("useGenerationGap");
    }
}
