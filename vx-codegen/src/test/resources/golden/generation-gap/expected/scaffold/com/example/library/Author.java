package com.example.library;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The {@code Author} entity. Its persistent state lives in the generated {@link AuthorBase}.
 *
 * <p>This file is yours: Vantix created it once and will never modify it.
 * Add methods freely. If you rename the table with {@code @@table} in {@code schema.vx},
 * update {@code @Table} here to match.
 */
@Entity
@Table(name = "authors")
public class Author extends AuthorBase {
}
