package com.example.demo.controller;

import com.example.demo.dto.user.BatchUpdateUser;
import com.example.demo.dto.user.CreateUser;
import com.example.demo.dto.user.UpdateUser;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Tag(name = "user management", description = "user management description")
@RequestMapping("/api")
@Validated
public class UserController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/users")
    @Operation(summary = "get user list", description = "get user list description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = User.class)))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<User>> getUserList(){
        List<User> userList = userService.get();
        return ResponseEntity.ok(userList);
    }

    @GetMapping("/users/count")
    @Operation(summary = "get user count", description = "get user count description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = Long.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<Long> getUserCount(){
        Long userCount = userService.count();
        return ResponseEntity.ok(userCount);
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "get user by id", description = "get user by id description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<User> getUserById(@PathVariable Long id){
        User user = userService.get(id);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/users/search")
    @Operation(summary = "get user list by ids", description = "get user list by ids description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = User.class)))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<User>> getUserListByIds(@RequestBody List<Long> ids){
        List<User> userList = userService.get(ids);
        return ResponseEntity.ok(userList);
    }

    @GetMapping("/users/{id}/exists")
    @Operation(summary = "check if user exists", description = "check if user exists description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = Boolean.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<Boolean> existUserById(@PathVariable Long id){
        boolean userExist = userService.exist(id);
        return ResponseEntity.ok(userExist);
    }

    @PostMapping("/users")
    @Operation(summary = "create a user", description = "create a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Success", content = @Content(schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<User> createUser(@Valid @RequestBody CreateUser createUser){
        User user = new User();
        user.setFirstName(createUser.getFirstName());
        user.setLastName(createUser.getLastName());
        user.setUsername(createUser.getUsername());
        user.setEmail(createUser.getEmail());
        user.setPassword(passwordEncoder.encode(createUser.getPassword()));
        user.setRole(createUser.getRole());
        User createdUser = userService.create(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @PostMapping("/users/batch")
    @Operation(summary = "create users", description = "create users description")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = User.class)))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<User>> createUsers(@Valid @RequestBody List<@Valid CreateUser> users){
        List<User> createUsers = users.stream().map(user -> {
            User createUser = new User();
            createUser.setFirstName(user.getFirstName());
            createUser.setLastName(user.getLastName());
            createUser.setUsername(user.getUsername());
            createUser.setEmail(user.getEmail());
            createUser.setPassword(passwordEncoder.encode(user.getPassword()));
            createUser.setRole(user.getRole());
            return createUser;
        }).toList();
        List<User> createdUser = userService.create(createUsers);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @PutMapping("/users/{id}")
    @Operation(summary = "update a user", description = "update a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<User> updateUser(@PathVariable Long id, @Valid @RequestBody UpdateUser user){
        User updateUser = new User();
        updateUser.setFirstName(user.getFirstName());
        updateUser.setLastName(user.getLastName());
        updateUser.setUsername(user.getUsername());
        updateUser.setEmail(user.getEmail());
        updateUser.setRole(user.getRole());
        User updatedUser = userService.update(id, updateUser);
        return ResponseEntity.ok(updatedUser);
    }

    @PutMapping("/users/batch")
    @Operation(summary = "update users", description = "update users description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = User.class)))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<User>> updateUsers(@Valid @RequestBody List<@Valid BatchUpdateUser> users){
        List<User> updateUsers = users.stream().map(user -> {
            User updateUser = new User();
            updateUser.setId(user.getId());
            updateUser.setFirstName(user.getFirstName());
            updateUser.setLastName(user.getLastName());
            updateUser.setUsername(user.getUsername());
            updateUser.setEmail(user.getEmail());
            updateUser.setRole(user.getRole());
            return updateUser;
        }).toList();
        List<User> updatedUsers = userService.update(updateUsers);
        return ResponseEntity.ok(updatedUsers);
    }

    @PatchMapping("/users/{id}")
    @Operation(summary = "partial update a user", description = "partial update a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<User> patchUser(@PathVariable Long id, @RequestBody UpdateUser user) {
        User patchUser = new User();
        if (user.getFirstName() != null) patchUser.setFirstName(user.getFirstName());
        if (user.getLastName() != null) patchUser.setLastName(user.getLastName());
        if (user.getUsername() != null) patchUser.setUsername(user.getUsername());
        if (user.getEmail() != null) patchUser.setEmail(user.getEmail());
        if (user.getRole() != null) patchUser.setRole(user.getRole());
        User patchedUser = userService.update(id, patchUser);
        return ResponseEntity.ok(patchedUser);
    }

    @PatchMapping("/users/batch")
    @Operation(summary = "partial update a user", description = "partial update a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<User>> patchUser(@RequestBody List<BatchUpdateUser> users) {
        List<User> patchUsers = users.stream().map(user -> {
            User patchUser = new User();
            if (user.getId() != null) patchUser.setId(user.getId());
            if (user.getFirstName() != null) patchUser.setFirstName(user.getFirstName());
            if (user.getLastName() != null) patchUser.setLastName(user.getLastName());
            if (user.getUsername() != null) patchUser.setUsername(user.getUsername());
            if (user.getEmail() != null) patchUser.setEmail(user.getEmail());
            if (user.getRole() != null) patchUser.setRole(user.getRole());
            return patchUser;
        }).toList();
        List<User> patchedUser = userService.update(patchUsers);
        return ResponseEntity.ok(patchedUser);
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "delete a user", description = "delete a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Delete"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<Void> deleteUser(@PathVariable Long id){
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/batch")
    @Operation(summary = "delete users", description = "delete users description")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Delete"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<Void> deleteUsers(@RequestBody List<Long> ids){
        userService.delete(ids);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/all")
    @Operation(summary = "delete all users", description = "delete all users description")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Delete"),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<Void> deleteAllUsers(){
        userService.delete();
        return ResponseEntity.noContent().build();
    }
}
