package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.enums.Role;
import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.TelegramAuthService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Контроллер для отображения инструкций и управления доступом к ним.
 */
@Controller
public class InstructionController {
    private static final Logger logger = LoggerFactory.getLogger(InstructionController.class);

    private final InstructionService instructionService;
    private final UserService userService;
    private final TelegramAuthService telegramAuthService;

    public InstructionController(InstructionService instructionService, UserService userService, TelegramAuthService telegramAuthService) {
        this.instructionService = instructionService;
        this.userService = userService;
        this.telegramAuthService = telegramAuthService;
    }

    /**
     * Обрабатывает запросы по реферальным ссылкам для существующих и новых пользователей
     */
    @GetMapping("/ref")
    public String handleReferral(
            @RequestParam(value = "code", required = false) String refCode,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (refCode == null || refCode.isEmpty()) {
            logger.warn("Получен запрос без кода реферала");
            redirectAttributes.addFlashAttribute("error", "Код реферала не указан");
            return "redirect:/";
        }

        logger.info("Получен запрос с кодом реферала: {}", refCode);

        // Проверяем существование инструкции по коду
        Optional<Instruction> instructionOpt = instructionService.findByQrCode(refCode);
        if (instructionOpt.isEmpty()) {
            logger.warn("Инструкция с кодом {} не найдена", refCode);
            redirectAttributes.addFlashAttribute("error", "Инструкция не найдена");
            return "redirect:/";
        }

        Instruction instruction = instructionOpt.get();
        logger.info("Найдена инструкция: {}", instruction.getTitle());

        // Проверяем, авторизован ли пользователь
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = authentication != null &&
                authentication.isAuthenticated() &&
                !"anonymousUser".equals(authentication.getPrincipal().toString());

        if (isAuthenticated) {
            // Если пользователь авторизован, добавляем ему доступ к инструкции
            String userPhone = authentication.getName();
            Optional<User> userOpt = userService.findByPhone(userPhone);

            if (userOpt.isPresent()) {
                User user = userOpt.get();

                // Добавляем QR-код к пользователю
                userService.handleReferralLink(user, refCode);
                userService.registerUser(user);

                logger.info("Пользователю {} добавлен доступ к инструкции ID: {}, код: {}",
                        userPhone, instruction.getId(), refCode);

                redirectAttributes.addFlashAttribute("success",
                        "Вам открыт доступ к инструкции: " + instruction.getTitle());

                // Перенаправляем на страницу инструкции
                return "redirect:/instruction/" + instruction.getId();
            } else {
                logger.error("Пользователь аутентифицирован, но не найден в БД: {}", userPhone);
                SecurityContextHolder.clearContext();
                redirectAttributes.addFlashAttribute("error", "Ошибка идентификации пользователя");
                return "redirect:/login";
            }
        } else {
            // Если пользователь не авторизован, сохраняем QR-код в сессии и перенаправляем на регистрацию
            session.setAttribute("qrCodeForRegistration", refCode);
            logger.info("Пользователь не авторизован. Код {} сохранен в сессии для регистрации", refCode);

            // Перенаправляем на регистрацию
            return "redirect:/register?ref=" + refCode;
        }
    }

    /**
     * Отображает главную страницу со списком инструкций.
     * Учитывает права доступа текущего пользователя.
     */
    @GetMapping("/")
    public String home(Model model,
                       @RequestParam(required = false) String telegram_auth,
                       @RequestParam(required = false) String telegram_id) {

        // Восстановление авторизации после редиректа с Telegram (если необходимо)
        if ("true".equals(telegram_auth) && telegram_id != null && !telegram_id.isEmpty()) {
            try {
                Long telegramIdLong = Long.parseLong(telegram_id);
                Optional<User> userOpt = userService.findByTelegramId(telegramIdLong);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    logger.info("Восстановление авторизации для пользователя {} с Telegram ID: {}",
                            user.getPhone(), telegramIdLong);
                    telegramAuthService.authenticateUser(user);
                    model.addAttribute("telegramAuthRefreshed", true);
                }
            } catch (Exception e) {
                logger.error("Ошибка при восстановлении авторизации: {}", e.getMessage());
            }
        }

        // Получаем текущую аутентификацию из контекста Spring Security
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = authentication != null && authentication.isAuthenticated() &&
                !"anonymousUser".equals(authentication.getPrincipal().toString());

        User currentUser = null;
        List<String> userReferralLinks = new ArrayList<>();
        boolean isAdmin = false;

