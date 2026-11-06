package cn.smartbox.auth;
public record UserAccount(Long id,String username,String passwordHash,String roleCode,Boolean enabled) {}
