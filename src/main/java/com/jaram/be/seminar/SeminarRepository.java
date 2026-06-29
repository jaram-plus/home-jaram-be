package com.jaram.be.seminar;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeminarRepository extends JpaRepository<Seminar, String> {
    List<Seminar> findAllByOrderByStartsAtDesc();
}
