/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateMojoTest {

    @TempDir
    Path basedir;

    private GenerateMojo mojo() {
        MavenProject project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        GenerateMojo mojo = new GenerateMojo();
        mojo.project = project;
        mojo.schemaPath = "vantix/schema.vx";
        return mojo;
    }

    private void schema(String source) throws IOException {
        Path file = basedir.resolve("vantix/schema.vx");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void generatesAndRegistersTheOutputAsACompileSourceRoot() throws Exception {
        schema("""
                generator { package = "com.example" }
                entity Book {
                  id Long @id @generated
                }
                """);
        GenerateMojo mojo = mojo();

        mojo.execute();

        Path out = basedir.resolve("target/generated-sources/vantix");
        assertThat(out.resolve("com/example/Book.java")).exists();
        assertThat(mojo.project.getCompileSourceRoots()).contains(out.toString());
    }

    @Test
    void failsTheBuildOnSchemaErrors() throws IOException {
        schema("generator { package = \"com.example\" }\nentity Book {\n  id Long @id\n  x Strng\n}\n");

        assertThatThrownBy(() -> mojo().execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("1 error(s)");
        assertThat(basedir.resolve("target")).doesNotExist();
    }

    @Test
    void explainsAMissingSchema() {
        assertThatThrownBy(() -> mojo().execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("mvn vantix:init");
    }

    @Test
    void canBeSkipped() throws Exception {
        GenerateMojo mojo = mojo();
        mojo.skip = true;
        mojo.execute(); // no schema, but skipped: no failure
        assertThat(mojo.project.getCompileSourceRoots()).isEmpty();
    }
}
