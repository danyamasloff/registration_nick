package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpSession;
import java.util.Optional;

/**
 * Контроллер для управления профилем пользователя, включая привязку Telegram.
 */
@Controller
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);

    private final UserService userService;

    @Value("${telegram.bot.id}")
    private String telegramBotId;

    @Value("${telegram.bot.username}")
    private String telegramBotUsername;

    @Value("${telegram.auth.redirect-uri}")
    private String telegramRedirectUri;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Отображает страницу профиля пользователя.
     */
    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public String showProfilePage(Model model) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userPhone = authentication.getName();

        Optional<User> userOpt = userService.findByPhone(userPhone);
        if (userOpt.isEmpty()) {
            logger.error("Не найден профиль для аутентифицированного пользователя: {}", userPhone);
            return "redirect:/login?error=profile_not_found";
        }

        model.addAttribute("user", userOpt.get());

        // Добавляем для Telegram Widget
        model.addAttribute("telegramBotUsername", telegramBotUsername);
        model.addAttribute("telegramRedirectUri", telegramRedirectUri);

        return "profile";
    }

    /**
     * Инициирует процесс привязки/изменения Telegram-аккаунта.
     * Генерирует URL для Telegram Login Widget и перенаправляет пользователя.
     */
    @GetMapping("/profile/link-telegram")
    @PreAuthorize("isAuthenticated()")
    public String linkTelegramRedirect(HttpSession session, RedirectAttributes redirectAttributes) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userPhone = authentication.getName();
        logger.info("Пользователь {} инициировал привязку Telegram", userPhone);

        // Сохраняем телефон и флаг привязки в сессии
        session.setAttribute("phoneForTelegramLinking", userPhone);
        session.setAttribute("telegramLinkingMode", true);

        // Проверяем наличие необходимых параметров
        if (telegramBotId == null || telegramRedirectUri == null || appBaseUrl == null) {
            logger.error("Не заданы параметры Telegram в application.properties");
            redirectAttributes.addFlashAttribute("error", "Ошибка конфигурации Telegram.");
            return "redirect:/profile";
        }

        try {
            // Для отладки: проверяем, что параметры заданы корректно
            logger.info("Telegram параметры: botId={}, redirectUri={}, baseUrl={}",
                    telegramBotId, telegramRedirectUri, appBaseUrl);

            // Формируем URL для Telegram OAuth (без добавления параметров к redirect_uri)
            String telegramAuthUrl = UriComponentsBuilder.newInstance()
                    .scheme("https")
                    .host("oauth.telegram.org")
                    .path("/auth")
                    .queryParam("bot_id", telegramBotId)
                    .queryParam("origin", appBaseUrl)
                    .queryParam("request_access", "write")
                    .queryParam("return_to", telegramRedirectUri) // Используем чистый URL без параметров
                    .build()
                    .toUriString();

            logger.info("Перенаправление на URL Telegram: {}", telegramAuthUrl);
            return "redirect:" + telegramAuthUrl;
        } catch (Exception e) {
            logger.error("Ошибка при генерации URL для Telegram: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Ошибка привязки Telegram.");
            return "redirect:/profile";
        }
    }

    /**
     * Страница диагностики для отладки работы Telegram
     */
    @GetMapping("/profile/telegram-debug")
    @PreAuthorize("isAuthenticated()")
    public String telegramDebugPage(Model model) {
        model.addAttribute("telegramBotId", telegramBotId);
        model.addAttribute("telegramBotUsername", telegramBotUsername);
        model.addAttribute("telegramRedirectUri", telegramRedirectUri);
        model.addAttribute("appBaseUrl", appBaseUrl);

        return "telegram_debug";
    }
}