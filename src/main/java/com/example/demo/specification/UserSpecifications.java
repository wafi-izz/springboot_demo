package com.example.demo.specification;

import com.example.demo.entity.Role;
import com.example.demo.entity.User;
import org.springframework.data.jpa.domain.Specification;

public class UserSpecifications {
    public static Specification<User> hasRole(Role role){
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    public static Specification<User> firstNameLike(String name){
        return (root, query, cb) -> cb.like(cb.lower(root.get("firstName")), "%" + name.toLowerCase() + "%");
    }

    public static Specification<User> lastNameLike(String name){
        return (root, query, cb) -> cb.like(cb.lower(root.get("lastName")), "%" + name.toLowerCase() + "%");
    }

    public static Specification<User> emailLike(String email){
        return (root, query, cb) -> cb.like(cb.lower(root.get("email")), "%" + email.toLowerCase() + "%");
    }

    public static Specification<User> usernameLike(String username){
        return (root, query, cb) -> cb.like(cb.lower(root.get("username")), "%" + username.toLowerCase() + "%");
    }
}
