/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Vantix Studio data browser, launched by {@code vantix studio}. Phase 0 skeleton: boots an
 * empty context. Phase 4 adds localhost binding, a session token, CSRF, and the JDBC-backed table
 * browse/edit REST API consumed by the React frontend.
 */
@SpringBootApplication
public class VantixStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(VantixStudioApplication.class, args);
    }
}
