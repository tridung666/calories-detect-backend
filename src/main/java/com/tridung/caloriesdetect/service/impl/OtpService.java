package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.common.enums.OtpPurpose;
import com.tridung.caloriesdetect.entity.OtpToken;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.repository.OtpTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Callers must hold the user row lock (or have just inserted the user). */
@Service
@RequiredArgsConstructor
public class OtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PER_HOUR = 5;

    private final OtpTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean issue(User user, OtpPurpose purpose) {
        Instant now = Instant.now();
        var latest = tokens.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), purpose);
        if (latest.isPresent() && latest.get().getExpiresAt().minus(LIFETIME).plus(COOLDOWN).isAfter(now)) {
            return false;
        }
        if (tokens.countByUserIdAndPurposeAndExpiresAtAfter(user.getId(), purpose,
                now.minus(Duration.ofHours(1)).plus(LIFETIME)) >= MAX_PER_HOUR) {
            return false;
        }
        String otp;
        do {
            otp = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        } while (latest.isPresent() && passwordEncoder.matches(otp, latest.get().getOtpHash()));
        // Avoid immediately reissuing the same numeric code as the superseded one.
        tokens.invalidateUnusedByUserIdAndPurpose(user.getId(), purpose);
        tokens.saveAndFlush(OtpToken.builder()
                .user(user).purpose(purpose).otpHash(passwordEncoder.encode(otp))
                .expiresAt(now.plus(LIFETIME)).build());
        emailService.sendOtp(user.getEmail(), otp, purpose);
        return true;
    }

    /** The outer transaction must also commit AppException so incorrect attempts survive. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AppException.class)
    public void verifyAndConsume(User user, OtpPurpose purpose, String otp) {
        OtpToken token = tokens.findFirstByUserIdAndPurposeOrderByIdDesc(user.getId(), purpose)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_OTP));
        if (Boolean.TRUE.equals(token.getIsUsed()) || !token.getExpiresAt().isAfter(Instant.now())
                || token.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new AppException(ErrorCode.INVALID_OTP);
        }
        if (!passwordEncoder.matches(otp, token.getOtpHash())) {
            token.setAttemptCount(token.getAttemptCount() + 1);
            if (token.getAttemptCount() >= MAX_ATTEMPTS) token.setIsUsed(true);
            throw new AppException(ErrorCode.INVALID_OTP);
        }
        token.setIsUsed(true);
        tokens.invalidateUnusedByUserIdAndPurpose(user.getId(), purpose);
    }
}
