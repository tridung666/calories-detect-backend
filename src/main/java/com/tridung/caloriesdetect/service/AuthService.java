package com.tridung.caloriesdetect.service;

import com.tridung.caloriesdetect.dto.GoogleUserInfo;
import com.tridung.caloriesdetect.dto.request.auth.ForgotPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.ResetPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.RequestPasswordChangeRequest;
import com.tridung.caloriesdetect.dto.request.auth.GoogleLoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.SetPasswordRequest;
import com.tridung.caloriesdetect.dto.request.auth.LoginRequest;
import com.tridung.caloriesdetect.dto.request.auth.LogoutRequest;
import com.tridung.caloriesdetect.dto.request.auth.RefreshTokenRequest;
import com.tridung.caloriesdetect.dto.request.auth.RegisterRequest;
import com.tridung.caloriesdetect.dto.request.auth.VerifyEmailRequest;
import com.tridung.caloriesdetect.dto.request.auth.ResendOtpRequest;
import com.tridung.caloriesdetect.dto.response.auth.LoginResponse;
import com.tridung.caloriesdetect.dto.response.auth.RegisterResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    LoginResponse loginWithGoogle(GoogleLoginRequest request);

    void linkGoogle(GoogleLoginRequest request);

    void setPassword(SetPasswordRequest request);

    GoogleUserInfo verifyGoogleIdToken(String idToken);

    RegisterResponse register(RegisterRequest request);

    void verifyEmail(VerifyEmailRequest request);

    void resendOtp(ResendOtpRequest request);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    LoginResponse refreshToken(RefreshTokenRequest request);

    void logout(LogoutRequest request);

    void requestPasswordChange(RequestPasswordChangeRequest request);

}
