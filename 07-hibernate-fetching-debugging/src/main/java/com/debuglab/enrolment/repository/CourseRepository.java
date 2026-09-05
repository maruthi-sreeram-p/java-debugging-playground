package com.debuglab.enrolment.repository;

import com.debuglab.enrolment.dto.CourseSummary;
import com.debuglab.enrolment.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    @Query("SELECT c FROM Course c JOIN FETCH c.modules")
    Page<Course> findAllWithModules(Pageable pageable);

    @Query("SELECT c.title AS title, COUNT(DISTINCT m.id) AS modules, COUNT(l.id) AS lessons "
            + "FROM Course c LEFT JOIN c.modules m LEFT JOIN m.lessons l "
            + "WHERE c.id = :id GROUP BY c.title")
    Optional<CourseSummary> summaryFor(@Param("id") Long id);
}
