package com.nikolay.nikolay.service;

import com.nikolay.nikolay.dto.TelegramAuthDTO;
import com.nikolay.nikolay.enums.Role;
import com.nikolay.nikolay.model.User;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class TelegramAuthService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramAuthService.class);
    private static final long AUTH_EXPIRATION_TIME_SECONDS = 86400; // 24 часа

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Value("${telegram.bot.token}")
    private String botToken;

    public TelegramAuthService(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Проверяет данные, полученные от Telegram
     *
     * @param authData данные аутентификации от Telegram
     * @return true если данные верны и не устарели
     */
    public boolean validateTelegramResponse(TelegramAuthDTO authData) {
        // Проверяем наличие всех необходимых полей
        if (authData.getId() == null ||
                authData.getAuth_date() == null ||
                authData.getHash() == null) {
            logger.warn("Не хватает обязательных полей для проверки");
            return false;
        }

        try {
            // Проверяем актуальность данных (не старше 24 часов)
            long authDate = Long.parseLong(authData.getAuth_date());
            long currentTime = Instant.now().getEpochSecond();

            if (currentTime - authDate > AUTH_EXPIRATION_TIME_SECONDS) {
                logger.warn("Данные аутентификации устарели");
                return false;
            }

            // Формируем строку для проверки
            String dataCheckString = buildDataCheckString(authData);

            // Вычисляем хеш и сравниваем
            return validateHash(dataCheckString, authData.getHash());
        } catch (NumberFormatException e) {
            logger.error("Ошибка при преобразовании auth_date в число", e);
            return false;
        } catch (Exception e) {
            logger.error("Ошибка при проверке данных Telegram", e);
            return false;
        }
    }

    /**
     * Регистрирует нового пользователя на основе данных Telegram
     *
     * @param authData данные аутентификации от Telegram
     * @return созданный объект пользователя
     */
    public User registerUserWithTelegram(TelegramAuthDTO authData) {
        // Проверяем, существует ли пользователь с таким Telegram
        Optional<User> existingUser = userService.findByTelegram(authData.getUsername());
        if (existingUser.isPresent()) {
            return existingUser.get();
        }

        // Создаем нового пользователя
        User newUser = new User();
        newUser.setTelegram(authData.getUsername());

        // Генерируем временный номер телефона
        newUser.setPhone(generateTemporaryPhone());

        // Генерируем случайный пароль (временный, для соответствия валидации)
        String temporaryPassword = generateRandomPassword();
        newUser.setPassword(passwordEncoder.encode(temporaryPassword));

        // Устанавливаем роль USER
        newUser.setRole(Role.USER);

        // Обрабатываем реферальную ссылку, если она есть
        if (authData.getReferralLink() != null && !authData.getReferralLink().isEmpty()) {
            newUser.setReferralLink(authData.getReferralLink());
        } else {
            newUser.setReferralLink(""); // Пустая строка по умолчанию
        }

        // Сохраняем пользователя
        userService.registerUser(newUser);

        return newUser;
    }

    /**
     * Генерирует случайный пароль для временного использования
     */
    private String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Генерирует временный телефонный номер для пользователя Telegram
     * Будет заменен на реальный номер позже
     */
    private String generateTemporaryPhone() {
        // Создаем временный номер формата +7XXXXXXXXXX только из цифр
        Random random = new Random();
        StringBuilder sb = new StringBuilder("+7");
        for(int i = 0; i < 10; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * Создает строку проверки на основе данных от Telegram
     */
    private String buildDataCheckString(TelegramAuthDTO authData) {
        // Собираем все поля, кроме hash, в TreeMap для сортировки по ключам
        Map<String, String> data = new TreeMap<>();
        data.put("id", authData.getId().toString());
        data.put("first_name", authData.getFirst_name());

        if (authData.getLast_name() != null) {
            data.put("last_name", authData.getLast_name());
        }

        if (authData.getUsername() != null) {
            data.put("username", authData.getUsername());
        }

        if (authData.getPhoto_url() != null) {
            data.put("photo_url", authData.getPhoto_url());
        }

        data.put("auth_date", authData.getAuth_date());

        // Преобразуем в формат key=value с разделителем \n
        return data.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Проверяет хеш данных Telegram
     */
    private boolean validateHash(String dataCheckString, String receivedHash) {
        try {
            // Создаем секретный ключ из SHA-256 от токена бота
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(botToken.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");

            // Вычисляем HMAC-SHA256
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(secretKey);
            byte[] hashBytes = hmac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
            String calculatedHash = Hex.encodeHexString(hashBytes);

            // Сравниваем вычисленный хеш с полученным от Telegram
            boolean isValid = calculatedHash.equals(receivedHash);
            if (!isValid) {
                logger.warn("Неверный хеш для данных Telegram. Ожидается: {}, Получено: {}",
                        calculatedHash, receivedHash);
            }

            return isValid;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Ошибка при вычислении HMAC", e);
            return false;
        }
    }
}