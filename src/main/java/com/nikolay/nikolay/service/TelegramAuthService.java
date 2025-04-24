package com.nikolay.nikolay.service;

import com.nikolay.nikolay.dto.TelegramAuthDTO;
import com.nikolay.nikolay.model.User;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Сервис для работы с аутентификацией через Telegram.
 * ДОБАВЛЕНО: Улучшенное логирование для валидации хеша.
 */
@Service
public class TelegramAuthService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramAuthService.class);
    private static final long AUTH_EXPIRATION_TIME_SECONDS = 86400; // 24 часа

    private final UserDetailsService userDetailsService;
    private final CustomUserDetailsService customUserDetailsService; // Используем наш сервис

    @Value("${telegram.bot.token}")
    private String botToken;

    // Инжектируем CustomUserDetailsService вместо стандартного UserDetailsService
    public TelegramAuthService(CustomUserDetailsService customUserDetailsService) {
        this.userDetailsService = customUserDetailsService;
        this.customUserDetailsService = customUserDetailsService; // Сохраняем ссылку
    }

    /**
     * Проверяет подлинность и актуальность данных, полученных от Telegram.
     * @param authData DTO с данными аутентификации от Telegram.
     * @return true, если данные действительны, иначе false.
     */
    public boolean validateTelegramResponse(TelegramAuthDTO authData) {
        // <<< НОВОЕ ЛОГИРОВАНИЕ >>>
        logger.debug(">>> Entering validateTelegramResponse for Telegram ID: {}", authData.getId());
        // <<< КОНЕЦ НОВОГО ЛОГИРОВАНИЯ >>>

        // Проверка формата токена бота
        if (botToken == null || !botToken.contains(":")) {
            logger.error("Некорректный формат токена бота. Должен быть в формате ID:TOKEN");
            return false;
        }

        // Проверяем наличие обязательных полей
        if (authData.getId() == null || authData.getAuth_date() == null || authData.getHash() == null) {
            logger.warn("Отсутствуют обязательные поля для проверки Telegram: id={}, auth_date={}, hash={}",
                    authData.getId(), authData.getAuth_date(), authData.getHash());
            return false;
        }

        try {
            // Проверяем актуальность данных (не старше 24 часов)
            long authDate = Long.parseLong(authData.getAuth_date());
            long currentTime = Instant.now().getEpochSecond();

            if (currentTime - authDate > AUTH_EXPIRATION_TIME_SECONDS) {
                logger.warn("Данные Telegram устарели: authDate={}, currentTime={}, разница={}",
                        authDate, currentTime, currentTime - authDate);
                return false; // Данные просрочены
            } else {
                logger.debug("Проверка времени данных Telegram: OK (authDate={}, currentTime={}, разница={})",
                        authDate, currentTime, currentTime - authDate);
            }

            // Формируем строку для проверки и валидируем хеш
            String dataCheckString = buildDataCheckString(authData);

            // <<< НОВОЕ ЛОГИРОВАНИЕ >>>
            logger.debug("Data Check String for validation:\n---\n{}\n---", dataCheckString); // Логируем строку четко
            // <<< КОНЕЦ НОВОГО ЛОГИРОВАНИЯ >>>

            boolean isValid = validateHash(dataCheckString, authData.getHash());

            if (isValid) {
                logger.info("Успешная валидация данных от Telegram для id={}", authData.getId());
            } else {
                logger.warn("Ошибка валидации хеша от Telegram для id={}", authData.getId());
            }

            return isValid;

        } catch (NumberFormatException e) {
            logger.error("Ошибка парсинга auth_date: {}", authData.getAuth_date(), e);
            return false;
        } catch (Exception e) {
            logger.error("Непредвиденная ошибка при валидации данных Telegram: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Создает строку для проверки хеша из данных Telegram.
     * @param authData DTO с данными от Telegram.
     * @return Строка в формате "key=value\nkey2=value2...".
     */
    private String buildDataCheckString(TelegramAuthDTO authData) {
        Map<String, String> dataMap = new TreeMap<>(); // TreeMap для автоматической сортировки ключей

        // Добавляем поля в Map, пропуская null значения и hash
        if (authData.getId() != null) dataMap.put("id", authData.getId().toString());
        if (authData.getFirst_name() != null) dataMap.put("first_name", authData.getFirst_name());
        if (authData.getLast_name() != null) dataMap.put("last_name", authData.getLast_name());
        if (authData.getUsername() != null) dataMap.put("username", authData.getUsername());
        if (authData.getPhoto_url() != null) dataMap.put("photo_url", authData.getPhoto_url());
        if (authData.getAuth_date() != null) dataMap.put("auth_date", authData.getAuth_date());

        // Формируем строку key=value, разделенную '\n'
        return dataMap.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Вычисляет HMAC-SHA256 хеш для строки данных и сравнивает с хешем от Telegram.
     * @param dataCheckString Строка для проверки.
     * @param receivedHash Хеш, полученный от Telegram.
     * @return true, если хеши совпадают, иначе false.
     */
    private boolean validateHash(String dataCheckString, String receivedHash) {
        try {
            // 1. Получаем секретный ключ: SHA256 от токена бота
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] secretKeyBytes = sha256.digest(botToken.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKeyBytes, "HmacSHA256");

            // 2. Инициализируем HMAC-SHA256 с секретным ключом
            Mac hmacSha256 = Mac.getInstance("HmacSHA256");
            hmacSha256.init(secretKeySpec);

            // 3. Вычисляем хеш от строки данных
            byte[] hashBytes = hmacSha256.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

            // 4. Преобразуем байты хеша в шестнадцатеричную строку
            String calculatedHash = Hex.encodeHexString(hashBytes);

            // 5. Сравниваем вычисленный хеш с полученным
            boolean isValid = calculatedHash.equalsIgnoreCase(receivedHash);

            // <<< НОВОЕ ЛОГИРОВАНИЕ >>>
            if (!isValid) {
                logger.warn("Хеш Telegram НЕ валиден!");
                logger.debug("Calculated Hash: {}", calculatedHash);
                logger.debug("Received Hash:   {}", receivedHash);
            } else {
                logger.debug("Хеш Telegram валиден (Calculated: {}, Received: {})", calculatedHash, receivedHash);
            }
            // <<< КОНЕЦ НОВОГО ЛОГИРОВАНИЯ >>>

            return isValid;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Критическая ошибка при вычислении хеша Telegram: {}", e.getMessage(), e);
            return false; // Критическая ошибка конфигурации или среды
        }
    }

    /**
     * Аутентифицирует пользователя в Spring Security.
     * @param user Пользователь для аутентификации.
     */
    public void authenticateUser(User user) {
        if (user == null || user.getPhone() == null) {
            throw new IllegalArgumentException("Невозможно аутентифицировать пользователя без данных (user или phone is null)");
        }

        String phone = user.getPhone();
        // <<< НОВОЕ ЛОГИРОВАНИЕ >>>
        logger.info(">>> Попытка аутентификации пользователя через Telegram: {}", phone);
        // <<< КОНЕЦ НОВОГО ЛОГИРОВАНИЯ >>>


        try {
            // Загружаем детали пользователя ИСПОЛЬЗУЯ НАШ СЕРВИС
            // UserDetails userDetails = userDetailsService.loadUserByUsername(phone); // Старый вариант
            UserDetails userDetails = customUserDetailsService.loadUserByUsername(phone); // Используем инжектированный CustomUserDetailsService

            if (userDetails == null) {
                // На всякий случай, если loadUserByUsername вернет null вместо исключения
                logger.error("Ошибка аутентификации: CustomUserDetailsService вернул null для телефона: {}", phone);
                throw new UsernameNotFoundException("Пользователь не найден: " + phone);
            }

            // Создаем объект аутентификации
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());

            // Устанавливаем аутентификацию в контекст
            SecurityContextHolder.getContext().setAuthentication(authentication);
            logger.info("Пользователь {} успешно аутентифицирован через Telegram и установлен в SecurityContext", phone);

        } catch (UsernameNotFoundException e) {
            logger.error("Ошибка аутентификации: пользователь не найден CustomUserDetailsService: {}", phone, e);
            throw e; // Пробрасываем исключение дальше
        } catch (Exception e) {
            logger.error("Непредвиденная ошибка при аутентификации пользователя {} через Telegram: {}", phone, e.getMessage(), e);
            // Оборачиваем в RuntimeException, чтобы было видно в вызывающем коде
            throw new RuntimeException("Ошибка аутентификации: " + e.getMessage(), e);
        }
    }
}
    