package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.exception.ServiceException;
import com.example.demo.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository){
        this.userRepository = userRepository;
    }

    @Override
    public List<User> get() {
        return userRepository.findAll();
    }

    @Override
    public Long count() {
        return userRepository.count();
    }

    @Override
    public User get(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + id + " was Not Found"));
    }

    @Override
    public List<User> get(List<Long> ids) {
        return userRepository.findAllById(ids);
    }

    @Override
    public Boolean exist(Long id) {
        return userRepository.existsById(id);
    }

    @Override
    public User create(User user){
        try {
            return userRepository.save(user);
        } catch (Exception e) {
            throw new ServiceException("failed to create user.", e);
        }
    }

    @Override
    public List<User> create(List<User> users) {
        try {
            return userRepository.saveAll(users);
        } catch (Exception e) {
            throw new ServiceException("failed to create users.", e);
        }
    }

    @Override
    public User update(Long id, User user) {
        try{
            User UpdateUser = userRepository.findById(id).orElseThrow();
            UpdateUser.setFirstName(user.getFirstName());
            UpdateUser.setEmail(user.getEmail());
            return userRepository.save(UpdateUser);
        } catch (Exception e) {
            throw new ServiceException("failed to update user.", e);
        }
    }

    @Override
    public List<User> update(List<User> users) {
        try{
            List<User> UpdateUsers = users.stream().map(user -> {
                User UpdateUser = userRepository.findById(user.getId()).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + user.getId() + " was Not Found"));
                UpdateUser.setFirstName(user.getFirstName());
                UpdateUser.setEmail(user.getEmail());
                return UpdateUser;
            }).toList();
            return userRepository.saveAll(UpdateUsers);
        } catch (Exception e) {
            throw new ServiceException("failed to update users.", e);
        }
    }

    @Override
    public void delete(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + id + " was Not Found"));
        userRepository.delete(user);
    }

    @Override
    public void delete(List<Long> ids) {
        List<User> user = userRepository.findAllById(ids);
        userRepository.deleteAll(user);
    }

    @Override
    public void delete() {
        userRepository.deleteAll();
    }
}
