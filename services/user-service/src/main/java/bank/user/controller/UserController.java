package bank.user.controller;

import bank.user.domain.User;
import bank.user.dto.CreateUserRequest;
import bank.user.dto.UpdateUserRequest;
import bank.user.dto.UserResponse;
import bank.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse getUser(@PathVariable UUID id) {
        return UserResponse.from(userService.getUser(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(userService.updateUser(id, request));
    }

    @PatchMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> disableUser(@PathVariable UUID id) {
        userService.disableUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id) {
        userService.resetPassword(id);
        return ResponseEntity.accepted().build();
    }
}
