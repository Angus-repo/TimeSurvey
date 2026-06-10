package com.example.timesurvey.repo;

import com.example.timesurvey.model.Survey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SurveyRepository extends JpaRepository<Survey, String> {
    List<Survey> findByOwnerTokenOrderByCreatedAtDesc(String ownerToken);
}
