package com.angus.timesurvey.repo;

import com.angus.timesurvey.model.EntraToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EntraTokenRepository extends JpaRepository<EntraToken, String> {

    Optional<EntraToken> findByRememberTokenHash(String rememberTokenHash);
}
