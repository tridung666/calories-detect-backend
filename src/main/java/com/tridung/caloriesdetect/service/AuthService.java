package com.tridung.caloriesdetect.service;

import com.tridung.caloriesdetect.dto.GoogleUserInfo;
import com.tridung.caloriesdetect.dto.request.auth.ChangePasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    LoginResponse loginWithGoogle(GoogleLoginRequest request);

    GoogleUserInfo verifyGoogleIdToken(String idToken);

    RegisterResponse register(RegisterRequest request);

    LoginResponse refreshToken(RefreshTokenRequest request);

    void logout(LogoutRequest request);

    void changePassword(Long userId, ChangePasswordRequest request);
}
