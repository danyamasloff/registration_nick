package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.dto.TelegramAuthDTO;
import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.TelegramAuthService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity; // <<< ИМПОРТ для ResponseEntity
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody; // <<< ИМПОРТ для @ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;

/**
 * Контроллер для обработки callback-запроса от Telegram после аутентификации.
 * ИСПРАВЛЕНО: Не делаем редирект после пустого callback'а (проверки домена),
 * возвращаем простой HTTP 200 OK, чтобы браузер мог получить второй callback.
 */
@Controller
public class TelegramCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(TelegramCallbackController.class);

    private final TelegramAuthService telegramAuthService;
    private final UserService userService;
    private final InstructionService instructionService;

    public TelegramCallbackController(
            TelegramAuthService telegramAuthService,
            UserService userService,
            InstructionService instructionService) {
        this.telegramAuthService = telegramAuthService;
        this.userService = userService;
        this.instructionService = instructionService;
    }

    /**
     * Обрабатывает callback-запрос от Telegram после аутентификации пользователя.
     * Возвращает либо строку для редиректа (при успехе/ошибке обработки данных),
     * либо ResponseEntity<Void> для пустого callback'а.
     */
    @GetMapping("/telegram-callback")
    // @ResponseBody // Добавляем @ResponseBody, чтобы Spring не искал шаблон для ResponseEntity
    // ИЗМЕНЕНИЕ: Возвращаемый тип Object, чтобы можно было вернуть и String (редирект) и ResponseEntity
    public Object handleTelegramCallback(
            @RequestParam Map<String, String> telegramParams,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            HttpServletRequest request) {

        logger.info(">>> handleTelegramCallback INVOKED. Session ID: {}. Parameters present: {}",
                session.getId(), !telegramParams.isEmpty());

        logAllRequestDetails(request);

        String idStr = telegramParams.get("id");
        String hash = telegramParams.get("hash");
        String authDate = telegramParams.get("auth_date");

        // Проверка пустого запроса (проверка домена или отмена)
        if (!telegramParams.containsKey("id")) {
            logger.info("Получен запрос без параметра 'id' от Telegram (вероятно, проверка домена или отмена пользователем).");
            if (telegramParams.containsKey("error")) {
                logger.info("Пользователь отменил авторизацию Telegram: {}", telegramParams.get("error"));
                redirectAttributes.addFlashAttribute("info", "Вы отменили авторизацию через Telegram");
                // В случае отмены - редиректим
                return determineRedirectPath(session);
            } else {
                // Иначе считаем это проверкой домена
                logger.info("Обработка как проверка домена. Возвращаем HTTP 200 OK.");
                // <<< ИЗМЕНЕНИЕ: Возвращаем пустой ответ 200 OK, а не редирект >>>
                // Это позволит браузеру остаться на месте и принять второй callback
                return ResponseEntity.ok().build(); // HTTP 200 OK без тела
            }
        }

        // --- Если мы дошли сюда, значит параметры id, hash, auth_date ДОЛЖНЫ быть ---
        if (hash == null || authDate == null) {
            logger.warn("Отсутствуют обязательные параметры hash или auth_date от Telegram, хотя 'id' присутствует. Параметры: {}", telegramParams);
            redirectAttributes.addFlashAttribute("error", "Ошибка получения данных от Telegram. Попробуйте еще раз.");
            session.removeAttribute("telegramLinkingMode");
            session.removeAttribute("phoneForTelegramLinking");
            return determineRedirectPath(session); // Редирект при ошибке
        }

        // Создаем DTO с данными авторизации
        TelegramAuthDTO authData = buildTelegramAuthDTO(telegramParams);
        Long telegramId = authData.getId();

        logger.info(">>> Processing Telegram callback WITH data: id={}, hash={}, auth_date={}",
                authData.getId(), authData.getHash(), authData.getAuth_date());

        // Проверяем валидность данных от Telegram (хеш и время)
        boolean validRequest = telegramAuthService.validateTelegramResponse(authData);
        logger.info(">>> Telegram data validation result: {}", validRequest);

        if (!validRequest) {
            logger.warn("Невалидный ответ от Telegram для id={}. Хеш: {}, Дата: {}", telegramId, hash, authDate);
            redirectAttributes.addFlashAttribute("error", "Ошибка проверки данных от Telegram. Попробуйте еще раз.");
            session.removeAttribute("telegramLinkingMode");
            session.removeAttribute("phoneForTelegramLinking");
            return determineRedirectPath(session); // Редирект при ошибке валидации
        }

        // --- Логика определения режима: Привязка или Вход ---
        Boolean isLinkingMode = (Boolean) session.getAttribute("telegramLinkingMode");
        String phoneForLinking = (String) session.getAttribute("phoneForTelegramLinking");

        // Важно: Сразу удаляем атрибуты сессии после их прочтения
        session.removeAttribute("telegramLinkingMode");
        session.removeAttribute("phoneForTelegramLinking");
        logger.info(">>> Read and removed session attributes: isLinkingMode={}, phoneForLinking={}", isLinkingMode, phoneForLinking);

        // --- Вызываем соответствующий обработчик ---
        if (Boolean.TRUE.equals(isLinkingMode) && phoneForLinking != null) {
            logger.info(">>> Entering handleTelegramLinkingWithPhone for phone {}", phoneForLinking);
            // handleTelegramLinkingWithPhone возвращает строку редиректа
            return handleTelegramLinkingWithPhone(phoneForLinking, authData, redirectAttributes);
        } else {
            logger.info(">>> Entering handleTelegramLogin for Telegram ID {}", telegramId);
            // handleTelegramLogin возвращает строку редиректа
            return handleTelegramLogin(authData, session, redirectAttributes);
        }
    }

    // --- Остальные методы без изменений ---

    /**
     * Определяет путь для редиректа в зависимости от состояния аутентификации.
     * Используется для возврата пользователя в случае ошибки или отмены.
     */
    private String determineRedirectPath(HttpSession session) {
        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = currentAuth != null && currentAuth.isAuthenticated()
                && !"anonymousUser".equals(currentAuth.getPrincipal().toString());

        Boolean wasLinking = (Boolean) session.getAttribute("telegramLinkingMode");

        if (isAuthenticated || Boolean.TRUE.equals(wasLinking)) {
            logger.debug("Determined redirect path: /profile (isAuthenticated={}, wasLinking={})", isAuthenticated, wasLinking);
            return "redirect:/profile";
        } else {
            logger.debug("Determined redirect path: /login (isAuthenticated={}, wasLinking={})", isAuthenticated, wasLinking);
            return "redirect:/login";
        }
    }


    /**
     * Собирает TelegramAuthDTO из карты параметров.
     */
    private TelegramAuthDTO buildTelegramAuthDTO(Map<String, String> params) {
        TelegramAuthDTO authData = new TelegramAuthDTO();
        try {
            if (params.containsKey("id")) {
                authData.setId(Long.parseLong(params.get("id")));
            }
        } catch (NumberFormatException e) {
            logger.error("Ошибка парсинга Telegram ID: {}", params.get("id"));
        }
        authData.setFirst_name(params.get("first_name"));
        authData.setLast_name(params.get("last_name"));
        authData.setUsername(params.get("username"));
        authData.setPhoto_url(params.get("photo_url"));
        authData.setAuth_date(params.get("auth_date"));
        authData.setHash(params.get("hash"));
        return authData;
    }


    /**
     * Логирование всех деталей запроса для отладки
     */
    private void logAllRequestDetails(HttpServletRequest request) {
        logger.info("===== TELEGRAM CALLBACK REQUEST DETAILS =====");
        logger.info("Request URL: {}", request.getRequestURL() +
                (request.getQueryString() != null ? "?" + request.getQueryString() : ""));
        logger.info("Request Method: {}", request.getMethod());
        logger.info("Session ID: {}", request.getSession().getId());

        logger.info("--- Headers ---");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            logger.info("{}: {}", headerName, request.getHeader(headerName));
        }

        logger.info("--- Forwarded Headers ---");
        logger.info("X-Forwarded-Proto: {}", request.getHeader("X-Forwarded-Proto"));
        logger.info("X-Forwarded-Host: {}", request.getHeader("X-Forwarded-Host"));
        logger.info("X-Forwarded-For: {}", request.getHeader("X-Forwarded-For"));
        logger.info("X-Real-IP: {}", request.getHeader("X-Real-IP"));
        logger.info("Host: {}", request.getHeader("Host"));
        logger.info("Request Scheme: {}", request.getScheme());
        logger.info("Is Secure: {}", request.isSecure());


        logger.info("--- Parameters ---");
        Map<String, String[]> params = request.getParameterMap();
        if (params.isEmpty()) {
            logger.info("No parameters found in the request.");
        } else {
            for (Map.Entry<String, String[]> entry : params.entrySet()) {
                StringBuilder values = new StringBuilder();
                for (String value : entry.getValue()) {
                    values.append(value).append(", ");
                }
                logger.info("{}: {}", entry.getKey(), values.length() > 0 ?
                        values.substring(0, values.length() - 2) : "[empty]");
            }
        }
        logger.info("--- Session Attributes ---");
        HttpSession session = request.getSession(false);
        if (session != null) {
            Enumeration<String> attributeNames = session.getAttributeNames();
            if (!attributeNames.hasMoreElements()) {
                logger.info("No attributes found in the session.");
            } else {
                while (attributeNames.hasMoreElements()) {
                    String attrName = attributeNames.nextElement();
                    if ("telegramLinkingMode".equals(attrName) || "phoneForTelegramLinking".equals(attrName) || "qrCode".equals(attrName) || attrName.startsWith("org.springframework")) {
                        Object attrValue = session.getAttribute(attrName);
                        String valueToLog = (attrValue != null && attrName.equals("SPRING_SECURITY_CONTEXT")) ? "[present]" : String.valueOf(attrValue);
                        logger.info("{}: {}", attrName, valueToLog);
                    } else {
                        logger.info("{}: [hidden]", attrName);
                    }
                }
            }
        } else {
            logger.info("No active session found for logging attributes.");
        }

        logger.info("=======================================");
    }

    /**
     * Обрабатывает привязку Telegram-аккаунта к пользователю по номеру телефона из сессии.
     */
    private String handleTelegramLinkingWithPhone(
            String phone,
            TelegramAuthDTO authData,
            RedirectAttributes redirectAttributes) {

        logger.info(">>> Entering handleTelegramLinkingWithPhone logic for phone: {}", phone);

        Long telegramId = authData.getId();
        String telegramUsername = authData.getUsername();

        logger.info("Привязка Telegram id={}, username={} к телефону из сессии: {}",
                telegramId, telegramUsername, phone);

        Optional<User> existingUserWithTgId = userService.findByTelegramId(telegramId);
        if (existingUserWithTgId.isPresent() && !existingUserWithTgId.get().getPhone().equals(phone)) {
            logger.warn("Telegram id={} уже привязан к другому пользователю: {}",
                    telegramId, existingUserWithTgId.get().getPhone());
            redirectAttributes.addFlashAttribute("error",
                    "Этот аккаунт Telegram уже привязан к другому профилю.");
            return "redirect:/profile";
        }

        Optional<User> userOpt = userService.findByPhone(phone);
        if (userOpt.isEmpty()) {
            logger.error("Не найден пользователь с телефоном из сессии: {}", phone);
            redirectAttributes.addFlashAttribute("error", "Ошибка привязки: ваш профиль не найден. Попробуйте войти снова.");
            return "redirect:/login";
        }

        User user = userOpt.get();

        if (telegramId.equals(user.getTelegramId())) {
            logger.info("Telegram id={} уже был привязан к этому пользователю {}", telegramId, phone);
            if (telegramUsername != null && !telegramUsername.equals(user.getTelegram())) {
                logger.info("Обновление Telegram username для пользователя {} с {} на {}", phone, user.getTelegram(), telegramUsername);
                boolean updated = userService.updateTelegramInfo(user.getId(), telegramId, telegramUsername);
                if (updated) {
                    redirectAttributes.addFlashAttribute("info", "Имя пользователя Telegram обновлено.");
                } else {
                    redirectAttributes.addFlashAttribute("warning", "Не удалось обновить имя пользователя Telegram.");
                }
            } else {
                redirectAttributes.addFlashAttribute("info", "Этот аккаунт Telegram уже привязан к вашему профилю.");
            }
            return "redirect:/profile";
        }

        logger.info("Попытка обновления Telegram данных для пользователя ID {}: telegramId={}, telegramUsername={}",
                user.getId(), telegramId, telegramUsername);

        boolean updated = userService.updateTelegramInfo(user.getId(), telegramId, telegramUsername);

        if (updated) {
            logger.info("Успешно привязан Telegram id={} к пользователю: {}", telegramId, phone);
            redirectAttributes.addFlashAttribute("success", "Аккаунт Telegram успешно привязан к вашему профилю!");
            try {
                telegramAuthService.authenticateUser(user);
                logger.info("Данные пользователя {} в SecurityContext обновлены после привязки Telegram", phone);
            } catch (Exception e) {
                logger.warn("Не удалось обновить SecurityContext после привязки Telegram для {}: {}", phone, e.getMessage());
            }
        } else {
            logger.error("Не удалось привязать Telegram id={} к пользователю: {}", telegramId, phone);
            redirectAttributes.addFlashAttribute("error", "Не удалось сохранить привязку Telegram. Попробуйте еще раз или обратитесь к администратору.");
        }

        return "redirect:/profile";
    }


    /**
     * Обрабатывает вход пользователя через Telegram.
     */
    private String handleTelegramLogin(
            TelegramAuthDTO authData,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        logger.info(">>> Entering handleTelegramLogin logic for Telegram ID: {}", authData.getId());

        Long telegramId = authData.getId();
        logger.info("Попытка входа через Telegram id={}", telegramId);

        Optional<User> userOpt = userService.findByTelegramId(telegramId);

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            logger.info("Найден пользователь: {} для входа через Telegram id={}", user.getPhone(), telegramId);

            try {
                telegramAuthService.authenticateUser(user);
                logger.info("Пользователь {} успешно аутентифицирован через Telegram", user.getPhone());

                String qrCode = (String) session.getAttribute("qrCode");
                String redirectUrl = "/";

                if (qrCode != null && !qrCode.isEmpty()) {
                    logger.info("Обнаружен QR-код {} в сессии при входе через Telegram", qrCode);
                    session.removeAttribute("qrCode");
                    userService.handleReferralLink(user, qrCode);
                    userService.registerUser(user);
                    logger.info("QR-код {} добавлен/проверен для пользователя {}", qrCode, user.getPhone());

                    Optional<Instruction> instructionOpt = instructionService.findByQrCode(qrCode);
                    if (instructionOpt.isPresent()) {
                        redirectUrl = "/instruction/" + instructionOpt.get().getId();
                        logger.info("Перенаправление на инструкцию ID {} по QR-коду {}", instructionOpt.get().getId(), qrCode);
                    } else {
                        logger.warn("Инструкция для QR-кода {} не найдена, перенаправление на главную", qrCode);
                    }
                } else {
                    logger.info("QR-код в сессии не найден, перенаправление на главную страницу");
                }

                redirectAttributes.addFlashAttribute("success", "Вы успешно вошли через Telegram!");
                return "redirect:" + redirectUrl;

            } catch (UsernameNotFoundException e) {
                logger.error("Критическая ошибка: Пользователь найден по Telegram ID, но не найден при аутентификации: {}", e.getMessage());
                redirectAttributes.addFlashAttribute("error", "Ошибка входа: внутренняя ошибка сервера.");
                return "redirect:/login";
            } catch (Exception e) {
                logger.error("Непредвиденная ошибка при аутентификации пользователя {} через Telegram: {}", user.getPhone(), e.getMessage(), e);
                redirectAttributes.addFlashAttribute("error", "Произошла ошибка входа. Попробуйте позже.");
                return "redirect:/login";
            }

        } else {
            logger.warn("Пользователь с Telegram id={} не найден в системе", telegramId);
            redirectAttributes.addFlashAttribute("error", "Этот аккаунт Telegram не зарегистрирован в системе. Пожалуйста, войдите по номеру телефона и привяжите Telegram в профиле, или зарегистрируйтесь.");
            return "redirect:/login";
        }
    }
}
