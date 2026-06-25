package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.config.JwtProperties;
import com.tridung.caloriesdetect.dto.request.LoginRequest;
import com.tridung.caloriesdetect.dto.request.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.LoginResponse;
import com.tridung.caloriesdetect.dto.response.RegisterResponse;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );
        var userDetails = (CustomUserDetails) authentication.getPrincipal();
        String accessToken = jwtService.generateToken(userDetails);
        String rawRefreshToken = jwtService.generateRefreshToken();
        String refreshTokenHash = jwtService.hashToken(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(userDetails.user())
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

}
