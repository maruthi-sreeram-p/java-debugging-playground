package com.debuglab.studentregistry.repository;

import com.debuglab.studentregistry.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {

    List<Student> findByDepartment(String department);

    List<Student> findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
            String firstName, String lastName);

    List<Student> findByDepartmentAndFirstNameContainingIgnoreCase(String department, String firstName);

    boolean existsByEmail(String email);
}
