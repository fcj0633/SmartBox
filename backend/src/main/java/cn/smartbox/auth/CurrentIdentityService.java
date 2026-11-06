package cn.smartbox.auth;

import java.util.List;
import org.springframework.stereotype.Service;

/** Application boundary for the current account view; persistence stays out of controllers. */
@Service
public class CurrentIdentityService {
    private final AuthMapper users;
    public CurrentIdentityService(AuthMapper users) { this.users = users; }
    public record View(String username, String role, List<String> box_ids) {}
    public View current(CurrentUser user) {
        return new View(user.username(), user.role(), users.boxes(user.id()));
    }
}
