/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.model.Order;
import com.example.demo.model.OrderStatus;
import com.example.demo.model.User;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code equals}/{@code hashCode}/{@code toString} contract for instances that never
 * touch a database: transient, id-assigned, and across entity types. The proxy and detached cases
 * need Hibernate and are in {@link DemoApplicationTest}.
 */
class EntityEqualityTest {

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Test
    void anInstanceEqualsItself() {
        User u = new User();
        // A second reference, not `isEqualTo(u)`: the point is to exercise equals()' `this == o`
        // branch for an unsaved instance (null id), which a self-assertion would short-circuit.
        Object sameInstance = u;
        assertThat(u.equals(sameInstance)).isTrue();
    }

    @Test
    void transientInstancesAreNeverEqualToEachOther() {
        // Two unsaved rows are different rows, even with identical field values.
        assertThat(new User()).isNotEqualTo(new User());
    }

    @Test
    void instancesWithTheSameIdAreEqual() {
        assertThat(user(7L)).isEqualTo(user(7L)).hasSameHashCodeAs(user(7L));
        assertThat(user(7L)).isNotEqualTo(user(8L));
    }

    @Test
    void differentEntityTypesWithTheSameIdAreNotEqual() {
        Order order = new Order();
        order.setId(7L);
        assertThat((Object) order).isNotEqualTo(user(7L));
        assertThat(user(7L)).isNotEqualTo(null);
    }

    @Test
    void hashCodeDoesNotChangeWhenTheIdIsAssigned() {
        User u = new User();
        Set<User> set = new HashSet<>();
        set.add(u);
        int before = u.hashCode();

        u.setId(42L); // what persist does

        assertThat(u.hashCode()).isEqualTo(before);
        assertThat(set).contains(u);
    }

    @Test
    void toStringShowsScalarsButNeverRelations() {
        User u = user(1L);
        u.setEmail("ada@example.com");
        assertThat(u.toString())
                .contains("id=1")
                .contains("email=ada@example.com")
                .doesNotContain("orders");
    }

    @Test
    void databaseDefaultsAreMirroredInJava() {
        Order order = new Order();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getPlacedAt()).isNotNull();
        assertThat(new User().getOrders()).isEmpty();
    }

    @Test
    void theForeignKeyMirrorFollowsTheRelationBeforeAnythingIsSaved() {
        Order order = new Order();
        assertThat(order.getUserId()).isNull();
        order.setUser(user(9L));
        assertThat(order.getUserId()).isEqualTo(9L);
    }
}
