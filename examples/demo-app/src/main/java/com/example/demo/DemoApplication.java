/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dogfood application. From Phase 1 this boots against Vantix-generated entities and repositories
 * and doubles as the end-to-end integration test ({@code init -> generate -> migrate -> boot ->
 * query}). In Phase 0 it exists only to prove the generated-code contract compiles against the
 * Spring Boot baseline.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
