package com.tridung.caloriesdetect.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.tridung.caloriesdetect.config.JwtProperties;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.dto.request.auth.ChangePasswordRequest;
import com.tridung.caloriesdetect.dto.GoogleUserInfo;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;
import com.tridung.caloriesdetect.entity.RefreshToken;
import com.tridung.caloriesdetect.entity.User;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        final var authentication = authenticate(request);
        var userDetails = (CustomUserDetails) authentication.getPrincipal();
        return issueTokens(userDetails.user());
    }

    @Override
    @Transactional
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleUserInfo googleUser = verifyGoogleIdToken(request.idToken());

        User user = userRepository.findByGoogleSubject(googleUser.subject())
                .orElseGet(() -> findOrCreateGoogleUser(googleUser));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }

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
            if (!Boolean.TRUE.equals(payload.getEmailVerified()) || payload.getEmail() == null) {
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
        return userRepository.findByEmailIgnoreCase(googleUser.email())
                .map(existingUser -> {
                    if (existingUser.getGoogleSubject() != null
                            && !existingUser.getGoogleSubject().equals(googleUser.subject())) {
                        throw new AppException(ErrorCode.INVALID_GOOGLE_TOKEN);
                    }
                    existingUser.setGoogleSubject(googleUser.subject());
                    return userRepository.save(existingUser);
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .email(googleUser.email())
                        .googleSubject(googleUser.subject())
                        .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                        .fullName(googleUser.fullName())
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .build()));
    }

    private LoginResponse issueTokens(User user) {
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
        } catch(DisabledException exception) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        } catch (AuthenticationException exception) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {

        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = userMapper.toEntity(request, encodedPassword);
        User savedUser = userRepository.save(user);

        return userMapper.toRegisterResponse(savedUser);
    }

    @Override
    @Transactional
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        String oldRefreshTokenHash = jwtService.hashToken(request.refreshToken());

        RefreshToken oldRefreshToken = refreshTokenRepository.findByTokenHash(oldRefreshTokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (oldRefreshToken.getRevokedAt() != null) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }

        if (oldRefreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

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

        RefreshToken oldRefreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (oldRefreshToken.getRevokedAt() != null) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }

        if (oldRefreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        oldRefreshToken.setRevokedAt(LocalDateTime.now());

        refreshTokenRepository.save(oldRefreshToken);
    }

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new AppException(ErrorCode.OLD_PASSWORD_NOT_MATCH);
        }

        if(!request.newPassword().equals(request.confirmNewPassword())) {
            throw new AppException(ErrorCode.PASSWORD_CONFIRM_NOT_MATCH);
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

}
