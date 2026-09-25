/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dogfood application: boots on the entities and repositories that {@code vx-maven-plugin}
 * generates from {@code vantix/schema.vx}, and doubles as the end-to-end test bed
 * ({@code generate -> boot -> query} today; {@code migrate} joins in Phase 2).
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
