package com.debuglab.studentregistry.service;

import com.debuglab.studentregistry.dto.StudentDto;
import com.debuglab.studentregistry.entity.Student;
import com.debuglab.studentregistry.exception.StudentNotFoundException;
import com.debuglab.studentregistry.mapper.StudentMapper;
import com.debuglab.studentregistry.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);

    private final StudentRepository studentRepository;
    private final StudentMapper studentMapper;

    public StudentService(StudentRepository studentRepository, StudentMapper studentMapper) {
        this.studentRepository = studentRepository;
        this.studentMapper = studentMapper;
    }

    public List<StudentDto> getAllStudents() {
        List<StudentDto> result = new ArrayList<>();
        for (Student student : studentRepository.findAll()) {
            result.add(studentMapper.toDto(student));
        }
        return result;
    }

    public StudentDto getStudentById(Long id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new StudentNotFoundException(id));
        return studentMapper.toDto(student);
    }

    public List<StudentDto> search(String name, String department) {
        List<Student> found;
        if (department != null && name != null) {
            found = studentRepository.findByDepartmentAndFirstNameContainingIgnoreCase(department, name);
        } else if (department != null) {
            found = studentRepository.findByDepartment(department);
        } else if (name != null) {
            found = studentRepository
                    .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(name, name);
        } else {
            found = studentRepository.findAll();
        }

        log.debug("Search name={} department={} matched {} students", name, department, found.size());

        List<StudentDto> result = new ArrayList<>();
        for (Student student : found) {
            result.add(studentMapper.toDto(student));
        }
        return result;
    }

    public StudentDto createStudent(StudentDto dto) {
        Student saved = studentRepository.save(studentMapper.toEntity(dto));
        log.info("Created student with id {}", saved.getId());
        return studentMapper.toDto(saved);
    }

    public StudentDto updateStudent(Long id, StudentDto dto) {
        Student existing = studentRepository.findById(id)
                .orElseThrow(() -> new StudentNotFoundException(id));
        studentMapper.copyToExisting(dto, existing);
        return studentMapper.toDto(studentRepository.save(existing));
    }

    public void deleteStudent(Long id) {
        if (!studentRepository.existsById(id)) {
            throw new StudentNotFoundException(id);
        }
        studentRepository.deleteById(id);
    }
}
