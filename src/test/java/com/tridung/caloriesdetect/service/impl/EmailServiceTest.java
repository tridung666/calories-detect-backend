package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.common.enums.OtpPurpose;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.exceptions.TemplateInputException;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Properties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {
    @Mock JavaMailSender mailSender;
    private EmailService emails;

    @BeforeEach
    void setup() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        emails = new EmailService(mailSender, engine);
        ReflectionTestUtils.setField(emails, "fromEmail", "noreply@example.com");
        lenient().when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void sendsHtmlWithPurposeSpecificCopyAndLeadingZeroOtp(OtpPurpose purpose) throws Exception {
        emails.sendOtp("user@example.com", "012345", purpose);
        MimeMessage message = sentMessage();
        String html = (String) message.getContent();
        assertThat(message.isMimeType("text/html")).isTrue();
        assertThat(message.getContentType()).containsIgnoringCase("charset=UTF-8");
        assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(html).contains("012345", "5</span> phút", "chỉ được sử dụng một lần")
                .doesNotContain("${", "th:text");
        if (purpose == OtpPurpose.EMAIL_VERIFICATION) {
            assertThat(message.getSubject()).isEqualTo("Calories Detect - Email Verification");
            assertThat(html).contains("Xác minh email của bạn", "hoàn tất đăng ký").doesNotContain("Xác nhận thay đổi mật khẩu");
        } else {
            assertThat(message.getSubject()).isEqualTo("Calories Detect - Password Reset");
            assertThat(html).contains("Xác nhận thay đổi mật khẩu", "đặt lại hoặc đổi mật khẩu").doesNotContain("hoàn tất đăng ký");
        }
    }

    @Test
    void welcomeRendersVietnameseNameAndEscapesUserProvidedHtml() throws Exception {
        emails.sendWelcome("user@example.com", "  Nguyễn <img src=x onerror=alert(1)>  ");
        MimeMessage message = sentMessage();
        assertThat(message.isMimeType("text/html")).isTrue();
        assertThat(message.getSubject()).isEqualTo("Calories Detect - Welcome");
        assertThat((String) message.getContent()).contains("Nguyễn &lt;img", "Calories Detect", "Tài khoản của bạn đã sẵn sàng")
                .doesNotContain("<img", "otp-code", "${", "th:text");
    }

    @Test
    void welcomeUsesFallbackForMissingName() throws Exception {
        emails.sendWelcome("user@example.com", null);
        assertThat((String) sentMessage().getContent()).contains("<strong>bạn</strong>");
    }

    @Test
    void smtpFailureReturnsSafeApplicationError() {
        doThrow(new MailSendException("Private SMTP detail")).when(mailSender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> emails.sendOtp("user@example.com", "012345", OtpPurpose.PASSWORD_RESET))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_DELIVERY_FAILED);
    }

    @Test
    void templateFailureReturnsSameSafeErrorBeforeSending() {
        var brokenEngine = mock(TemplateEngine.class);
        when(brokenEngine.process(eq("email/otp"), any())).thenThrow(new TemplateInputException("Missing template"));
        var service = new EmailService(mailSender, brokenEngine);
        assertThatThrownBy(() -> service.sendOtp("user@example.com", "012345", OtpPurpose.EMAIL_VERIFICATION))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.EMAIL_DELIVERY_FAILED);
        verifyNoInteractions(mailSender);
    }

    private MimeMessage sentMessage() throws Exception {
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        var message = captor.getValue();
        message.saveChanges();
        return message;
    }
}
