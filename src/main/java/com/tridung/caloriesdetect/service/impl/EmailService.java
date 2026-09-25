package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.common.enums.OtpPurpose;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateEngineException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public void sendOtp(String toEmail, String otp, OtpPurpose purpose) {
        Context context = new Context(Locale.forLanguageTag("vi"));
        context.setVariable("otp", otp);
        context.setVariable("expiresInMinutes", OtpService.LIFETIME.toMinutes());
        String subject = switch (purpose) {
            case EMAIL_VERIFICATION -> {
                context.setVariable("purposeTitle", "Xác minh email của bạn");
                context.setVariable("purposeDescription", "Nhập mã dưới đây trong ứng dụng để xác minh địa chỉ email và hoàn tất đăng ký tài khoản Calories Detect.");
                yield "Calories Detect - Email Verification";
            }
            case PASSWORD_RESET -> {
                context.setVariable("purposeTitle", "Xác nhận thay đổi mật khẩu");
                context.setVariable("purposeDescription", "Bạn vừa yêu cầu đặt lại hoặc đổi mật khẩu. Nhập mã dưới đây trong ứng dụng để xác nhận mật khẩu mới cho tài khoản Calories Detect.");
                yield "Calories Detect - Password Reset";
            }
        };
        sendHtml(toEmail, subject, "email/otp", context);
    }

    public void sendWelcome(String toEmail, String fullName) {
        Context context = new Context(Locale.forLanguageTag("vi"));
        context.setVariable("fullName", fullName == null || fullName.isBlank() ? "bạn" : fullName.trim());
        sendHtml(toEmail, "Calories Detect - Welcome", "email/welcome", context);
    }

    private void sendHtml(String toEmail, String subject, String template, Context context) {
        try {
            String html = templateEngine.process(template, context);
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MailException | MessagingException | TemplateEngineException exception) {
            // Keep delivery failures safe for callers and preserve OTP transaction rollback.
            throw new AppException(ErrorCode.EMAIL_DELIVERY_FAILED);
        }
    }
}
