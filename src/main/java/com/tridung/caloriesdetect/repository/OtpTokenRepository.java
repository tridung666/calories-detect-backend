package com.tridung.caloriesdetect.repository;

import com.tridung.caloriesdetect.entity.OtpToken;
import com.tridung.caloriesdetect.common.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {
    Optional<OtpToken> findFirstByUserIdAndPurposeOrderByIdDesc(Long userId, OtpPurpose purpose);

    long countByUserIdAndPurposeAndExpiresAtAfter(Long userId, OtpPurpose purpose, Instant cutoff);

    @Modifying(flushAutomatically = true)
    @Query("update OtpToken t set t.isUsed = true where t.user.id = :userId and t.purpose = :purpose and t.isUsed = false")
    void invalidateUnusedByUserIdAndPurpose(@Param("userId") Long userId, @Param("purpose") OtpPurpose purpose);
}
