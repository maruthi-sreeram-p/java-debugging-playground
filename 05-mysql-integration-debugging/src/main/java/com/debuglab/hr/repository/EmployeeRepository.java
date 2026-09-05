package com.debuglab.hr.repository;

import com.debuglab.hr.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    boolean existsByEmail(String email);

    List<Employee> findByDepartmentIgnoreCase(String department);

    @Query(value = "SELECT id, first_name, last_name, email, department, designation, salary, "
            + "joining_date AS date_of_joining, active FROM employees "
            + "WHERE LOWER(department) = LOWER(?1) ORDER BY first_name",
            nativeQuery = true)
    List<Employee> searchByDepartment(String department);
}
