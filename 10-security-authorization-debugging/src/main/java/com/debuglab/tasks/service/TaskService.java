package com.debuglab.tasks.service;

import com.debuglab.tasks.dto.AssignRequest;
import com.debuglab.tasks.dto.CreateTaskRequest;
import com.debuglab.tasks.dto.TaskDto;
import com.debuglab.tasks.entity.Task;
import com.debuglab.tasks.exception.TaskNotFoundException;
import com.debuglab.tasks.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public List<TaskDto> listTasksFor(String username) {
        List<TaskDto> tasks = new ArrayList<>();
        for (Task task : taskRepository.findAll()) {
            tasks.add(toDto(task));
        }
        log.debug("Returned {} tasks to {}", tasks.size(), username);
        return tasks;
    }

    @Transactional
    public TaskDto create(CreateTaskRequest request, String username) {
        Task task = new Task();
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setStatus("OPEN");
        task.setCreatedBy(username);
        task.setCreatedAt(LocalDateTime.now());
        return toDto(taskRepository.save(task));
    }

    @Transactional
    public TaskDto assign(Long id, AssignRequest request) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
        task.setAssignee(request.getAssignee());
        task.setStatus("ASSIGNED");
        log.info("Task {} assigned to {}", id, request.getAssignee());
        return toDto(taskRepository.save(task));
    }

    @Secured("ROLE_ADMIN")
    @Transactional
    public void delete(Long id) {
        if (!taskRepository.existsById(id)) {
            throw new TaskNotFoundException(id);
        }
        taskRepository.deleteById(id);
        log.info("Task {} deleted", id);
    }

    @Transactional(readOnly = true)
    public long countByStatus(String status) {
        return taskRepository.countByStatus(status);
    }

    private TaskDto toDto(Task task) {
        return new TaskDto(task.getId(), task.getTitle(), task.getDescription(), task.getStatus(),
                task.getAssignee(), task.getCreatedBy(), task.getCreatedAt());
    }
}
