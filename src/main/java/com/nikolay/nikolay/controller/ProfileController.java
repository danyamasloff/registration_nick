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
 * Контроллер для управления профилем пользователя, включая
 * отображение профиля, привязку и отвязку Telegram-аккаунта,
 * а также страницу диагностики.
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
     *
     * @param model модель MVC для передачи данных в представление
     * @return имя шаблона профиля или редирект на страницу логина при ошибке
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
        model.addAttribute("telegramBotUsername", telegramBotUsername);
        model.addAttribute("telegramRedirectUri", telegramRedirectUri);

        return "profile";
    }

    /**
     * Инициирует процесс привязки или изменения Telegram-аккаунта.
     * <p>Сохраняет в сессии телефон пользователя и флаг режима привязки,
     * проверяет параметры конфигурации и генерирует URL для Telegram Login Widget.</p>
     *
     * @param session HTTP-сессия для хранения флага привязки
     * @param redirectAttributes объект для передачи flash-сообщений при редиректе
     * @return редирект на OAuth-URL Telegram или возврат на профиль при ошибке
     */
    @GetMapping("/profile/link-telegram")
    @PreAuthorize("isAuthenticated()")
    public String linkTelegramRedirect(HttpSession session, RedirectAttributes redirectAttributes) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userPhone = authentication.getName();
        logger.info("Пользователь {} инициировал привязку Telegram", userPhone);

        session.setAttribute("phoneForTelegramLinking", userPhone);
        session.setAttribute("telegramLinkingMode", true);

        if (telegramBotId == null || telegramRedirectUri == null || appBaseUrl == null) {
            logger.error("Не заданы параметры Telegram в application.properties");
            redirectAttributes.addFlashAttribute("error", "Ошибка конфигурации Telegram.");
            return "redirect:/profile";
        }

        try {
            logger.info("Telegram параметры: botId={}, redirectUri={}, baseUrl={}",
                    telegramBotId, telegramRedirectUri, appBaseUrl);

            String telegramAuthUrl = UriComponentsBuilder.newInstance()
                    .scheme("https")
                    .host("oauth.telegram.org")
                    .path("/auth")
                    .queryParam("bot_id", telegramBotId)
                    .queryParam("origin", appBaseUrl)
                    .queryParam("request_access", "write")
                    .queryParam("return_to", telegramRedirectUri)
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
     * Отвязывает текущий Telegram-аккаунт от профиля пользователя.
     * <p>Устанавливает поля telegramId и telegramUsername в null
     * и сохраняет изменения в базе данных.</p>
     *
     * @param redirectAttributes объект для передачи flash-сообщений при редиректе
     * @return редирект на страницу профиля
     */
    @GetMapping("/profile/unlink-telegram")
    @PreAuthorize("isAuthenticated()")
    public String unlinkTelegram(RedirectAttributes redirectAttributes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String phone = auth.getName();
        Optional<User> userOpt = userService.findByPhone(phone);
        if (userOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Профиль не найден");
            return "redirect:/profile";
        }
        User user = userOpt.get();

        boolean ok = userService.updateTelegramInfo(user.getId(), null, null);
        if (ok) {
            redirectAttributes.addFlashAttribute("success", "Telegram-аккаунт отвязан");
        } else {
            redirectAttributes.addFlashAttribute("error", "Не удалось отвязать Telegram");
        }
        return "redirect:/profile";
    }

    /**
     * Отображает страницу диагностики для отладки работы Telegram.
     *
     * @param model модель MVC для передачи данных в представление
     * @return имя шаблона страницы диагностики
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