package com.example.demo.controller;

import com.example.demo.dto.user.*;
import com.example.demo.entity.Role;
import com.example.demo.entity.User;
import com.example.demo.mapper.UserMapper;
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
    private final UserMapper userMapper;

    public UserController(UserService userService, PasswordEncoder passwordEncoder, UserMapper userMapper) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
    }

    @GetMapping("/users")
    @Operation(summary = "get user list", description = "get user list description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserResponse.class)))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<UserResponse>> getUserList(){
        List<User> userList = userService.get();
        return ResponseEntity.ok(userMapper.toResponseList(userList));
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
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id){
        User user = userService.get(id);
        return ResponseEntity.ok(userMapper.toResponse(user));
    }

    @PostMapping("/users/search")
    @Operation(summary = "get user list by ids", description = "get user list by ids description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserResponse.class)))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<UserResponse>> getUserListByIds(@RequestBody List<Long> ids){
        List<User> userList = userService.get(ids);
        return ResponseEntity.ok(userMapper.toResponseList(userList));
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
            @ApiResponse(responseCode = "201", description = "Success", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUser createUser){
        User user = userMapper.toEntity(createUser);
        user.setPassword(passwordEncoder.encode(createUser.getPassword()));
        User createdUser = userService.create(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(userMapper.toResponse(createdUser));
    }

    @PostMapping("/users/batch")
    @Operation(summary = "create users", description = "create users description")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<UserResponse>> createUsers(@Valid @RequestBody List<@Valid CreateUser> users){
        List<User> createUsers = users.stream().map(dto -> {
            User user = userMapper.toEntity(dto);
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
            return user;
        }).toList();
        List<User> createdUsers = userService.create(createUsers);
        return ResponseEntity.status(HttpStatus.CREATED).body(userMapper.toResponseList(createdUsers));
    }

    @PutMapping("/users/{id}")
    @Operation(summary = "update a user", description = "update a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id, @Valid @RequestBody UpdateUser user){
        User updatedUser = userService.update(id, user);
        return ResponseEntity.ok(userMapper.toResponse(updatedUser));
    }

    @PutMapping("/users/batch")
    @Operation(summary = "update users", description = "update users description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<UserResponse>> updateUsers(@Valid @RequestBody List<@Valid BatchUpdateUser> users){
        List<User> updatedUsers = userService.update(users);
        return ResponseEntity.ok(userMapper.toResponseList(updatedUsers));
    }

    @PatchMapping("/users/{id}")
    @Operation(summary = "partial update a user", description = "partial update a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<UserResponse> patchUser(@PathVariable Long id, @RequestBody UpdateUser user) {
        User patchedUser = userService.patch(id, user);
        return ResponseEntity.ok(userMapper.toResponse(patchedUser));
    }

    @PatchMapping("/users/batch")
    @Operation(summary = "partial update a user", description = "partial update a user description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Service unavailable", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    public ResponseEntity<List<UserResponse>> patchUser(@RequestBody List<BatchUpdateUser> users) {
        List<User> patchedUsers = userService.patch(users);
        return ResponseEntity.ok(userMapper.toResponseList(patchedUsers));
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

    @GetMapping("/users/filter")
    public ResponseEntity<List<UserResponse>> filterUsers(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String username) {

        UserFilterRequest filterRequest = new UserFilterRequest();
        filterRequest.setRole(role);
        filterRequest.setFirstName(firstName);
        filterRequest.setLastName(lastName);
        filterRequest.setEmail(email);
        filterRequest.setUsername(username);

        List<User> users = userService.filter(filterRequest);
        return ResponseEntity.ok(userMapper.toResponseList(users));
    }
}
