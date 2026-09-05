package com.debuglab.hr.service;

import com.debuglab.hr.dto.EmployeeDto;
import com.debuglab.hr.entity.Employee;
import com.debuglab.hr.exception.DuplicateEmailException;
import com.debuglab.hr.exception.EmployeeNotFoundException;
import com.debuglab.hr.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    public EmployeeService(EmployeeRepository employeeRepository, AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
    }

    public List<EmployeeDto> findAll() {
        List<Employee> employees = employeeRepository.findAll();
        log.debug("Directory listing returned {} employees", employees.size());
        return toDtoList(employees);
    }

    public EmployeeDto findById(Long id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new EmployeeNotFoundException(id));
        return toDto(employee);
    }

    public List<EmployeeDto> findByDepartment(String department) {
        return toDtoList(employeeRepository.searchByDepartment(department));
    }

    @Transactional
    public EmployeeDto create(EmployeeDto dto) {
        if (employeeRepository.existsByEmail(dto.getEmail())) {
            throw new DuplicateEmailException(dto.getEmail());
        }

        Employee employee = new Employee();
        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getLastName());
        employee.setEmail(dto.getEmail());
        employee.setDepartment(dto.getDepartment());
        employee.setDesignation(dto.getDesignation());
        employee.setSalary(dto.getSalary());
        employee.setDateOfJoining(dto.getDateOfJoining());
        employee.setActive(dto.getActive() == null || dto.getActive());

        Employee saved = employeeRepository.save(employee);
        auditService.record("EMPLOYEE_CREATED", "id=" + saved.getId() + " email=" + saved.getEmail());
        return toDto(saved);
    }

    @Transactional
    public EmployeeDto update(Long id, EmployeeDto dto) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new EmployeeNotFoundException(id));

        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getLastName());
        employee.setEmail(dto.getEmail());
        employee.setDepartment(dto.getDepartment());
        employee.setDesignation(dto.getDesignation());
        employee.setSalary(dto.getSalary());
        employee.setDateOfJoining(dto.getDateOfJoining());
        if (dto.getActive() != null) {
            employee.setActive(dto.getActive());
        }

        return toDto(employeeRepository.save(employee));
    }

    private List<EmployeeDto> toDtoList(List<Employee> employees) {
        List<EmployeeDto> dtos = new ArrayList<>();
        for (Employee employee : employees) {
            dtos.add(toDto(employee));
        }
        return dtos;
    }

    private EmployeeDto toDto(Employee employee) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(employee.getId());
        dto.setFirstName(employee.getFirstName());
        dto.setLastName(employee.getLastName());
        dto.setEmail(employee.getEmail());
        dto.setDepartment(employee.getDepartment());
        dto.setDesignation(employee.getDesignation());
        dto.setSalary(employee.getSalary());
        dto.setDateOfJoining(employee.getDateOfJoining());
        dto.setActive(employee.isActive());
        return dto;
    }
}