        // Если пользователь аутентифицирован, получаем его данные
        if (isAuthenticated) {
            String principalName = authentication.getName();
            Optional<User> userOpt = userService.findByPhone(principalName);

            if (userOpt.isPresent()) {
                currentUser = userOpt.get();
                isAdmin = currentUser.getRole() == Role.ADMIN;
                String referralLinkString = currentUser.getReferralLink();

                if (referralLinkString != null && !referralLinkString.isEmpty()) {
                    userReferralLinks = Arrays.asList(referralLinkString.split("\\s*,\\s*"));
                }
                logger.info("Пользователь {} (Админ: {}) имеет доступ к кодам: {}",
                        principalName, isAdmin, userReferralLinks);
            } else {
                logger.warn("Аутентифицированный пользователь {} не найден в базе данных!", principalName);
                isAuthenticated = false;
            }
        }

        // Получаем все инструкции из базы
        List<Instruction> allInstructions = instructionService.getAllInstructions();

        // Обрабатываем каждую инструкцию для определения доступности
        final List<String> finalUserReferralLinks = userReferralLinks;
        final boolean finalIsAdmin = isAdmin;
        final boolean finalIsAuthenticated = isAuthenticated;

        List<Instruction> processedInstructions = allInstructions.stream().map(instruction -> {
            // Проверяем доступ: админ или пользователь аутентифицирован и имеет нужный QR-код
            boolean isAvailable = finalIsAdmin || (finalIsAuthenticated &&
                    finalUserReferralLinks.contains(instruction.getQrCode()));
            instruction.setAvailable(isAvailable);
            instruction.setHref(isAvailable ? "/instruction/" + instruction.getId() : "javascript:void(0);");
            return instruction;
        }).collect(Collectors.toList());

        // Добавляем данные в модель для шаблона
        model.addAttribute("instructions", processedInstructions);
        model.addAttribute("isAuthenticated", finalIsAuthenticated);
        model.addAttribute("isAdmin", finalIsAdmin);

        return "index";
    }

    /**
     * Отображает страницу конкретной инструкции по ее ID.
     * Проверяет права доступа текущего пользователя.
     */
    @GetMapping("/instruction/{id}")
    public String viewInstruction(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        // Получаем инструкцию по ID
        Optional<Instruction> instructionOpt = instructionService.getInstructionById(id);
        if (instructionOpt.isEmpty()) {
            logger.warn("Запрошена несуществующая инструкция с ID: {}", id);
            redirectAttributes.addFlashAttribute("error", "Инструкция не найдена.");
            return "redirect:/";
        }

        Instruction instruction = instructionOpt.get();
        logger.info("Запрос на просмотр инструкции ID: {}, Title: '{}', QR: {}",
                instruction.getId(), instruction.getTitle(), instruction.getQrCode());

        // Проверяем аутентификацию пользователя
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() ||
                "anonymousUser".equals(authentication.getPrincipal().toString())) {
            logger.warn("Анонимный доступ к инструкции ID: {}", id);
            redirectAttributes.addFlashAttribute("error", "Для доступа к инструкции необходимо войти.");
            return "redirect:/login";
        }

        // Получаем пользователя из контекста
        String principalName = authentication.getName();
        Optional<User> userOpt = userService.findByPhone(principalName);
        if (userOpt.isEmpty()) {
            logger.error("Аутентифицированный пользователь {} не найден в БД при доступе к инструкции ID: {}",
                    principalName, id);
            redirectAttributes.addFlashAttribute("error", "Ошибка получения данных пользователя.");
            SecurityContextHolder.clearContext();
            return "redirect:/login";
        }

        User user = userOpt.get();
        boolean isAdmin = user.getRole() == Role.ADMIN;

        // Проверяем доступ
        boolean hasAccess = isAdmin;
        if (!hasAccess) {
            // Проверяем наличие QR-кода у обычного пользователя
            String userLinks = user.getReferralLink();
            if (userLinks != null && !userLinks.isEmpty()) {
                List<String> userQrCodes = Arrays.asList(userLinks.split("\\s*,\\s*"));
                if (userQrCodes.contains(instruction.getQrCode())) {
                    hasAccess = true;
                }
            }
        }

        // Если доступ есть - отображаем инструкцию
        if (hasAccess) {
            logger.info("Доступ к инструкции ID: {} предоставлен пользователю: {} (Админ: {})",
                    id, principalName, isAdmin);
            model.addAttribute("instruction", instruction);
            return "instruction";
        } else {
            // Если доступа нет - перенаправляем на главную
            logger.warn("Отказано в доступе к инструкции ID: {} для пользователя: {}", id, principalName);
            redirectAttributes.addFlashAttribute("error", "У вас нет доступа к этой инструкции.");
            return "redirect:/";
        }
    }
}