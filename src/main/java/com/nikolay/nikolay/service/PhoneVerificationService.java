package com.nikolay.nikolay.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PhoneVerificationService {
    private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();

    @Value("${smsc.login}")
    private String smscLogin;

    @Value("${smsc.password}")
    private String smscPassword;

    public void sendVerificationCode(String phone) {
        String code = String.valueOf(new Random().nextInt(9000) + 1000);
        verificationCodes.put(phone, code);
        System.out.println("Code: " + code + " send to phone " + phone);
        String url = String.format(
                "https://smsc.ru/sys/send.php?login=%s&psw=%s&phones=%s&mes=%s&fmt=3",
                smscLogin,
                smscPassword,
                phone,
                URLEncoder.encode("Код подтверждения: " + code, StandardCharsets.UTF_8)
        );

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.getInputStream(); // отправляем запрос
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при отправке SMS", e);
        }
    }

    public boolean verifyCode(String phone, String code) {
        return code.equals(verificationCodes.get(phone));
    }

    public void clearCode(String phone) {
        verificationCodes.remove(phone);
    }
}
