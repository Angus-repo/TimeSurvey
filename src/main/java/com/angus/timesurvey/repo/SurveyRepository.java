package com.angus.timesurvey.repo;

import com.angus.timesurvey.model.Survey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SurveyRepository extends JpaRepository<Survey, String> {
    List<Survey> findByOwnerTokenOrderByCreatedAtDesc(String ownerToken);
    List<Survey> findByEndDateLessThanEqual(LocalDate date);
}
