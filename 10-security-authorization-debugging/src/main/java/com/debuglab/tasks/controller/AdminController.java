package com.debuglab.tasks.controller;

import com.debuglab.tasks.entity.AppUser;
import com.debuglab.tasks.entity.Role;
import com.debuglab.tasks.repository.AppUserRepository;
import com.debuglab.tasks.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AppUserRepository appUserRepository;
    private final TaskService taskService;

    public AdminController(AppUserRepository appUserRepository, TaskService taskService) {
        this.appUserRepository = appUserRepository;
        this.taskService = taskService;
    }

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> users() {
        List<Map<String, Object>> users = new ArrayList<>();
        for (AppUser user : appUserRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            row.put("displayName", user.getDisplayName());
            row.put("roles", user.getRoles().stream().map(Role::getName).collect(Collectors.toList()));
            users.add(row);
        }
        return ResponseEntity.ok(users);
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> audit() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("open", taskService.countByStatus("OPEN"));
        body.put("assigned", taskService.countByStatus("ASSIGNED"));
        body.put("done", taskService.countByStatus("DONE"));
        return ResponseEntity.ok(body);
    }
}
