/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.model.Order;
import com.example.demo.model.OrderRepository;
import com.example.demo.model.User;
import com.example.demo.model.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Phase 1 end-to-end check: a Spring Boot application boots on Vantix-generated entities and
 * repositories against real PostgreSQL, and the generated mappings, finders, defaults and
 * {@code equals}/{@code hashCode} behave correctly with real Hibernate proxies and sessions.
 *
 * <p>Skipped (not failed) when Docker is unavailable; CI always has Docker.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers(disabledWithoutDocker = true)
class DemoApplicationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ShopService shop;

    @Autowired
    UserRepository users;

    @Autowired
    OrderRepository orders;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    private static String unique(String name) {
        return name + "-" + System.nanoTime() + "@example.com";
    }

    @Test
    void bootsOnTheGeneratedEntities() {
        assertThat(emf.getMetamodel().getEntities())
                .extracting(e -> e.getJavaType().getSimpleName())
                .containsExactlyInAnyOrder("User", "Profile", "Order");
    }

    @Test
    void savesAndFindsThroughTheDerivedUniqueFinder() {
        User saved = shop.register(unique("ada"), "Ada");

        User found = users.findByEmail(saved.getEmail()).orElseThrow();
        assertThat(found).isEqualTo(saved);
        assertThat(found.getId()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void enforcesTheUniqueConstraint() {
        String email = unique("dup");
        shop.register(email, "First");
        assertThatThrownBy(() -> shop.register(email, "Second")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mapsOneToManyBothWays() {
        User user = shop.register(unique("grace"), "Grace");
        Order first = shop.placeOrder(user.getId(), new BigDecimal("19.99"));
        shop.placeOrder(user.getId(), new BigDecimal("5.00"));

        List<Order> placed = shop.ordersOf(user.getEmail());

        assertThat(placed).hasSize(2).contains(first);
        Order reloaded = orders.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo(user.getId());
        assertThat(reloaded.getTotal()).isEqualByComparingTo("19.99");
    }

    @Test
    void mapsOneToOneAndItsUniqueForeignKeyFinder() {
        User user = shop.register(unique("linus"), "Linus");
        shop.writeBio(user.getId(), "Kernel person");

        assertThat(shop.bioOf(user.getId())).contains("Kernel person");
    }

    @Test
    void storesEnumsAsTheirNames() {
        User user = shop.register(unique("enum"), "Enum");
        Order order = shop.placeOrder(user.getId(), BigDecimal.ONE);

        String status = jdbc.queryForObject("select status from orders where id = ?", String.class, order.getId());
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void createsIndexesWithVantixsDeterministicNames() {
        List<String> names =
                jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String.class);
        assertThat(names).contains("users_created_at_idx", "orders_user_id_placed_at_idx");
    }

    @Test
    void updatedAtAdvancesOnUpdate() throws InterruptedException {
        User user = shop.register(unique("upd"), "Before");
        Instant first = user.getUpdatedAt();
        Thread.sleep(5);

        tx.executeWithoutResult(s -> users.findById(user.getId()).orElseThrow().setName("After"));

        assertThat(users.findById(user.getId()).orElseThrow().getUpdatedAt()).isAfter(first);
    }

    @Test
    void aLazyProxyEqualsTheLoadedEntityWithoutBeingInitialized() {
        Long id = shop.register(unique("proxy"), "Proxy").getId();

        EntityManager em = emf.createEntityManager();
        try {
            User proxy = em.getReference(User.class, id);
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
            assertThat(proxy.getClass()).isNotEqualTo(User.class); // really a proxy

            User loaded = users.findById(id).orElseThrow(); // a different session
            assertThat(proxy).isEqualTo(loaded);
            assertThat(loaded).isEqualTo(proxy);
            assertThat(proxy.hashCode()).isEqualTo(loaded.hashCode());
            // equals/hashCode must not have loaded the row.
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
        } finally {
            em.close();
        }
    }

    @Test
    void twoUninitializedProxiesOfTheSameRowAreEqual() {
        Long id = shop.register(unique("twoproxies"), "Two").getId();
        Long other = shop.register(unique("otherproxy"), "Other").getId();

        EntityManager first = emf.createEntityManager();
        EntityManager second = emf.createEntityManager();
        try {
            User a = first.getReference(User.class, id);
            User b = second.getReference(User.class, id);
            User c = second.getReference(User.class, other);

            // Equality must be decided by id: an implementation that reads `other.id` as a field sees
            // null on an uninitialized proxy and would return false here without loading anything.
            assertThat(a).isEqualTo(b).isNotEqualTo(c);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
            assertThat(Hibernate.isInitialized(a)).isFalse();
            assertThat(Hibernate.isInitialized(b)).isFalse();
            assertThat(Hibernate.isInitialized(c)).isFalse();
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void aProxyGoesIntoAHashSetWithoutBeingLoaded() {
        Long id = shop.register(unique("hashset"), "Hash").getId();
        EntityManager em = emf.createEntityManager();
        try {
            User proxy = em.getReference(User.class, id);
            Set<User> set = new HashSet<>();

            set.add(proxy); // hashCode() on the proxy

            assertThat(Hibernate.isInitialized(proxy)).isFalse();
            assertThat(set).contains(users.findById(id).orElseThrow());
        } finally {
            em.close();
        }
    }

    @Test
    void aDetachedEntityEqualsTheManagedOneWithTheSameId() {
        Long id = shop.register(unique("managed"), "Managed").getId();
        User detached = users.findById(id).orElseThrow(); // its session is already closed

        tx.executeWithoutResult(status -> {
            User managed = users.findById(id).orElseThrow();
            assertThat(managed).isNotSameAs(detached);
            assertThat(managed).isEqualTo(detached);
            assertThat(detached).isEqualTo(managed);
            assertThat(managed.hashCode()).isEqualTo(detached.hashCode());
        });
    }

    /**
     * Characterises a Hibernate limitation the analyzer warns about: without bytecode enhancement
     * the inverse side of a one-to-one ({@code User.profile}) cannot be lazy, because Hibernate must
     * query to know whether to put a proxy or null there. Loading N users costs N extra queries. If
     * a Hibernate upgrade ever makes this lazy, this test fails and the warning should be revisited.
     */
    @Test
    void loadingEntitiesWithAnInverseOneToOneCostsOneQueryPerRow() {
        List<Long> ids = List.of(
                shop.register(unique("n1-a"), "A").getId(),
                shop.register(unique("n1-b"), "B").getId(),
                shop.register(unique("n1-c"), "C").getId());
        shop.writeBio(ids.get(0), "has a profile");
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

        stats.clear();
        tx.executeWithoutResult(status -> assertThat(users.findAllById(ids)).hasSize(3));
        long withInverse = stats.getPrepareStatementCount();

        stats.clear();
        tx.executeWithoutResult(status -> assertThat(orders.findAll()).isNotNull());
        long withoutInverse = stats.getPrepareStatementCount();

        assertThat(withoutInverse).isEqualTo(1);
        assertThat(withInverse).isEqualTo(1 + ids.size());
    }

    @Test
    void detachedCopiesOfTheSameRowAreEqual() {
        Long id = shop.register(unique("detached"), "Detached").getId();

        User a = users.findById(id).orElseThrow();
        User b = users.findById(id).orElseThrow();

        assertThat(a).isNotSameAs(b).isEqualTo(b);
        assertThat(Set.of(a)).contains(b);
    }

    @Test
    void anEntityAddedToASetBeforeSavingIsStillFoundAfter() {
        User user = new User();
        user.setEmail(unique("set"));
        user.setName("Set");
        Set<User> set = new HashSet<>();
        set.add(user);

        User saved = users.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(set).contains(saved);
    }
}
