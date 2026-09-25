package com.tridung.caloriesdetect.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.tridung.caloriesdetect.common.enums.UserRole;
import com.tridung.caloriesdetect.common.enums.UserStatus;
import com.tridung.caloriesdetect.config.JwtProperties;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;
import com.tridung.caloriesdetect.entity.RefreshToken;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.entity.AuthProvider;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;

import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.mapper.UserMapper;
import com.tridung.caloriesdetect.repository.RefreshTokenRepository;
import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.security.CurrentUserProvider;
import com.tridung.caloriesdetect.common.enums.OtpPurpose;
import com.tridung.caloriesdetect.security.CustomUserDetails;
import com.tridung.caloriesdetect.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private AuthProviderRepository authProviderRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @Mock
    private OtpService otpService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(
                "test-secret-key-test-secret-key-test-secret-key",
                Duration.ofMinutes(15),
                Duration.ofDays(7)
        );

        org.mockito.Mockito.lenient().when(refreshTokenRepository.findUserIdByTokenHash(any(String.class)))
                .thenReturn(Optional.of(1L));
        org.mockito.Mockito.lenient().when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testUser()));
        authService = new AuthServiceImpl(
                authenticationManager,
                jwtService,
                userRepository,
                authProviderRepository,
                refreshTokenRepository,
                passwordEncoder,
                userMapper,
                jwtProperties,
                googleIdTokenVerifier,
                otpService,
                currentUserProvider
        );
    }

    @Test
    void loginWithGoogle_shouldIssueTokensForExistingUser() throws Exception {
        User user = testUser();
        GoogleLoginRequest request = new GoogleLoginRequest("google-id-token");

        stubGoogleToken("test@gmail.com", "Test User");
        when(authProviderRepository.findByProviderAndProviderSubject(AuthProviderType.GOOGLE, "google-subject"))
                .thenReturn(Optional.of(AuthProvider.builder().user(user).provider(AuthProviderType.GOOGLE)
                        .providerSubject("google-subject").build()));
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        stubIssuedTokens();

        LoginResponse response = authService.loginWithGoogle(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh-token");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginWithGoogle_shouldCreateUserOnFirstLogin() throws Exception {
        GoogleLoginRequest request = new GoogleLoginRequest("google-id-token");
        User savedUser = User.builder()
                .id(2L)
                .email("new@gmail.com")
                .fullName("New User")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();

        stubGoogleToken("new@gmail.com", "New User");
        when(authProviderRepository.findByProviderAndProviderSubject(AuthProviderType.GOOGLE, "google-subject"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("new@gmail.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        stubIssuedTokens();

        authService.loginWithGoogle(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("new@gmail.com");
        ArgumentCaptor<AuthProvider> providerCaptor = ArgumentCaptor.forClass(AuthProvider.class);
        verify(authProviderRepository).saveAndFlush(providerCaptor.capture());
        assertThat(providerCaptor.getValue().getProvider()).isEqualTo(AuthProviderType.GOOGLE);
        assertThat(providerCaptor.getValue().getProviderSubject()).isEqualTo("google-subject");
        assertThat(providerCaptor.getValue().getPasswordHash()).isNull();
        verifyNoInteractions(passwordEncoder);
        assertThat(userCaptor.getValue().getFullName()).isEqualTo("New User");
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.USER);
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    private void stubIssuedTokens() {
        when(jwtService.generateToken(any(CustomUserDetails.class))).thenReturn("access-token");
        when(jwtService.generateRefreshToken()).thenReturn("raw-refresh-token");
        when(jwtService.hashToken("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(jwtService.expirationSeconds()).thenReturn(900L);
    }

    private void stubGoogleToken(String email, String fullName) throws Exception {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
                .setSubject("google-subject")
                .setEmail(email)
                .setEmailVerified(true);
        payload.set("name", fullName);

        GoogleIdToken googleIdToken = mock(GoogleIdToken.class);
        when(googleIdToken.getPayload()).thenReturn(payload);
        when(googleIdTokenVerifier.verify("google-id-token")).thenReturn(googleIdToken);
    }

    @Test
    void login_shouldReturnAccessTokenAndRefreshToken() {
        User user = testUser();
        when(authProviderRepository.findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL))
                .thenReturn(Optional.of(AuthProvider.builder().user(user).provider(AuthProviderType.LOCAL)
                        .passwordHash("encoded-password").build()));
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
    void login_shouldThrowInvalidCredentialsWhenEmailDoesNotExist() {
        LoginRequest request = new LoginRequest("unknown@gmail.com", "password123");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verifyNoInteractions(jwtService, refreshTokenRepository);
    }

    @Test
    void login_shouldThrowInvalidCredentialsWhenPasswordIsWrong() {
        LoginRequest request = new LoginRequest("test@gmail.com", "wrong-password");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verifyNoInteractions(jwtService, refreshTokenRepository);
    }

    @Test
    void login_shouldThrowAccountInactiveWhenAccountIsDisabled() {
        LoginRequest request = new LoginRequest("inactive@gmail.com", "password123");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new DisabledException("User is disabled"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCOUNT_INACTIVE);

        verifyNoInteractions(jwtService, refreshTokenRepository);
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
                UserStatus.ACTIVE,
                false
        );

        when(userRepository.findByEmailForUpdate("test@gmail.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userMapper.toEntity(request)).thenReturn(user);
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        when(userMapper.toRegisterResponse(user)).thenReturn(expectedResponse);

        RegisterResponse response = authService.register(request);

        assertThat(response).isEqualTo(expectedResponse);
        assertThat(user.getEmailVerified()).isFalse();
        verify(otpService).issue(user, OtpPurpose.EMAIL_VERIFICATION);
        ArgumentCaptor<AuthProvider> providerCaptor = ArgumentCaptor.forClass(AuthProvider.class);
        verify(authProviderRepository).save(providerCaptor.capture());
        assertThat(providerCaptor.getValue().getProvider()).isEqualTo(AuthProviderType.LOCAL);
        assertThat(providerCaptor.getValue().getPasswordHash()).isEqualTo("encoded-password");
        assertThat(providerCaptor.getValue().getProviderSubject()).isNull();
        verifyNoInteractions(jwtService, refreshTokenRepository);
    }

    @Test
    void register_shouldThrowWhenEmailAlreadyExists() {
        RegisterRequest request = new RegisterRequest(
                "test@gmail.com",
                "password123",
                "Test User"
        );

        when(userRepository.findByEmailForUpdate("test@gmail.com")).thenReturn(Optional.of(testUser()));

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
        when(refreshTokenRepository.findUserIdByTokenHash("unknown-hash")).thenReturn(Optional.empty());

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
                .fullName("Test User")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
    }
}
