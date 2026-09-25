/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NamesTest {

    @ParameterizedTest
    @CsvSource({
        "id, id",
        "createdAt, created_at",
        "userId, user_id",
        "OrderItem, order_item",
        "HTTPServer, http_server",
        "addressLine2, address_line2",
        "already_snake, already_snake",
        "URL, url",
    })
    void convertsCamelCaseToSnakeCase(String input, String expected) {
        assertThat(Names.snakeCase(input)).isEqualTo(expected);
    }

    @Test
    void namesIndexesAndUniqueConstraintsLikePostgres() {
        assertThat(Names.indexName("users", List.of("created_at"), false)).isEqualTo("users_created_at_idx");
        assertThat(Names.indexName("seat", List.of("row", "number"), true)).isEqualTo("seat_row_number_key");
    }

    @Test
    void shortensOverlongIndexNamesDeterministically() {
        List<String> columns = List.of("a_very_long_column_name", "another_very_long_column_name", "third");
        String name = Names.indexName("an_equally_long_table_name", columns, false);
        assertThat(name).hasSize(63).isEqualTo(Names.indexName("an_equally_long_table_name", columns, false));
        assertThat(name).isNotEqualTo(Names.indexName("an_equally_long_table_name", List.of("x"), false));
    }

    @ParameterizedTest
    @CsvSource({"com.acme.shop, true", "shop, true", "com..acme, false", "com.acme.class, false", "1com, false"})
    void validatesJavaPackageNames(String name, boolean valid) {
        assertThat(Names.isJavaPackage(name)).isEqualTo(valid);
    }
}
