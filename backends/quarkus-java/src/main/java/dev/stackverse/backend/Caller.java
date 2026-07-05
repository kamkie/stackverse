package dev.stackverse.backend;

import java.util.List;

record Caller(String username, List<String> roles, String name, String email) {
}
