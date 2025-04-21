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
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class NovofonVerificationService implements InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(NovofonVerificationService.class);
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, VerificationData> verificationCodes = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate;

    @Value("${novofon.api.url}")
    private String apiUrl;

    @Value("${novofon.api.secret}")
    private String apiSecret;

    @Value("${novofon.virtual_number}")
    private String virtualNumber;

    @Value("${novofon.verification.code_length}")
    private int codeLength;

    public NovofonVerificationService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        logger.info("Проверка конфигурации Novofon:");
        logger.info("API URL: {}", apiUrl);
        logger.info("Виртуальный номер: {}", virtualNumber);

        if (apiSecret != null && apiSecret.length() > 4) {
            logger.info("API Secret: {}...{} (длина: {})",
                    apiSecret.substring(0, 4),
                    apiSecret.substring(apiSecret.length() - 4),
                    apiSecret.length());
        } else {
            logger.error("API Secret отсутствует или неверного формата!");
        }
    }

    /**
     * Отправляет код верификации через голосовой звонок
     * @param phone номер телефона пользователя
     */
    public void sendVerificationCode(String phone) {
        try {
            String code = generateCode();
            String ttsMessage = formatTtsMessage(code);
            String requestId = UUID.randomUUID().toString();
            String formattedPhone = formatPhoneNumber(phone);

            logger.info("Подготовка звонка для верификации: номер={}, код={}", formattedPhone, code);

            NovofonTtsMessage contactMessage = new NovofonTtsMessage("tts", ttsMessage);
            NovofonParams params = new NovofonParams(apiSecret, virtualNumber, formattedPhone, contactMessage);
            NovofonJsonRpcRequest request = new NovofonJsonRpcRequest("start.informer_call", params, requestId);

            try {
                logger.info("Отправляемый JSON запрос: {}", objectMapper.writeValueAsString(request));
            } catch (Exception e) {
                logger.warn("Не удалось сериализовать запрос для логгирования", e);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "NikolayApp/1.0");
            HttpEntity<NovofonJsonRpcRequest> entity = new HttpEntity<>(request, headers);

            logger.info("Отправка звонка для верификации на номер: {}", formattedPhone);
            ResponseEntity<NovofonResponse> response = restTemplate.postForEntity(apiUrl, entity, NovofonResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                NovofonResponse body = response.getBody();
                if (body.getError() != null) {
                    logger.error("Ошибка API Новофон: Код={}, Сообщение={}",
                            body.getError().getCode(), body.getError().getMessage());

                    try {
                        logger.error("Полный ответ: {}", objectMapper.writeValueAsString(body));
                    } catch (Exception e) {
                        logger.error("Ошибка сериализации ответа", e);
                    }
                } else if (body.getResult() != null) {
                    logger.info("Звонок успешно инициирован, ID сессии: {}",
                            body.getResult().getData().getCallSessionId());
                    verificationCodes.put(phone, new VerificationData(code, LocalDateTime.now()));
                }
            } else {
                logger.error("Неожиданный ответ от API: статус={}", response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Ошибка при отправке звонка верификации", e);
        }
    }

    /**
     * Проверяет введенный код верификации
     * @param phone номер телефона пользователя
     * @param code код, введенный пользователем
     * @return true если код верный и не истек срок действия
     */
    public boolean verifyCode(String phone, String code) {
        VerificationData data = verificationCodes.get(phone);
        if (data == null) {
            logger.warn("Не найден код верификации для номера: {}", phone);
            return false;
        }

        if (LocalDateTime.now().isAfter(data.timestamp.plus(CODE_TTL))) {
            logger.warn("Истек срок действия кода для номера: {}", phone);
            verificationCodes.remove(phone);
            return false;
        }

        boolean isValid = data.code.equals(code);
        if (isValid) {
            logger.info("Успешная верификация для номера: {}", phone);
            verificationCodes.remove(phone);
        } else {
            logger.warn("Неверный код для номера: {}", phone);
        }
        return isValid;
    }

    /**
     * Удаляет код верификации для номера телефона
     * @param phone номер телефона пользователя
     */
    public void clearCode(String phone) {
        verificationCodes.remove(phone);
        logger.debug("Удален код верификации для номера: {}", phone);
    }

    /**
     * Генерирует случайный цифровой код
     * @return строка с цифровым кодом
     */
    private String generateCode() {
        SecureRandom random = new SecureRandom();
        return IntStream.range(0, codeLength)
                .map(i -> random.nextInt(10))
                .mapToObj(String::valueOf)
                .collect(Collectors.joining());
    }

    /**
     * Форматирует текст для синтеза речи с пробелами между цифрами
     * @param code код верификации
     * @return форматированное сообщение для TTS
     */
    private String formatTtsMessage(String code) {
        String spacedCode = code.chars()
                .mapToObj(c -> String.valueOf((char) c))
                .collect(Collectors.joining(" "));
        return "Код подтверждения " + spacedCode + ".";
    }

    /**
     * Приводит номер телефона к формату E.164 без символа +
     * @param phone номер телефона
     * @return номер в формате E.164 без символа +
     */
    private String formatPhoneNumber(String phone) {
        // Удаляем все нецифровые символы
        String cleanPhone = phone.replaceAll("[^\\d]", "");

        // Если номер начинается с "8" для России, заменяем на "7"
        if (cleanPhone.startsWith("8") && cleanPhone.length() == 11) {
            cleanPhone = "7" + cleanPhone.substring(1);
        }

        // Проверяем длину номера и добавляем "7" для России если необходимо
        if (cleanPhone.length() == 10) {
            cleanPhone = "7" + cleanPhone;
        }

        logger.info("Форматирование телефона для API: исходный={}, форматированный={}", phone, cleanPhone);
        return cleanPhone;
    }

    // Классы данных для JSON-RPC запроса и ответа
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class NovofonJsonRpcRequest {
        @JsonProperty("jsonrpc")
        private final String jsonrpc = "2.0";
        private String method;
        private NovofonParams params;
        private String id;

        public NovofonJsonRpcRequest(String method, NovofonParams params, String id) {
            this.method = method;
            this.params = params;
            this.id = id;
        }

        public String getJsonrpc() { return jsonrpc; }
        public String getMethod() { return method; }
        public NovofonParams getParams() { return params; }
        public String getId() { return id; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class NovofonParams {
        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("virtual_phone_number")
        private String virtualPhoneNumber;

        private String contact;

        @JsonProperty("contact_message")
        private NovofonTtsMessage contactMessage;

        public NovofonParams(String accessToken, String virtualPhoneNumber, String contact, NovofonTtsMessage contactMessage) {
            this.accessToken = accessToken;
            this.virtualPhoneNumber = virtualPhoneNumber;
            this.contact = contact;
            this.contactMessage = contactMessage;
        }

        public String getAccessToken() { return accessToken; }
        public String getVirtualPhoneNumber() { return virtualPhoneNumber; }
        public String getContact() { return contact; }
        public NovofonTtsMessage getContactMessage() { return contactMessage; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class NovofonTtsMessage {
        private String type;
        private String value;

        public NovofonTtsMessage(String type, String value) {
            this.type = type;
            this.value = value;
        }

        public String getType() { return type; }
        public String getValue() { return value; }
    }

    private static class NovofonResponse {
        private String jsonrpc;
        private String id;
        private NovofonResult result;
        private NovofonError error;

        public String getJsonrpc() { return jsonrpc; }
        public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public NovofonResult getResult() { return result; }
        public void setResult(NovofonResult result) { this.result = result; }
        public NovofonError getError() { return error; }
        public void setError(NovofonError error) { this.error = error; }
    }

    private static class NovofonResult {
        private NovofonResultData data;

        public NovofonResultData getData() { return data; }
        public void setData(NovofonResultData data) { this.data = data; }
    }

    private static class NovofonResultData {
        @JsonProperty("call_session_id")
        private Long callSessionId;

        public Long getCallSessionId() { return callSessionId; }
        public void setCallSessionId(Long callSessionId) { this.callSessionId = callSessionId; }
    }

    private static class NovofonError {
        private int code;
        private String message;
        @JsonProperty("data")
        private Object data;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }
    }

    private static class VerificationData {
        private final String code;
        private final LocalDateTime timestamp;

        public VerificationData(String code, LocalDateTime timestamp) {
            this.code = code;
            this.timestamp = timestamp;
        }
    }
}