package com.angus.timesurvey.repo;

import com.angus.timesurvey.model.UserActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface UserActivityRepository extends JpaRepository<UserActivity, Long> {

    List<UserActivity> findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(LocalDateTime cutoff);

    void deleteByOccurredAtBefore(LocalDateTime cutoff);
}
