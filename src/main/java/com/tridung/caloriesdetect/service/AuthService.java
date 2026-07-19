package com.tridung.caloriesdetect.service;

import com.tridung.caloriesdetect.dto.request.auth.ChangePasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.response.LoginResponse;
import com.tridung.caloriesdetect.dto.response.RegisterResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    RegisterResponse register(RegisterRequest request);

    LoginResponse refreshToken(RefreshTokenRequest request);

    void logout(LogoutRequest request);

    void changePassword(Long userId, ChangePasswordRequest request);
}
