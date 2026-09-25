/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import java.nio.file.Path;

/**
 * One Java source file produced by the generator.
 *
 * <p>A {@code scaffold} file (the editable {@code User} half of the Generation Gap pattern) is
 * written once into the user's source tree and never touched again; every other file is disposable
 * output that is rewritten whenever its content changes.
 *
 * @param packageName the file's package
 * @param simpleName the top-level type's simple name
 * @param content the complete source text ({@code \n} line endings)
 * @param scaffold whether this is a write-once, user-owned file
 */
public record GeneratedFile(String packageName, String simpleName, String content, boolean scaffold) {

    /** The path relative to a source root, e.g. {@code com/acme/shop/User.java}. */
    public Path relativePath() {
        return Path.of(packageName.replace('.', '/'), simpleName + ".java");
    }
}
