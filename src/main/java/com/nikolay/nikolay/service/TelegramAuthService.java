package com.nikolay.nikolay.service;

import com.nikolay.nikolay.dto.TelegramAuthDTO;
import com.nikolay.nikolay.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class TelegramAuthService {
    private static final Logger logger = LoggerFactory.getLogger(TelegramAuthService.class);
    private static final long AUTH_EXPIRATION_TIME_SECONDS = 86400; // 24 часа

    private final UserDetailsService userDetailsService;

    @Autowired
    private HttpServletRequest request;

    @Value("${telegram.bot.token}")
    private String botToken;

    public TelegramAuthService(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    /**
     * Проверяет данные, полученные от Telegram
     */
    public boolean validateTelegramResponse(TelegramAuthDTO authData) {
        if (authData == null || authData.getId() == null ||
                authData.getAuth_date() == null || authData.getHash() == null) {
            logger.warn("Отсутствуют обязательные поля в данных Telegram");
            return false;
        }

        try {
            // Проверяем время авторизации
            long authDate = Long.parseLong(authData.getAuth_date());
            long currentTime = System.currentTimeMillis() / 1000;
            if (currentTime - authDate > AUTH_EXPIRATION_TIME_SECONDS) {
                logger.warn("Данные авторизации Telegram устарели");
                return false;
            }

            // Формируем строку для проверки и проверяем хеш
            String dataCheckString = buildDataCheckString(authData);
            return validateHash(dataCheckString, authData.getHash());
        } catch (Exception e) {
            logger.error("Ошибка при проверке данных Telegram", e);
            return false;
        }
    }

    /**
     * Формирует строку для проверки хеша из данных Telegram
     */
    private String buildDataCheckString(TelegramAuthDTO authData) {
        Map<String, String> dataMap = new TreeMap<>();

        if (authData.getId() != null)
            dataMap.put("id", authData.getId().toString());
        if (authData.getFirst_name() != null)
            dataMap.put("first_name", authData.getFirst_name());
        if (authData.getLast_name() != null)
            dataMap.put("last_name", authData.getLast_name());
        if (authData.getUsername() != null)
            dataMap.put("username", authData.getUsername());
        if (authData.getPhoto_url() != null)
            dataMap.put("photo_url", authData.getPhoto_url());
        if (authData.getAuth_date() != null)
            dataMap.put("auth_date", authData.getAuth_date());

        return dataMap.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Проверяет хеш данных
     */
    private boolean validateHash(String dataCheckString, String receivedHash) {
        try {
            // Создаем секретный ключ из SHA-256 хеша от токена бота
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(botToken.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");

            // Вычисляем HMAC-SHA256
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(secretKey);
            byte[] hashBytes = hmac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
            String calculatedHash = Hex.encodeHexString(hashBytes);

            return calculatedHash.equalsIgnoreCase(receivedHash);
        } catch (Exception e) {
            logger.error("Ошибка при проверке хеша", e);
            return false;
        }
    }

    /**
     * Аутентифицирует пользователя в Spring Security
     */
    public void authenticateUser(User user) {
        if (user == null || user.getPhone() == null) {
            throw new IllegalArgumentException("Пользователь для аутентификации не найден или не имеет телефона");
        }

        try {
            // Загружаем детали пользователя и создаем объект аутентификации
            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getPhone());
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

            // Устанавливаем детали запроса
            authentication.setDetails(new WebAuthenticationDetails(request));

            // Устанавливаем в контекст безопасности
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Явно сохраняем контекст безопасности в сессии
            HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            logger.info("Пользователь {} успешно аутентифицирован через Telegram и сохранен в сессии", user.getPhone());
        } catch (Exception e) {
            logger.error("Ошибка при аутентификации пользователя {} через Telegram: {}", user.getPhone(), e.getMessage());
            throw new RuntimeException("Ошибка аутентификации: " + e.getMessage(), e);
        }
    }
}