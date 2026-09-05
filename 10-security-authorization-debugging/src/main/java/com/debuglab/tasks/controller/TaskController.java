package com.debuglab.tasks.controller;

import com.debuglab.tasks.dto.AssignRequest;
import com.debuglab.tasks.dto.CreateTaskRequest;
import com.debuglab.tasks.dto.TaskDto;
import com.debuglab.tasks.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ResponseEntity<List<TaskDto>> listTasks(Authentication authentication) {
        return ResponseEntity.ok(taskService.listTasksFor(authentication.getName()));
    }

    @PostMapping
    public ResponseEntity<TaskDto> create(@Valid @RequestBody CreateTaskRequest request,
                                          Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.create(request, authentication.getName()));
    }

    @PutMapping("/{id}/assign")
    public ResponseEntity<TaskDto> assign(@PathVariable Long id,
                                          @Valid @RequestBody AssignRequest request) {
        return ResponseEntity.ok(taskService.assign(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
