package com.example.demo.service;

import com.example.demo.dto.user.BatchUpdateUser;
import com.example.demo.dto.user.UpdateUser;
import com.example.demo.dto.user.UserFilterRequest;
import com.example.demo.entity.User;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.exception.ServiceException;
import com.example.demo.mapper.UserMapper;
import com.example.demo.repository.UserRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import com.example.demo.specification.UserSpecifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserServiceImpl(UserRepository userRepository, UserMapper userMapper){
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Override
    public List<User> get() {
        return userRepository.findAll();
    }

    @Override
    public Page<User> get(Pageable pageable) {
        return userRepository.findAll(pageable);
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
    public User update(Long id, UpdateUser dto) {
        try{
            User existing = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + id + " was Not Found"));
            userMapper.updateEntity(dto, existing);
            return userRepository.save(existing);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("failed to update user.", e);
        }
    }

    @Override
    public User patch(Long id, UpdateUser dto) {
        try{
            User existing = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + id + " was Not Found"));
            userMapper.patchEntity(dto, existing);
            return userRepository.save(existing);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("failed to update user.", e);
        }
    }

    @Override
    public List<User> update(List<BatchUpdateUser> dtos) {
        try{
            List<User> updatedUsers = dtos.stream().map(dto -> {
                User existing = userRepository.findById(dto.getId()).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + dto.getId() + " was Not Found"));
                userMapper.updateEntity(dto, existing);
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
    public List<User> patch(List<BatchUpdateUser> dtos) {
        try{
            List<User> updatedUsers = dtos.stream().map(dto -> {
                User existing = userRepository.findById(dto.getId()).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + dto.getId() + " was Not Found"));
                userMapper.patchEntity(dto, existing);
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
        try{
            User user = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User with the id:" + id + " was Not Found"));
            userRepository.delete(user);
        }
        catch (ResourceNotFoundException e) {
            throw e;
        }
        catch (Exception e) {
            throw new ServiceException("failed to delete user.", e);
        }
    }

    @Override
    public void delete(List<Long> ids) {
        try{
            List<User> user = userRepository.findAllById(ids);
            userRepository.deleteAll(user);
        }
        catch (ResourceNotFoundException e) {
            throw e;
        }
        catch (Exception e) {
            throw new ServiceException("failed to delete users.", e);
        }
    }

    @Override
    public void delete() {
        try{
            userRepository.deleteAll();
        }
        catch (ResourceNotFoundException e) {
            throw e;
        }
        catch (Exception e) {
            throw new ServiceException("failed to delete users.", e);
        }
    }

    @Override
    public Page<User> filter(UserFilterRequest filterRequest, Pageable pageable) {
        try{
            Specification<User> spec = Specification.where(null); // start with no filter (matches all)

            if (filterRequest.getRole() != null) {
                spec = spec.and(UserSpecifications.hasRole(filterRequest.getRole()));
            }
            if (filterRequest.getFirstName() != null) {
                spec = spec.and(UserSpecifications.firstNameLike(filterRequest.getFirstName()));
            }
            if (filterRequest.getLastName() != null) {
                spec = spec.and(UserSpecifications.lastNameLike(filterRequest.getLastName()));
            }
            if (filterRequest.getEmail() != null) {
                spec = spec.and(UserSpecifications.emailLike(filterRequest.getEmail()));
            }
            if (filterRequest.getUsername() != null) {
                spec = spec.and(UserSpecifications.usernameLike(filterRequest.getUsername()));
            }

            return userRepository.findAll(spec, pageable);
        }
        catch (ResourceNotFoundException e) {
            throw e;
        }
        catch (Exception e) {
            throw new ServiceException("failed to get users.", e);
        }
    }
}
