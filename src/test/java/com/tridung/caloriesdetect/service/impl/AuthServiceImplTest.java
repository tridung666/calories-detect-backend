package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserMapper userMapper;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(
                "test-secret-key-test-secret-key-test-secret-key",
                Duration.ofMinutes(15),
                Duration.ofDays(7)
        );

        authService = new AuthServiceImpl(
                authenticationManager,
                jwtService,
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                userMapper,
                jwtProperties
        );
    }

    @Test
    void login_shouldReturnAccessTokenAndRefreshToken() {
        User user = testUser();
        CustomUserDetails userDetails = new CustomUserDetails(user);
        Authentication authentication = mock(Authentication.class);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(jwtService.generateToken(userDetails)).thenReturn("access-token");
        when(jwtService.generateRefreshToken()).thenReturn("raw-refresh-token");
        when(jwtService.hashToken("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(jwtService.expirationSeconds()).thenReturn(900L);

        LoginResponse response = authService.login(
                new LoginRequest("test@gmail.com", "password123")
        );

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());

        RefreshToken savedRefreshToken = captor.getValue();
        assertThat(savedRefreshToken.getUser()).isEqualTo(user);
        assertThat(savedRefreshToken.getTokenHash()).isEqualTo("hashed-refresh-token");
        assertThat(savedRefreshToken.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void register_shouldCreateUserSuccessfully() {
        RegisterRequest request = new RegisterRequest(
                "test@gmail.com",
                "password123",
                "Test User"
        );
        User user = testUser();
        RegisterResponse expectedResponse = new RegisterResponse(
                1L,
                "test@gmail.com",
                "Test User",
                UserRole.USER,
                UserStatus.ACTIVE
        );

        when(userRepository.existsByEmailIgnoreCase("test@gmail.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userMapper.toEntity(request, "encoded-password")).thenReturn(user);
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toRegisterResponse(user)).thenReturn(expectedResponse);

        RegisterResponse response = authService.register(request);

        assertThat(response).isEqualTo(expectedResponse);
    }

    @Test
    void register_shouldThrowWhenEmailAlreadyExists() {
        RegisterRequest request = new RegisterRequest(
                "test@gmail.com",
                "password123",
                "Test User"
        );

        when(userRepository.existsByEmailIgnoreCase("test@gmail.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_EXISTED);
    }

    @Test
    void refreshToken_shouldRotateRefreshTokenAndReturnNewTokens() {
        User user = testUser();
        RefreshToken oldRefreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash("old-hashed-refresh-token")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(jwtService.hashToken("old-raw-refresh-token")).thenReturn("old-hashed-refresh-token");
        when(refreshTokenRepository.findByTokenHash("old-hashed-refresh-token"))
                .thenReturn(Optional.of(oldRefreshToken));
        when(jwtService.generateToken(any(CustomUserDetails.class))).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken()).thenReturn("new-raw-refresh-token");
        when(jwtService.hashToken("new-raw-refresh-token")).thenReturn("new-hashed-refresh-token");
        when(jwtService.expirationSeconds()).thenReturn(900L);

        LoginResponse response = authService.refreshToken(
                new RefreshTokenRequest("old-raw-refresh-token")
        );

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-raw-refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);

        assertThat(oldRefreshToken.getRevokedAt()).isNotNull();
        assertThat(oldRefreshToken.getReplacedByTokenHash()).isEqualTo("new-hashed-refresh-token");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());

        RefreshToken newRefreshToken = captor.getValue();
        assertThat(newRefreshToken.getUser()).isEqualTo(user);
        assertThat(newRefreshToken.getTokenHash()).isEqualTo("new-hashed-refresh-token");
        assertThat(newRefreshToken.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void refreshToken_shouldThrowWhenTokenNotFound() {
        when(jwtService.hashToken("unknown-refresh-token")).thenReturn("unknown-hash");
        when(refreshTokenRepository.findByTokenHash("unknown-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshToken(
                new RefreshTokenRequest("unknown-refresh-token")
        ))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }

    @Test
    void refreshToken_shouldThrowWhenTokenRevoked() {
        RefreshToken refreshToken = RefreshToken.builder()
                .user(testUser())
                .tokenHash("hashed-refresh-token")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revokedAt(LocalDateTime.now())
                .build();

        when(jwtService.hashToken("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(refreshTokenRepository.findByTokenHash("hashed-refresh-token"))
                .thenReturn(Optional.of(refreshToken));

        assertThatThrownBy(() -> authService.refreshToken(
                new RefreshTokenRequest("raw-refresh-token")
        ))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_REVOKED);
    }

    @Test
    void refreshToken_shouldThrowWhenTokenExpired() {
        RefreshToken refreshToken = RefreshToken.builder()
                .user(testUser())
                .tokenHash("hashed-refresh-token")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(jwtService.hashToken("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(refreshTokenRepository.findByTokenHash("hashed-refresh-token"))
                .thenReturn(Optional.of(refreshToken));

        assertThatThrownBy(() -> authService.refreshToken(
                new RefreshTokenRequest("raw-refresh-token")
        ))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_EXPIRED);
    }

    @Test
    void logout_shouldRevokeRefreshToken() {
        RefreshToken refreshToken = RefreshToken.builder()
                .user(testUser())
                .tokenHash("hashed-refresh-token")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(jwtService.hashToken("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(refreshTokenRepository.findByTokenHash("hashed-refresh-token"))
                .thenReturn(Optional.of(refreshToken));

        authService.logout(new LogoutRequest("raw-refresh-token"));

        assertThat(refreshToken.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository).save(refreshToken);
    }

    private User testUser() {
        return User.builder()
                .id(1L)
                .email("test@gmail.com")
                .password("encoded-password")
                .fullName("Test User")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
