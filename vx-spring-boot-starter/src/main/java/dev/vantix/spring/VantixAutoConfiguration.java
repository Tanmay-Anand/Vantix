/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Auto-configuration entry point for Vantix in a Spring Boot application. Phase 0 skeleton: registers
 * no beans yet. Phase 3+ wires the {@code VantixClient} query bean (from the app's
 * {@code EntityManagerFactory}) and Phase 4 wires the Hibernate error translator.
 */
@AutoConfiguration
public class VantixAutoConfiguration {}
