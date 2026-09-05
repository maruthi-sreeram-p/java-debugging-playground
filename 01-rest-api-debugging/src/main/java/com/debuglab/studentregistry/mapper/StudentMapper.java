package com.debuglab.studentregistry.mapper;

import com.debuglab.studentregistry.dto.StudentDto;
import com.debuglab.studentregistry.entity.Student;
import org.springframework.stereotype.Component;

@Component
public class StudentMapper {

    public StudentDto toDto(Student student) {
        StudentDto dto = new StudentDto();
        dto.setId(student.getId());
        dto.setFirstName(student.getFirstName());
        dto.setLastName(student.getLastName());
        dto.setEmail(student.getEmail());
        dto.setDepartment(student.getDepartment());
        dto.setCgpa(student.getCgpa());
        return dto;
    }

    public Student toEntity(StudentDto dto) {
        Student student = new Student();
        student.setFirstName(dto.getFirstName());
        student.setLastName(dto.getLastName());
        student.setEmail(dto.getEmail());
        student.setCgpa(dto.getCgpa());
        return student;
    }

    public void copyToExisting(StudentDto dto, Student target) {
        target.setFirstName(dto.getFirstName());
        target.setLastName(dto.getLastName());
        target.setEmail(dto.getEmail());
        target.setDepartment(dto.getDepartment());
        target.setCgpa(dto.getCgpa());
    }
}
