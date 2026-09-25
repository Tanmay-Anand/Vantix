/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliTest {

    @TempDir
    Path project;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine cli = VantixCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--project-dir=" + project);
        all.add("--no-color");
        return cli.execute(all.toArray(String[]::new));
    }

    private void schema(String source) throws IOException {
        Path file = project.resolve("vantix/schema.vx");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static final String VALID = """
            generator { package = "com.example.app.model" }
            entity Book {
              id    Long   @id @generated
              isbn  String @unique
              title String
            }
            """;

    // ---- init --------------------------------------------------------------------------------

    @Test
    void initScaffoldsASchemaInTheApplicationsPackage() throws IOException {
        Path app = project.resolve("src/main/java/com/acme/shop/ShopApplication.java");
        Files.createDirectories(app.getParent());
        Files.writeString(app, "package com.acme.shop;\n\n@SpringBootApplication\nclass ShopApplication {}\n");

        assertThat(run("init")).isZero();

        assertThat(project.resolve("vantix/schema.vx")).content().contains("package = \"com.acme.shop.model\"");
        assertThat(project.resolve(".gitignore")).content().contains("target/generated-sources/vantix/");
        assertThat(out.toString()).contains("Created vantix/schema.vx");
    }

    @Test
    void initNeverOverwritesAnExistingSchemaWithoutForce() throws IOException {
        schema(VALID);
        assertThat(run("init")).isZero();
        assertThat(project.resolve("vantix/schema.vx")).hasContent(VALID);
        assertThat(out.toString()).contains("already exists");
    }

    @Test
    void initLeavesAGitignoreThatAlreadyCoversTargetAlone() throws IOException {
        Files.writeString(project.resolve(".gitignore"), "target/\n");
        run("init");
        assertThat(project.resolve(".gitignore")).hasContent("target/\n");
    }

    @Test
    void theInitTemplateValidatesAndGenerates() {
        assertThat(run("init", "--package=com.example.model")).isZero();
        assertThat(run("generate")).isZero();
        assertThat(project.resolve("target/generated-sources/vantix/com/example/model/User.java"))
                .exists();
        assertThat(err.toString()).isEmpty();
    }

    // ---- validate ----------------------------------------------------------------------------

    @Test
    void validateReportsSuccess() throws IOException {
        schema(VALID);
        assertThat(run("validate")).isZero();
        assertThat(out.toString()).contains("vantix/schema.vx is valid: 1 entity, 0 enums");
    }

    @Test
    void validatePrintsEveryDiagnosticAndExitsOne() throws IOException {
        schema("""
                generator { package = "com.example" }
                entity Book {
                  id    Long @id
                  title Strng
                  pages Int @lenght(3)
                }
                """);
        assertThat(run("validate")).isEqualTo(1);
        assertThat(err.toString())
                .contains("error: Unknown type `Strng`")
                .contains("--> vantix/schema.vx:4:9")
                .contains("did you mean `String`?")
                .contains("did you mean `@length`?")
                .contains("vantix/schema.vx has 2 errors");
    }

    @Test
    void validateExplainsAMissingSchema() {
        assertThat(run("validate")).isEqualTo(1);
        assertThat(err.toString()).contains("No schema found at vantix/schema.vx");
        assertThat(out.toString()).contains("vantix init");
    }

    @Test
    void validateHonoursAnExplicitSchemaPath() throws IOException {
        Files.writeString(project.resolve("other.vx"), VALID);
        assertThat(run("validate", "--schema=other.vx")).isZero();
    }

    @Test
    void validateEmitsMachineReadableJson() throws IOException {
        schema("generator { package = \"com.example\" }\nentity Book {\n  id Long @id\n  x Strng\n}\n");
        assertThat(run("validate", "--json")).isEqualTo(1);

        JsonNode json = JsonMapper.builder().build().readTree(out.toString());
        assertThat(json.get("ok").asBoolean()).isFalse();
        assertThat(json.get("errors").asInt()).isEqualTo(1);
        JsonNode d = json.get("diagnostics").get(0);
        assertThat(d.get("severity").asText()).isEqualTo("error");
        assertThat(d.get("line").asInt()).isEqualTo(4);
        assertThat(d.get("suggestion").asText()).isEqualTo("did you mean `String`?");
        assertThat(err.toString()).as("--json prints nothing else").isEmpty();
    }

    @Test
    void jsonIsLocaleIndependent() throws IOException {
        // Under tr-TR, "WARNING".toLowerCase() is "warnıng" (dotless ı): machine output must not change.
        schema("entity Book {\n  id Long @id\n}\n"); // no generator block: one warning
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(run("validate", "--json")).isZero();
        } finally {
            java.util.Locale.setDefault(previous);
        }
        JsonNode d = JsonMapper.builder()
                .build()
                .readTree(out.toString())
                .get("diagnostics")
                .get(0);
        assertThat(d.get("severity").asText()).isEqualTo("warning");
    }

    @Test
    void jsonCarriesSecondaryLocations() throws IOException {
        schema("""
                generator { package = "com.example" }
                entity Book {
                  id    Long @id
                  title String
                  title String
                }
                """);
        run("validate", "--json");
        JsonNode related = JsonMapper.builder()
                .build()
                .readTree(out.toString())
                .get("diagnostics")
                .get(0)
                .get("related");
        assertThat(related.get(0).get("line").asInt()).isEqualTo(4);
        assertThat(related.get(0).get("note").asText()).isEqualTo("first declared here");
    }

    // ---- generate ----------------------------------------------------------------------------

    @Test
    void generateWritesEntitiesAndRepositories() throws IOException {
        schema(VALID);
        assertThat(run("generate")).isZero();

        Path pkg = project.resolve("target/generated-sources/vantix/com/example/app/model");
        assertThat(pkg.resolve("Book.java")).content().contains("@Entity");
        assertThat(pkg.resolve("BookRepository.java")).content().contains("Optional<Book> findByIsbn(String isbn)");
        assertThat(out.toString()).contains("Generated 2 files").contains("(2 written, 0 unchanged)");
    }

    @Test
    void aSecondRunWritesNothing() throws IOException {
        schema(VALID);
        run("generate");
        out.getBuffer().setLength(0);

        assertThat(run("generate", "--verbose")).isZero();
        assertThat(out.toString()).contains("(0 written, 2 unchanged)").contains("unchanged target/generated-sources");
    }

    @Test
    void generateWritesNothingWhenTheSchemaHasErrors() throws IOException {
        schema("entity Book {\n  title Strng\n}\n");
        assertThat(run("generate")).isEqualTo(1);
        assertThat(project.resolve("target")).doesNotExist();
        assertThat(out.toString()).contains("Nothing was generated");
    }

    @Test
    void generateReportsFilesAsJson() throws IOException {
        schema(VALID);
        assertThat(run("generate", "--json")).isZero();

        JsonNode json = JsonMapper.builder().build().readTree(out.toString());
        assertThat(json.get("ok").asBoolean()).isTrue();
        assertThat(json.get("outputDirectory").asText()).isEqualTo("target/generated-sources/vantix");
        assertThat(json.get("files").get("written")).hasSize(2);
    }

    @Test
    void generateFailsWhenAHandWrittenClassWouldClash() throws IOException {
        schema(VALID);
        Path clash = project.resolve("src/main/java/com/example/app/model/Book.java");
        Files.createDirectories(clash.getParent());
        Files.writeString(clash, "package com.example.app.model;\nclass Book {}\n");

        assertThat(run("generate")).isEqualTo(1);
        assertThat(err.toString()).contains("also declares com.example.app.model.Book");
    }

    @Test
    void generationGapScaffoldsOnceIntoSrcMainJava() throws IOException {
        schema(VALID.replace(
                "package = \"com.example.app.model\"",
                "package = \"com.example.app.model\"\n  useGenerationGap = true"));
        assertThat(run("generate")).isZero();
        Path scaffold = project.resolve("src/main/java/com/example/app/model/Book.java");
        assertThat(scaffold).content().contains("class Book extends BookBase");
        assertThat(out.toString()).contains("scaffolded src/main/java/com/example/app/model/Book.java");
    }

    @Test
    void watchRegeneratesWhenTheSchemaChanges() throws Exception {
        schema(VALID);
        Thread watcher = new Thread(() -> run("generate", "--watch"));
        watcher.setDaemon(true);
        watcher.start();
        Path generated = project.resolve("target/generated-sources/vantix/com/example/app/model/Book.java");
        try {
            await(() -> Files.exists(generated) && out.toString().contains("Watching"));
            schema(VALID.replace("title String", "title String @length(300)"));
            await(() -> Files.readString(generated).contains("length = 300"));
        } finally {
            watcher.interrupt();
            watcher.join(Duration.ofSeconds(5).toMillis());
        }
        assertThat(watcher.isAlive()).isFalse();
    }

    /**
     * Many editors save by writing a temporary file and renaming it over the original. A watch on
     * the file itself loses track after the first such save; watching the directory does not.
     */
    @Test
    void watchSurvivesEditorsThatSaveByRenamingATempFile() throws Exception {
        schema(VALID);
        Thread watcher = new Thread(() -> run("generate", "--watch"));
        watcher.setDaemon(true);
        watcher.start();
        Path schema = project.resolve("vantix/schema.vx");
        Path generated = project.resolve("target/generated-sources/vantix/com/example/app/model/Book.java");
        try {
            await(() -> Files.exists(generated) && out.toString().contains("Watching"));
            for (int length : List.of(300, 400)) { // twice: the second save must still be seen
                Path temp = project.resolve("vantix/.schema.vx.tmp");
                Files.writeString(temp, VALID.replace("title String", "title String @length(" + length + ")"));
                Files.move(
                        temp,
                        schema,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                await(() -> Files.readString(generated).contains("length = " + length));
            }
        } finally {
            watcher.interrupt();
            watcher.join(Duration.ofSeconds(5).toMillis());
        }
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!check.ok()) {
            assertThat(System.nanoTime()).as("timed out waiting").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    // ---- root --------------------------------------------------------------------------------

    @Test
    void theRootCommandPrintsUsageListingThePhaseOneCommands() {
        CommandLine cli = VantixCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        assertThat(cli.execute()).isZero();
        assertThat(out.toString()).contains("init").contains("validate").contains("generate");
    }
}
