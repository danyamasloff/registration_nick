package com.nikolay.nikolay.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException; // Импортируем для обработки ошибок RestTemplate
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Сервис для верификации номеров телефона с помощью звонков через API Novofon.
 * Генерирует код, инициирует звонок с TTS, хранит код временно и проверяет его.
 */
@Service
public class NovofonVerificationService implements InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(NovofonVerificationService.class);
    // Время жизни кода подтверждения (5 минут)
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    // ObjectMapper для работы с JSON (можно сделать бином Spring)
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Потокобезопасное хранилище кодов верификации (Телефон -> Данные верификации)
    private final Map<String, VerificationData> verificationCodes = new ConcurrentHashMap<>();
    // RestTemplate для выполнения HTTP-запросов к API Novofon
    private final RestTemplate restTemplate;

    // --- Параметры API Novofon из application.properties ---
    @Value("${novofon.api.url}")
    private String apiUrl;
    @Value("${novofon.api.secret}")
    private String apiSecret;
    @Value("${novofon.virtual_number}")
    private String virtualNumber;
    @Value("${novofon.verification.code_length}")
    private int codeLength;
    // --- Конец параметров API ---

    // Конструктор для внедрения RestTemplate
    public NovofonVerificationService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Метод вызывается после инициализации бина для проверки конфигурации.
     */
    @Override
    public void afterPropertiesSet() {
        logger.info("Проверка конфигурации NovofonVerificationService:");
        logger.info(" - API URL: {}", apiUrl);
        logger.info(" - Виртуальный номер: {}", virtualNumber);
        logger.info(" - Длина кода: {}", codeLength);
        if (apiSecret != null && !apiSecret.isBlank()) {
            logger.info(" - API Secret: ********** (задан)");
        } else {
            logger.error(" - API Secret НЕ ЗАДАН в конфигурации!");
            // Возможно, стоит выбросить исключение, если секрет обязателен
        }
    }

    /**
     * Отправляет код верификации через голосовой звонок Novofon.
     * Генерирует код, сохраняет его и инициирует звонок с TTS.
     * @param phone Номер телефона пользователя (должен быть предварительно нормализован).
     * @throws RuntimeException если произошла ошибка при вызове API Novofon.
     */
    public void sendVerificationCode(String phone) {
        String code = generateCode(); // Генерируем цифровой код
        String ttsMessage = formatTtsMessage(code); // Формируем сообщение для озвучки
        String requestId = UUID.randomUUID().toString(); // Уникальный ID запроса
        // Форматируем номер для API (обычно без '+')
        String formattedPhoneForApi = formatPhoneNumberForApi(phone);

        logger.info("Подготовка звонка Novofon: номер={}, код={}, ID запроса={}", formattedPhoneForApi, code, requestId);

        // Создаем объекты для JSON-RPC запроса
        NovofonTtsMessage contactMessage = new NovofonTtsMessage("tts", ttsMessage);
        NovofonParams params = new NovofonParams(apiSecret, virtualNumber, formattedPhoneForApi, contactMessage);
        NovofonJsonRpcRequest request = new NovofonJsonRpcRequest("start.informer_call", params, requestId);

        // Устанавливаем заголовки HTTP-запроса
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "NikolayAppClient/1.0"); // Пример User-Agent
        HttpEntity<NovofonJsonRpcRequest> entity = new HttpEntity<>(request, headers);

        try {
            // Логгируем JSON перед отправкой (полезно для отладки)
            // logger.debug("Отправляемый JSON Novofon: {}", objectMapper.writeValueAsString(request));

            // Выполняем POST-запрос к API Novofon
            ResponseEntity<NovofonResponse> response = restTemplate.postForEntity(apiUrl, entity, NovofonResponse.class);

            // Обрабатываем ответ
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                NovofonResponse body = response.getBody();
                if (body.getError() != null) {
                    // Ошибка на стороне API Novofon
                    logger.error("Ошибка API Novofon при отправке звонка на {}: Код={}, Сообщение='{}'",
                            formattedPhoneForApi, body.getError().getCode(), body.getError().getMessage());
                    // Логгируем полный ответ для деталей
                    // logger.error("Полный ответ с ошибкой от Novofon: {}", objectMapper.writeValueAsString(body));
                    throw new RuntimeException("Ошибка API Novofon: " + body.getError().getMessage());
                } else if (body.getResult() != null && body.getResult().getData() != null) {
                    // Звонок успешно инициирован
                    logger.info("Звонок Novofon на номер {} успешно инициирован. CallSessionID: {}",
                            formattedPhoneForApi, body.getResult().getData().getCallSessionId());
                    // Сохраняем код и время его генерации
                    verificationCodes.put(phone, new VerificationData(code, LocalDateTime.now()));
                } else {
                    // Странный ответ без ошибки и результата
                    logger.error("Неожиданный успешный ответ от API Novofon без результата для номера {}. Ответ: {}", formattedPhoneForApi, response.getBody());
                    throw new RuntimeException("Неожиданный ответ от API Novofon.");
                }
            } else {
                // Ошибка HTTP (не 2xx)
                logger.error("Ошибка HTTP при запросе к API Novofon для номера {}: Статус={}", formattedPhoneForApi, response.getStatusCode());
                throw new RuntimeException("Ошибка связи с API Novofon: Статус " + response.getStatusCodeValue());
            }
        } catch (RestClientException e) {
            logger.error("Ошибка RestTemplate при вызове API Novofon для номера {}: {}", formattedPhoneForApi, e.getMessage());
            throw new RuntimeException("Ошибка связи с сервисом верификации.", e);
        } catch (Exception e) { // Ловим другие возможные ошибки (например, JSON)
            logger.error("Непредвиденная ошибка при отправке звонка верификации Novofon на {}: {}", formattedPhoneForApi, e.getMessage(), e);
            throw new RuntimeException("Внутренняя ошибка сервиса верификации.", e);
        }
    }

    /**
     * Проверяет введенный пользователем код верификации.
     * @param phone Номер телефона пользователя (нормализованный).
     * @param code Код, введенный пользователем.
     * @return `true`, если код верный и не истек срок действия, иначе `false`.
     */
    public boolean verifyCode(String phone, String code) {
        VerificationData data = verificationCodes.get(phone);

        // Проверка наличия кода
        if (data == null) {
            logger.warn("Код верификации для номера {} не найден (возможно, истек или не запрашивался).", phone);
            return false;
        }

        // Проверка времени жизни кода
        if (LocalDateTime.now().isAfter(data.timestamp.plus(CODE_TTL))) {
            logger.warn("Истек срок действия кода верификации для номера {}. Время генерации: {}", phone, data.timestamp);
            verificationCodes.remove(phone); // Удаляем истекший код
            return false;
        }

        // Сравнение кодов
        boolean isValid = data.code.equals(code);
        if (isValid) {
            logger.info("Код верификации для номера {} успешно подтвержден.", phone);
            // verificationCodes.remove(phone); // Удаляем код сразу после успешной проверки
            // Не удаляем здесь, так как clearCode вызывается отдельно в контроллере
        } else {
            logger.warn("Введен неверный код верификации для номера {}. Ожидался: {}, Получен: {}", phone, data.code, code);
        }
        return isValid;
    }

    /**
     * Принудительно удаляет код верификации для указанного номера телефона.
     * Вызывается после успешной верификации или при необходимости очистки.
     * @param phone Номер телефона (нормализованный).
     */
    public void clearCode(String phone) {
        VerificationData removedData = verificationCodes.remove(phone);
        if (removedData != null) {
            logger.info("Удален код верификации для номера: {}", phone);
        } else {
            logger.debug("Попытка удаления несуществующего кода верификации для номера: {}", phone);
        }
    }

    /**
     * Генерирует случайный цифровой код указанной длины.
     * @return Строка с цифровым кодом.
     */
    private String generateCode() {
        if (codeLength <= 0) {
            throw new IllegalArgumentException("Длина кода должна быть положительным числом.");
        }
        SecureRandom random = new SecureRandom();
        return IntStream.range(0, codeLength)
                .map(i -> random.nextInt(10)) // Генерируем цифры от 0 до 9
                .mapToObj(String::valueOf)
                .collect(Collectors.joining());
    }

    /**
     * Форматирует текст для синтеза речи (TTS), разделяя цифры кода пробелами.
     * @param code Код верификации.
     * @return Строка сообщения для TTS.
     */
    private String formatTtsMessage(String code) {
        // Разделяем цифры пробелами: "1234" -> "1 2 3 4"
        String spacedCode = code.chars()
                .mapToObj(c -> String.valueOf((char) c))
                .collect(Collectors.joining(" "));
        // Формируем полное сообщение
        return "Ваш код подтверждения: " + spacedCode + ". Повторяю: " + spacedCode + ".";
    }

    /**
     * Приводит номер телефона к формату E.164, но **без** символа '+',
     * как этого ожидает API Novofon в поле 'contact'.
     * @param phone Номер телефона (предполагается нормализованный с '+').
     * @return Номер телефона в формате E.164 без '+'.
     */
    private String formatPhoneNumberForApi(String phone) {
        if (phone == null || !phone.startsWith("+")) {
            logger.warn("Некорректный формат телефона для API Novofon: {}", phone);
            // Возвращаем как есть или выбрасываем исключение
            return phone != null ? phone.replaceAll("[^\\d]", "") : "";
        }
        // Удаляем '+' и все нецифровые символы на всякий случай
        return phone.substring(1).replaceAll("[^\\d]", "");
    }

    // --- Внутренние классы для (де)сериализации JSON ---

    // Класс для хранения кода и времени его создания
    private static class VerificationData {
        final String code;
        final LocalDateTime timestamp;

        VerificationData(String code, LocalDateTime timestamp) {
            this.code = code;
            this.timestamp = timestamp;
        }
    }

    // Классы запроса и ответа Novofon JSON-RPC (остаются без изменений)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class NovofonJsonRpcRequest {
        @JsonProperty("jsonrpc")
        private final String jsonrpc = "2.0";
        private String method;
        private NovofonParams params;
        private String id;

        public NovofonJsonRpcRequest(String method, NovofonParams params, String id) {
            this.method = method; this.params = params; this.id = id;
        }
        public String getJsonrpc() { return jsonrpc; }
        public String getMethod() { return method; }
        public NovofonParams getParams() { return params; }
        public String getId() { return id; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class NovofonParams {
        @JsonProperty("access_token") private String accessToken;
        @JsonProperty("virtual_phone_number") private String virtualPhoneNumber;
        private String contact;
        @JsonProperty("contact_message") private NovofonTtsMessage contactMessage;

        public NovofonParams(String accessToken, String virtualPhoneNumber, String contact, NovofonTtsMessage contactMessage) {
            this.accessToken = accessToken; this.virtualPhoneNumber = virtualPhoneNumber; this.contact = contact; this.contactMessage = contactMessage;
        }
        public String getAccessToken() { return accessToken; }
        public String getVirtualPhoneNumber() { return virtualPhoneNumber; }
        public String getContact() { return contact; }
        public NovofonTtsMessage getContactMessage() { return contactMessage; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class NovofonTtsMessage {
        private String type; private String value;
        public NovofonTtsMessage(String type, String value) { this.type = type; this.value = value; }
        public String getType() { return type; } public String getValue() { return value; }
    }

    private static class NovofonResponse {
        private String jsonrpc; private String id; private NovofonResult result; private NovofonError error;
        public String getJsonrpc() { return jsonrpc; } public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
        public String getId() { return id; } public void setId(String id) { this.id = id; }
        public NovofonResult getResult() { return result; } public void setResult(NovofonResult result) { this.result = result; }
        public NovofonError getError() { return error; } public void setError(NovofonError error) { this.error = error; }
    }

    private static class NovofonResult {
        private NovofonResultData data;
        public NovofonResultData getData() { return data; } public void setData(NovofonResultData data) { this.data = data; }
    }

    private static class NovofonResultData {
        @JsonProperty("call_session_id") private Long callSessionId;
        public Long getCallSessionId() { return callSessionId; } public void setCallSessionId(Long callSessionId) { this.callSessionId = callSessionId; }
    }

    private static class NovofonError {
        private int code; private String message; private Object data;
        public int getCode() { return code; } public void setCode(int code) { this.code = code; }
        public String getMessage() { return message; } public void setMessage(String message) { this.message = message; }
        public Object getData() { return data; } public void setData(Object data) { this.data = data; }
    }
}