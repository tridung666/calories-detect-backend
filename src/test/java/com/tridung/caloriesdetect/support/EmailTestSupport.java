package com.tridung.caloriesdetect.support;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public final class EmailTestSupport {
    private EmailTestSupport() {
    }

    public static void prepare(JavaMailSender sender) {
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    public static String lastOtp(JavaMailSender sender, String subject) {
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, atLeastOnce()).send(captor.capture());
        try {
            var message = captor.getValue();
            message.saveChanges();
            assertThat(message.getSubject()).isEqualTo("Calories Detect - " + subject);
            assertThat(message.isMimeType("text/html")).isTrue();
            assertThat(message.getContentType()).containsIgnoringCase("charset=UTF-8");
            String html = (String) message.getContent();
            var matcher = Pattern.compile("id=\"otp-code\"[^>]*>\\s*([0-9]{6})\\s*<").matcher(html);
            assertThat(matcher.find()).isTrue();
            assertThat(html).doesNotContain("th:text", "${");
            return matcher.group(1);
        } catch (Exception exception) {
            throw new AssertionError("Unable to read OTP HTML email", exception);
        }
    }
}
