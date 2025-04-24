package com.nikolay.nikolay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nikolay.nikolay.dto.TelegramAuthDTO;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.TelegramAuthService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Controller
public class TelegramCallbackController {
    private static final Logger logger = LoggerFactory.getLogger(TelegramCallbackController.class);

    private final TelegramAuthService telegramAuthService;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TelegramCallbackController(
            TelegramAuthService telegramAuthService,
            UserService userService) {
        this.telegramAuthService = telegramAuthService;
        this.userService = userService;
    }

    /**
     * Обрабатывает callback от Telegram Login Widget.
     * Поддерживает два режима:
     * 1. Без параметров - возвращает HTML страницу для обработки хеш-фрагмента
     * 2. С параметрами - напрямую обрабатывает данные Telegram (режим redirect)
     */
    @GetMapping("/telegram-callback")
    public String handleTelegramCallback(
            @RequestParam(required = false) Map<String, String> telegramParams,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        // Если запрос без параметров - возвращаем HTML для обработки хеш-фрагмента
        if (telegramParams.isEmpty()) {
            return "telegram_callback";
        }

        try {
            // Создаем DTO из параметров
            TelegramAuthDTO authData = createAuthDtoFromParams(telegramParams);

            // Проверяем данные
            if (!telegramAuthService.validateTelegramResponse(authData)) {
                redirectAttributes.addFlashAttribute("error", "Ошибка проверки данных от Telegram");
                return "redirect:/login";
            }

            // Определяем режим работы: привязка или вход
            Boolean isLinkingMode = (Boolean) session.getAttribute("telegramLinkingMode");
            String phoneForLinking = (String) session.getAttribute("phoneForTelegramLinking");

            // Очищаем атрибуты сессии
            session.removeAttribute("telegramLinkingMode");
            session.removeAttribute("phoneForTelegramLinking");

            // Обрабатываем в зависимости от режима
            if (Boolean.TRUE.equals(isLinkingMode) && phoneForLinking != null) {
                return handleTelegramLinking(phoneForLinking, authData, redirectAttributes);
            } else {
                return handleTelegramLogin(authData, session, redirectAttributes);
            }

        } catch (Exception e) {
            logger.error("Ошибка при обработке Telegram callback", e);
            redirectAttributes.addFlashAttribute("error", "Ошибка обработки данных Telegram");
            return "redirect:/login";
        }
    }

    /**
     * Обрабатывает AJAX-запрос с данными от Telegram из хеш-фрагмента
     */
    @PostMapping("/process-telegram-auth")
    @ResponseBody
    public ResponseEntity<?> processTelegramAuth(@RequestBody Map<String, Object> authData, HttpSession session) {
        try {
            // Создаем DTO из полученных данных
            TelegramAuthDTO telegramAuthDTO = new TelegramAuthDTO();

            // Маппинг полей из JSON в DTO
            if (authData.containsKey("id")) telegramAuthDTO.setId(Long.valueOf(authData.get("id").toString()));
            if (authData.containsKey("first_name")) telegramAuthDTO.setFirst_name((String) authData.get("first_name"));
            if (authData.containsKey("last_name")) telegramAuthDTO.setLast_name((String) authData.get("last_name"));
            if (authData.containsKey("username")) telegramAuthDTO.setUsername((String) authData.get("username"));
            if (authData.containsKey("photo_url")) telegramAuthDTO.setPhoto_url((String) authData.get("photo_url"));
            if (authData.containsKey("auth_date")) telegramAuthDTO.setAuth_date(authData.get("auth_date").toString());
            if (authData.containsKey("hash")) telegramAuthDTO.setHash((String) authData.get("hash"));

            // Валидация данных
            if (!telegramAuthService.validateTelegramResponse(telegramAuthDTO)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Ошибка проверки данных от Telegram"
                ));
            }

            // Получаем режим (привязка/авторизация)
            Boolean isLinkingMode = (Boolean) session.getAttribute("telegramLinkingMode");
            String phoneForLinking = (String) session.getAttribute("phoneForTelegramLinking");

            // Очищаем атрибуты сессии
            session.removeAttribute("telegramLinkingMode");
            session.removeAttribute("phoneForTelegramLinking");

