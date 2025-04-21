package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.dto.TelegramAuthDTO;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.TelegramAuthService;
import com.nikolay.nikolay.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Контроллер для обработки Telegram callbacks
 */
@Controller
public class TelegramCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(TelegramCallbackController.class);

    private final TelegramAuthService telegramAuthService;
    private final UserService userService;
    private final UserDetailsService userDetailsService;

    public TelegramCallbackController(TelegramAuthService telegramAuthService,
                                      UserService userService,
                                      UserDetailsService userDetailsService) {
        this.telegramAuthService = telegramAuthService;
        this.userService = userService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Обрабатывает callback от Telegram после авторизации
     * Принимает параметры напрямую из URL
     */
    @GetMapping("/telegram-callback")
    public String handleTelegramCallback(
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "first_name", required = false) String firstName,
            @RequestParam(value = "last_name", required = false) String lastName,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "photo_url", required = false) String photoUrl,
            @RequestParam(value = "auth_date", required = false) String authDate,
            @RequestParam(value = "hash", required = false) String hash,
            @RequestParam(value = "ref", required = false) String referralLink) {

        logger.info("Получен callback от Telegram для пользователя: @{}", username);

        // Собираем данные в DTO
        TelegramAuthDTO authData = new TelegramAuthDTO();
        authData.setId(id);
        authData.setFirst_name(firstName);
        authData.setLast_name(lastName);
        authData.setUsername(username);
        authData.setPhoto_url(photoUrl);
        authData.setAuth_date(authDate);
        authData.setHash(hash);
        authData.setReferralLink(referralLink);

        // Проверяем данные авторизации
        if (!telegramAuthService.validateTelegramResponse(authData)) {
            logger.warn("Недействительные данные авторизации Telegram");
            return "redirect:/login?error=telegram_invalid";
        }

        try {
            // Ищем или создаем пользователя
            Optional<User> userOpt = userService.findByTelegram(username);
            User user;

            if (userOpt.isPresent()) {
                // Существующий пользователь
                user = userOpt.get();
                logger.info("Найден существующий пользователь с Telegram: @{}", username);
            } else {
                // Новый пользователь - регистрация через Telegram
                user = telegramAuthService.registerUserWithTelegram(authData);
                logger.info("Создан новый пользователь из данных Telegram: @{}", username);
            }

            // Перенаправляем на страницу установки реального пароля
            return "redirect:/telegram/set-password?telegram=" + user.getTelegram();
        } catch (Exception e) {
            logger.error("Ошибка при обработке Telegram callback", e);
            return "redirect:/login?error=telegram_error";
        }
    }
}