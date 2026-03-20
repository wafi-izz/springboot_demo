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
            User existing = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + id + " was Not Found"));
            if (user.getFirstName() != null) existing.setFirstName(user.getFirstName());
            if (user.getLastName() != null) existing.setLastName(user.getLastName());
            if (user.getUsername() != null) existing.setUsername(user.getUsername());
            if (user.getEmail() != null) existing.setEmail(user.getEmail());
            if (user.getRole() != null) existing.setRole(user.getRole());
            return userRepository.save(existing);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("failed to update user.", e);
        }
    }

    @Override
    public List<User> update(List<User> users) {
        try{
            List<User> updatedUsers = users.stream().map(user -> {
                User existing = userRepository.findById(user.getId()).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + user.getId() + " was Not Found"));
                if (user.getFirstName() != null) existing.setFirstName(user.getFirstName());
                if (user.getLastName() != null) existing.setLastName(user.getLastName());
                if (user.getUsername() != null) existing.setUsername(user.getUsername());
                if (user.getEmail() != null) existing.setEmail(user.getEmail());
                if (user.getRole() != null) existing.setRole(user.getRole());
                return existing;
            }).toList();
            return userRepository.saveAll(updatedUsers);
        } catch (ResourceNotFoundException e) {
            throw e;
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
