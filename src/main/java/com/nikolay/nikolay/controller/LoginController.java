package com.nikolay.nikolay.controller;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Контроллер для отображения страницы входа.
 */
@Controller
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    @Value("${telegram.auth.redirect-uri}")
    private String telegramRedirectUri;

    @Value("${telegram.bot.username}")
    private String telegramBotUsername;


    @Value("${app.base-url}")
    private String appBaseUrl;


    /**
     * Отображает форму входа.
     * @param qrCode Необязательный параметр QR-кода из URL (если пользователь перешел по ссылке).
     * @param error Наличие параметра error в URL указывает на неудачную попытку входа.
     * @param logout Наличие параметра logout в URL указывает, что пользователь только что вышел.
     * @param model Модель для передачи данных в шаблон.
     * @param session Сессия для сохранения QR-кода.
     * @return Имя шаблона страницы входа ("login").
     */
    @GetMapping("/login")
    public String showLoginForm(
            @RequestParam(value = "qrCode", required = false) String qrCode,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            Model model,
            HttpSession session) {

        logger.info("Запрос на страницу входа. qrCode={}, error={}, logout={}", qrCode, error, logout);

        if (qrCode != null && !qrCode.isEmpty()) {
            session.setAttribute("qrCode", qrCode);
            logger.info("QR-код {} сохранен в сессии из параметра URL.", qrCode);
        }

        // Добавляем параметры для кнопки Telegram Login Widget
        model.addAttribute("telegramBotUsername", telegramBotUsername);
        model.addAttribute("telegramRedirectUri", telegramRedirectUri);
        model.addAttribute("appBaseUrl", appBaseUrl);

        return "login";
    }
}