package com.tridung.caloriesdetect.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.tridung.caloriesdetect.config.JwtProperties;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.dto.request.auth.RequestPasswordChangeRequest;
import com.tridung.caloriesdetect.dto.request.auth.ForgotPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.ResetPasswordRequest;
import com.tridung.caloriesdetect.common.enums.OtpPurpose;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;
import com.tridung.caloriesdetect.security.CurrentUserProvider;

import com.tridung.caloriesdetect.dto.GoogleUserInfo;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.SetPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.request.auth.VerifyEmailRequest;
import com.tridung.caloriesdetect.dto.request.auth.ResendOtpRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.entity.RefreshToken;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.entity.AuthProvider;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.mapper.UserMapper;
import com.tridung.caloriesdetect.repository.RefreshTokenRepository;
import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.security.CustomUserDetails;
import com.tridung.caloriesdetect.security.JwtService;
import com.tridung.caloriesdetect.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final OtpService otpService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        // Serialize password authentication/token issuance with password changes.
        userRepository.findByEmailForUpdate(email);
        final var authentication = authenticate(new LoginRequest(email, request.password()));
        var userDetails = (CustomUserDetails) authentication.getPrincipal();
        if (localProvider(userDetails.user()).isEmpty()) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }
        return issueTokens(userDetails.user());
    }

    @Override
    @Transactional
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleUserInfo googleUser = verifyGoogleIdToken(request.idToken());

        User user = authProviderRepository.findByProviderAndProviderSubject(AuthProviderType.GOOGLE, googleUser.subject())
                .map(existing -> userRepository.findByIdForUpdate(existing.getUser().getId()).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND)))
                .orElseGet(() -> findOrCreateGoogleUser(googleUser));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }

        // Only a previously bound, verified Google subject or a newly created Google user reaches here.
        user.setEmailVerified(true);
        return issueTokens(user);
    }

    @Override
    public GoogleUserInfo verifyGoogleIdToken(String idToken) {
        try {
            GoogleIdToken verifiedToken = googleIdTokenVerifier.verify(idToken);
            if (verifiedToken == null) {
                throw new AppException(ErrorCode.INVALID_GOOGLE_TOKEN);
            }

            GoogleIdToken.Payload payload = verifiedToken.getPayload();
            if (!Boolean.TRUE.equals(payload.getEmailVerified()) || payload.getEmail() == null
                    || payload.getSubject() == null || payload.getSubject().isBlank()
                    || payload.getSubject().length() > 255 || !payload.getEmail().contains("@")) {
                throw new AppException(ErrorCode.INVALID_GOOGLE_TOKEN);
            }

            String email = payload.getEmail().trim().toLowerCase(Locale.ROOT);
            String fullName = (String) payload.get("name");
            if (fullName == null || fullName.isBlank()) {
                fullName = email.substring(0, email.indexOf('@'));
            }

            return new GoogleUserInfo(payload.getSubject(), email, fullName.trim());
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) {
            throw new AppException(ErrorCode.INVALID_GOOGLE_TOKEN);
        }
    }

    private User findOrCreateGoogleUser(GoogleUserInfo googleUser) {
        // Email alone never links identities. Existing users must authenticate and explicitly link.
        if (userRepository.existsByEmailIgnoreCase(googleUser.email())) {
            throw new AppException(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        }
        try {
            User user = userRepository.saveAndFlush(User.builder()
                    .email(googleUser.email())
                    .fullName(googleUser.fullName())
                    .role(UserRole.USER)
                    .status(UserStatus.ACTIVE)
                    .emailVerified(true)
                    .build());
            authProviderRepository.saveAndFlush(AuthProvider.builder().user(user)
                    .provider(AuthProviderType.GOOGLE).providerSubject(googleUser.subject()).build());
            return user;
        } catch (DataIntegrityViolationException exception) {
            // A concurrent creation can win either unique constraint; roll back this transaction.
            throw new AppException(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        }
    }

    @Override
    @Transactional
    public void linkGoogle(GoogleLoginRequest request) {
        GoogleUserInfo googleUser = verifyGoogleIdToken(request.idToken());
        User user = currentVerifiedUser();
        if (!user.getEmail().equalsIgnoreCase(googleUser.email())) {
            throw new AppException(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        }
        Optional<AuthProvider> owner = authProviderRepository
                .findByProviderAndProviderSubject(AuthProviderType.GOOGLE, googleUser.subject());
        if (owner.isPresent() && !owner.get().getUser().getId().equals(user.getId())) {
            throw new AppException(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        }
        Optional<AuthProvider> existing = authProviderRepository
                .findByUserIdAndProvider(user.getId(), AuthProviderType.GOOGLE);
        if (existing.isPresent()) {
            if (!googleUser.subject().equals(existing.get().getProviderSubject())) {
                throw new AppException(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
            }
            return;
        }
        try {
            authProviderRepository.saveAndFlush(AuthProvider.builder().user(user)
                    .provider(AuthProviderType.GOOGLE).providerSubject(googleUser.subject()).build());
        } catch (DataIntegrityViolationException exception) {
            throw new AppException(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        }
    }

    @Override
    @Transactional
    public void setPassword(SetPasswordRequest request) {
        validateNewPassword(request.newPassword(), request.confirmPassword());
        User user = currentVerifiedUser();
        if (localProvider(user).isPresent()) {
            throw new AppException(ErrorCode.LOCAL_PASSWORD_ALREADY_SET);
        }
        if (authProviderRepository.findByUserIdAndProvider(user.getId(), AuthProviderType.GOOGLE).isEmpty()) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }
        authProviderRepository.save(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                .passwordHash(passwordEncoder.encode(request.newPassword())).build());
        refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());
    }

    private Optional<AuthProvider> localProvider(User user) {
        return authProviderRepository.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL);
    }

    private LoginResponse issueTokens(User user) {
        requireVerifiedActiveUser(user);
        CustomUserDetails userDetails = new CustomUserDetails(user);
        String accessToken = jwtService.generateToken(userDetails);
        String rawRefreshToken = jwtService.generateRefreshToken();
        String refreshTokenHash = jwtService.hashToken(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(refreshTokenHash)
                .expiresAt(LocalDateTime.now().plus(jwtProperties.refreshExpiration()))
                .build();

        refreshTokenRepository.save(refreshToken);

        return new LoginResponse(
                accessToken,
                rawRefreshToken,
                "Bearer",
                jwtService.expirationSeconds()
        );
    }

    private Authentication authenticate(LoginRequest request) {
        try {
            return authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );
        } catch (DisabledException exception) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        } catch (AuthenticationException exception) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        Optional<User> existingUser =
                userRepository.findByEmailForUpdate(email);

        if (existingUser.isPresent()) {
            User user = existingUser.get();

            if (Boolean.TRUE.equals(user.getEmailVerified()) || user.getStatus() != UserStatus.ACTIVE) {
                throw new AppException(ErrorCode.USER_EXISTED);
            }

            otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION);

            return userMapper.toRegisterResponse(user);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = userMapper.toEntity(request);
        user.setEmail(email);
        user.setEmailVerified(false);
        User savedUser = userRepository.saveAndFlush(user);
        authProviderRepository.save(AuthProvider.builder().user(savedUser).provider(AuthProviderType.LOCAL)
                .passwordHash(encodedPassword).build());
        otpService.issue(savedUser, OtpPurpose.EMAIL_VERIFICATION);

        return userMapper.toRegisterResponse(savedUser);
    }

    @Override
    @Transactional(noRollbackFor = AppException.class)
    public void verifyEmail(VerifyEmailRequest request) {
        // Every verification/resend locks the same user before reading or changing OTP state.
        User user = userRepository.findByEmailForUpdate(normalizeEmail(request.email()))
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_OTP));
        if (Boolean.TRUE.equals(user.getEmailVerified()) || user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.INVALID_OTP);
        }
        otpService.verifyAndConsume(user, OtpPurpose.EMAIL_VERIFICATION, request.otp());
        user.setEmailVerified(true);
    }

    @Override
    @Transactional
    public void resendOtp(ResendOtpRequest request) {
        User user = userRepository.findByEmailForUpdate(normalizeEmail(request.email())).orElse(null);
        if (user == null || Boolean.TRUE.equals(user.getEmailVerified()) || user.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        User user = userRepository.findByEmailForUpdate(normalizeEmail(request.email())).orElse(null);
        if (!canResetPassword(user)) {
            return;
        }
        // Unknown, ineligible and throttled accounts receive the same public response.
        otpService.issue(user, OtpPurpose.PASSWORD_RESET);
    }

    @Override
    @Transactional(noRollbackFor = AppException.class)
    public void resetPassword(ResetPasswordRequest request) {
        validateNewPassword(request.newPassword(), request.confirmPassword());
        User user = userRepository.findByEmailForUpdate(normalizeEmail(request.email())).orElse(null);
        if (!canResetPassword(user)) {
            throw new AppException(ErrorCode.INVALID_OTP);
        }
        otpService.verifyAndConsume(user, OtpPurpose.PASSWORD_RESET, request.otp());
        localProvider(user).orElseThrow(() -> new AppException(ErrorCode.INVALID_OTP))
                .setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());
    }

    private boolean canResetPassword(User user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE
                && Boolean.TRUE.equals(user.getEmailVerified())
                && localProvider(user).map(AuthProvider::getPasswordHash).isPresent();
    }

    private void requireVerifiedActiveUser(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    @Override
    @Transactional
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        String oldRefreshTokenHash = jwtService.hashToken(request.refreshToken());

        Long userId = refreshTokenRepository.findUserIdByTokenHash(oldRefreshTokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        // Read token state only after locking the user: confirmation may have revoked it while we waited.
        RefreshToken oldRefreshToken = refreshTokenRepository.findByTokenHash(oldRefreshTokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (oldRefreshToken.getRevokedAt() != null) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }

        if (oldRefreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        requireVerifiedActiveUser(oldRefreshToken.getUser());
        CustomUserDetails userDetails = new CustomUserDetails(oldRefreshToken.getUser());

        String newAccessToken = jwtService.generateToken(userDetails);
        String newRawRefreshToken = jwtService.generateRefreshToken();
        String newRefreshTokenHash = jwtService.hashToken(newRawRefreshToken);

        oldRefreshToken.setRevokedAt(LocalDateTime.now());
        oldRefreshToken.setReplacedByTokenHash(newRefreshTokenHash);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(oldRefreshToken.getUser())
                .tokenHash(newRefreshTokenHash)
                .expiresAt(LocalDateTime.now().plus(jwtProperties.refreshExpiration()))
                .build();

        refreshTokenRepository.save(newRefreshToken);

        return new LoginResponse(
                newAccessToken,
                newRawRefreshToken,
                "Bearer",
                jwtService.expirationSeconds()
        );
    }

    @Override
    @Transactional
    public void logout(LogoutRequest request) {
        String tokenHash = jwtService.hashToken(request.refreshToken());
        Long userId = refreshTokenRepository.findUserIdByTokenHash(tokenHash).orElse(null);
        if (userId == null || userRepository.findByIdForUpdate(userId).isEmpty()) return;
        // Serialize with rotation. If it already won, revoke its successor as well.
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
        while (token != null && token.getUser().getId().equals(userId)) {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(LocalDateTime.now());
                refreshTokenRepository.save(token);
            }
            String successor = token.getReplacedByTokenHash();
            token = successor == null ? null : refreshTokenRepository.findByTokenHash(successor).orElse(null);
        }
    }

    @Override
    @Transactional
    public void requestPasswordChange(RequestPasswordChangeRequest request) {
        User user = currentVerifiedUser();
        AuthProvider local = localProvider(user)
                .orElseThrow(() -> new AppException(ErrorCode.LOCAL_PASSWORD_REQUIRED));
        if (!passwordEncoder.matches(request.currentPassword(), local.getPasswordHash())) {
            throw new AppException(ErrorCode.OLD_PASSWORD_NOT_MATCH);
        }
        if (!otpService.issue(user, OtpPurpose.PASSWORD_RESET)) {
            throw new AppException(ErrorCode.OTP_RATE_LIMITED);
        }
    }

    private void validateNewPassword(String password, String confirmation) {
        if (password == null || password.isBlank() || password.length() < 8 || password.length() > 72
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AppException(ErrorCode.INVALID_NEW_PASSWORD);
        }
        if (!password.equals(confirmation)) {
            throw new AppException(ErrorCode.PASSWORD_CONFIRM_NOT_MATCH);
        }
    }

    private User currentVerifiedUser() {
        User user = userRepository.findByIdForUpdate(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        requireVerifiedActiveUser(user);
        return user;
    }
}
