/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.demo;

import com.example.demo.model.Order;
import com.example.demo.model.OrderRepository;
import com.example.demo.model.Profile;
import com.example.demo.model.ProfileRepository;
import com.example.demo.model.User;
import com.example.demo.model.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hand-written application code over the Vantix-generated entities and repositories (everything in
 * {@code com.example.demo.model} is generated from {@code vantix/schema.vx}). If a schema or
 * generator change breaks the generated API, this class stops compiling.
 */
@Service
public class ShopService {

    private final UserRepository users;
    private final OrderRepository orders;
    private final ProfileRepository profiles;

    public ShopService(UserRepository users, OrderRepository orders, ProfileRepository profiles) {
        this.users = users;
        this.orders = orders;
        this.profiles = profiles;
    }

    @Transactional
    public User register(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        return users.saveAndFlush(user);
    }

    @Transactional
    public Profile writeBio(Long userId, String bio) {
        Profile profile = new Profile();
        profile.setUser(users.getReferenceById(userId));
        profile.setBio(bio);
        return profiles.saveAndFlush(profile);
    }

    @Transactional
    public Order placeOrder(Long userId, BigDecimal total) {
        Order order = new Order();
        order.setUser(users.getReferenceById(userId));
        order.setTotal(total);
        return orders.saveAndFlush(order);
    }

    @Transactional(readOnly = true)
    public List<Order> ordersOf(String email) {
        return users.findByEmail(email).map(u -> List.copyOf(u.getOrders())).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public Optional<String> bioOf(Long userId) {
        return profiles.findByUserId(userId).map(Profile::getBio);
    }
}
