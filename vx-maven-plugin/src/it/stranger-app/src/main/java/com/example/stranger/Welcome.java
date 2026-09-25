package com.example.stranger;

import com.example.stranger.model.User;
import com.example.stranger.model.UserRepository;

/** Only compiles if `vantix:init` + `compile` produced the entity and repository on the source path. */
class Welcome {
    private final UserRepository users;

    Welcome(UserRepository users) {
        this.users = users;
    }

    String greet(String email) {
        return users.findByEmail(email).map(User::getName).map(n -> "Hello, " + n).orElse("Who?");
    }
}
