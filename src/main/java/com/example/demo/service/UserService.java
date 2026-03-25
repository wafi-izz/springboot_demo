package com.example.demo.service;

import com.example.demo.dto.user.BatchUpdateUser;
import com.example.demo.dto.user.UpdateUser;
import com.example.demo.entity.User;

import java.util.List;

public interface UserService {
    List<User> get();
    Long count();
    User get(Long id);
    List<User> get(List<Long> ids);
    Boolean exist(Long id);
    User create(User user);
    List<User> create(List<User> users);
    User update(Long id, UpdateUser dto);
    User patch(Long id, UpdateUser dto);
    List<User> update(List<BatchUpdateUser> dtos);
    List<User> patch(List<BatchUpdateUser> dtos);
    void delete(Long id);
    void delete(List<Long> ids);
    void delete();
}