            // Обрабатываем данные в зависимости от режима
            if (Boolean.TRUE.equals(isLinkingMode) && phoneForLinking != null) {
                return processTelegramLinking(phoneForLinking, telegramAuthDTO);
            } else {
                return processTelegramLogin(telegramAuthDTO, session);
            }

        } catch (Exception e) {
            logger.error("Ошибка при обработке данных Telegram", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Ошибка обработки данных: " + e.getMessage()
            ));
        }
    }

    /**
     * Создает объект TelegramAuthDTO из параметров запроса
     */
    private TelegramAuthDTO createAuthDtoFromParams(Map<String, String> params) {
        TelegramAuthDTO dto = new TelegramAuthDTO();

        if (params.containsKey("id"))
            dto.setId(Long.parseLong(params.get("id")));
        if (params.containsKey("first_name"))
            dto.setFirst_name(params.get("first_name"));
        if (params.containsKey("last_name"))
            dto.setLast_name(params.get("last_name"));
        if (params.containsKey("username"))
            dto.setUsername(params.get("username"));
        if (params.containsKey("photo_url"))
            dto.setPhoto_url(params.get("photo_url"));
        if (params.containsKey("auth_date"))
            dto.setAuth_date(params.get("auth_date"));
        if (params.containsKey("hash"))
            dto.setHash(params.get("hash"));

        return dto;
    }

    /**
     * Обрабатывает привязку Telegram аккаунта к существующему пользователю
     */
    private String handleTelegramLinking(
            String phone,
            TelegramAuthDTO authData,
            RedirectAttributes redirectAttributes) {

        Long telegramId = authData.getId();
        String telegramUsername = authData.getUsername();

        logger.info("Обработка привязки Telegram: id={}, username={} для телефона {}",
                telegramId, telegramUsername, phone);

        // Проверяем, не привязан ли уже этот Telegram к другому пользователю
        Optional<User> existingUserWithTgId = userService.findByTelegramId(telegramId);
        if (existingUserWithTgId.isPresent() && !existingUserWithTgId.get().getPhone().equals(phone)) {
            logger.warn("Telegram id={} уже привязан к другому пользователю: {}",
                    telegramId, existingUserWithTgId.get().getPhone());
            redirectAttributes.addFlashAttribute("error",
                    "Этот аккаунт Telegram уже привязан к другому профилю");
            return "redirect:/profile";
        }

        // Находим пользователя по телефону
        Optional<User> userOpt = userService.findByPhone(phone);
        if (userOpt.isEmpty()) {
            logger.warn("Пользователь с телефоном {} не найден", phone);
            redirectAttributes.addFlashAttribute("error",
                    "Ваш профиль не найден. Попробуйте войти снова");
            return "redirect:/login";
        }

        User user = userOpt.get();

        // Обновляем Telegram данные
        boolean updated = userService.updateTelegramInfo(user.getId(), telegramId, telegramUsername);

        if (updated) {
            logger.info("Telegram id={}, username={} успешно привязан к пользователю {}",
                    telegramId, telegramUsername, phone);
            redirectAttributes.addFlashAttribute("success",
                    "Аккаунт Telegram успешно привязан к вашему профилю!");
            try {
                // Обновляем аутентификацию
                telegramAuthService.authenticateUser(user);
            } catch (Exception e) {
                logger.warn("Не удалось обновить данные аутентификации", e);
            }
        } else {
            logger.error("Не удалось обновить Telegram для пользователя {}", phone);
            redirectAttributes.addFlashAttribute("error",
                    "Не удалось сохранить привязку Telegram. Попробуйте еще раз");
        }

        return "redirect:/profile";
    }

    /**
     * Обрабатывает вход через Telegram (когда аккаунт уже привязан)
     */
    private String handleTelegramLogin(
            TelegramAuthDTO authData,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Long telegramId = authData.getId();

        // Ищем пользователя по Telegram ID
        Optional<User> userOpt = userService.findByTelegramId(telegramId);

        if (userOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Этот аккаунт Telegram не зарегистрирован в системе. " +
                            "Войдите по номеру телефона и привяжите Telegram в профиле");
            return "redirect:/login";
        }

        User user = userOpt.get();

        try {
            // Аутентифицируем пользователя
            telegramAuthService.authenticateUser(user);

            // Обрабатываем возможный QR-код из сессии
            String qrCode = (String) session.getAttribute("qrCode");
            String redirectUrl = "/";

            if (qrCode != null && !qrCode.isEmpty()) {
                session.removeAttribute("qrCode");
                userService.handleReferralLink(user, qrCode);
                userService.registerUser(user);
            }

            redirectAttributes.addFlashAttribute("success", "Вы успешно вошли через Telegram!");
            return "redirect:" + redirectUrl;

        } catch (Exception e) {
            logger.error("Ошибка при аутентификации через Telegram", e);
            redirectAttributes.addFlashAttribute("error", "Произошла ошибка входа. Попробуйте позже");
            return "redirect:/login";
        }
    }

    /**
     * Обрабатывает привязку Telegram аккаунта через AJAX
     */
    private ResponseEntity<?> processTelegramLinking(String phone, TelegramAuthDTO authData) {
        Long telegramId = authData.getId();
        String telegramUsername = authData.getUsername();

        // Проверяем, не привязан ли уже этот Telegram к другому пользователю
        Optional<User> existingUserWithTgId = userService.findByTelegramId(telegramId);
        if (existingUserWithTgId.isPresent() && !existingUserWithTgId.get().getPhone().equals(phone)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Этот аккаунт Telegram уже привязан к другому профилю",
                    "redirectUrl", "/profile",
                    "telegramId", telegramId
            ));
        }

        // Находим пользователя по телефону
        Optional<User> userOpt = userService.findByPhone(phone);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Пользователь не найден"
            ));
        }

        User user = userOpt.get();

        // Если Telegram ID уже такой же
        if (telegramId.equals(user.getTelegramId())) {
            if (telegramUsername != null && !telegramUsername.equals(user.getTelegram())) {
                userService.updateTelegramInfo(user.getId(), telegramId, telegramUsername);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Имя пользователя Telegram обновлено",
                        "redirectUrl", "/profile"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Этот аккаунт Telegram уже привязан к вашему профилю",
                        "redirectUrl", "/profile"
                ));
            }
        }

        // Обновляем Telegram данные
        boolean updated = userService.updateTelegramInfo(user.getId(), telegramId, telegramUsername);

        if (updated) {
            try {
                telegramAuthService.authenticateUser(user);
            } catch (Exception e) {
                logger.warn("Не удалось обновить данные аутентификации", e);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Аккаунт Telegram успешно привязан к вашему профилю!",
                    "redirectUrl", "/profile"
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Не удалось сохранить привязку Telegram. Попробуйте еще раз"
            ));
        }
    }

    /**
     * Обрабатывает вход через Telegram через AJAX
     */
    private ResponseEntity<?> processTelegramLogin(TelegramAuthDTO authData, HttpSession session) {
        Long telegramId = authData.getId();

        // Ищем пользователя по Telegram ID
        Optional<User> userOpt = userService.findByTelegramId(telegramId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Этот аккаунт Telegram не зарегистрирован в системе",
                    "redirectUrl", "/login"
            ));
        }

        User user = userOpt.get();

        try {
            // Аутентифицируем пользователя
            telegramAuthService.authenticateUser(user);

            // Добавляем атрибуты в сессию для двойной проверки
            session.setAttribute("TELEGRAM_AUTH_SUCCESS", true);
            session.setAttribute("TELEGRAM_AUTH_USER_ID", user.getId());

            // Обрабатываем возможный QR-код из сессии
            String qrCode = (String) session.getAttribute("qrCode");
            String redirectUrl = "/";

            if (qrCode != null && !qrCode.isEmpty()) {
                session.removeAttribute("qrCode");
                userService.handleReferralLink(user, qrCode);
                userService.registerUser(user);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Вы успешно вошли через Telegram!",
                    "redirectUrl", redirectUrl,
                    "telegramId", telegramId,
                    "userId", user.getId()
            ));

        } catch (Exception e) {
            logger.error("Ошибка при аутентификации через Telegram", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Произошла ошибка входа: " + e.getMessage(),
                    "redirectUrl", "/login"
            ));
        }
    }
}