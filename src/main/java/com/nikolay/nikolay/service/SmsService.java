package com.nikolay.nikolay.service;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class SmsService {

    private static final Logger logger = LoggerFactory.getLogger(SmsService.class);
    private final RestTemplate restTemplate;

    public SmsService() {
        this.restTemplate = new RestTemplate();
    }

    public String sendSms(String phone, String message) {
        String login = "subdubliner";
        String password = "HKK8weBX51mE";

        logger.info("Отправка SMS на номер: {}", phone);
        String uri = UriComponentsBuilder.fromHttpUrl("https://smsc.ru/sys/send.php")
                .queryParam("login", login)
                .queryParam("psw", password)
                .queryParam("phones", phone)
                .queryParam("mes", message)
                .queryParam("fmt", "3")  // формат JSON для удобства
                .toUriString();

        logger.info("Сформированный URI для запроса: {}", uri);

        try {
            String response = restTemplate.getForObject(uri, String.class);
            logger.info("Ответ от сервера SMS: {}", response);
            return response;
        } catch (Exception e) {
            logger.error("Ошибка при отправке SMS: {}", e.getMessage());
            throw new RuntimeException("Ошибка при отправке SMS: " + e.getMessage());
        }
    }
}
