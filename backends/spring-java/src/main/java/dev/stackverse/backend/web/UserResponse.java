package dev.stackverse.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(String username, String name, String email, List<String> roles) {
}
