package com.example.demo.service;

import com.example.demo.entity.User;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public interface UserService {
    List<User> get();
    Long count();
    User get(Long id);
    List<User> get(List<Long> ids);
    Boolean exist(Long id);
    User create(User user);
    List<User> create(List<User> users);
    User update(Long id, User user);
    List<User> update(List<User> users);
    void delete(Long id);
    void delete(List<Long> ids);
    void delete();
}
