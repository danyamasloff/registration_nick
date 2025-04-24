package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.enums.Role;
import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    // Конструктор для внедрения зависимостей
    public InstructionController(InstructionService instructionService, UserService userService) {
        this.instructionService = instructionService;
        this.userService = userService;
    }

    /**
     * Отображает главную страницу со списком инструкций.
     * Учитывает права доступа текущего пользователя.
     * @param model Модель для передачи данных в шаблон.
     * @return Имя шаблона главной страницы ("index").
     */
    @GetMapping("/")
    public String home(Model model) {
        // Получаем текущую аутентификацию из контекста Spring Security
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Определяем, аутентифицирован ли пользователь
        boolean isAuthenticated = authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal().toString());

        User currentUser = null;
        List<String> userReferralLinks = new ArrayList<>();
        boolean isAdmin = false;

        // Если пользователь аутентифицирован, получаем его данные
        if (isAuthenticated) {
            String principalName = authentication.getName(); // Обычно это номер телефона пользователя
            Optional<User> userOpt = userService.findByPhone(principalName); // Ищем по логину

            if (userOpt.isPresent()) {
                currentUser = userOpt.get();
                isAdmin = currentUser.getRole() == Role.ADMIN;
                String referralLinkString = currentUser.getReferralLink();
                // Преобразуем строку QR-кодов в список
                if (referralLinkString != null && !referralLinkString.isEmpty()) {
                    userReferralLinks = Arrays.asList(referralLinkString.split("\\s*,\\s*")); // Разбиваем по запятой с учетом пробелов
                }
                logger.info("Пользователь {} (Админ: {}) имеет доступ к QR: {}", principalName, isAdmin, userReferralLinks);
            } else {
                // Ситуация, когда аутентификация есть, но пользователя нет в БД - маловероятно, но возможно
                logger.warn("Аутентифицированный пользователь {} не найден в базе данных!", principalName);
                isAuthenticated = false; // Считаем его неаутентифицированным для отображения
            }
        }

        // Получаем все инструкции из базы
        List<Instruction> allInstructions = instructionService.getAllInstructions();

        // Обрабатываем каждую инструкцию для определения доступности и URL
        final List<String> finalUserReferralLinks = userReferralLinks; // Для использования в лямбде
        final boolean finalIsAdmin = isAdmin; // Для использования в лямбде
        final boolean finalIsAuthenticated = isAuthenticated; // Для использования в лямбде

        List<Instruction> processedInstructions = allInstructions.stream().map(instruction -> {
            // Проверяем доступ: админ или пользователь аутентифицирован и имеет нужный QR-код
            boolean isAvailable = finalIsAdmin || (finalIsAuthenticated && finalUserReferralLinks.contains(instruction.getQrCode()));
            instruction.setAvailable(isAvailable);

            // Устанавливаем URL: прямая ссылка если доступно, иначе заглушка
            instruction.setHref(isAvailable ? "/instruction/" + instruction.getId() : "javascript:void(0);");
            return instruction;
        }).collect(Collectors.toList()); // Собираем обработанный список

        // Добавляем данные в модель для шаблона
        model.addAttribute("instructions", processedInstructions);
        model.addAttribute("isAuthenticated", finalIsAuthenticated);
        model.addAttribute("isAdmin", finalIsAdmin); // Передаем флаг админа в шаблон

        return "index"; // Имя HTML шаблона главной страницы
    }

    /**
     * Отображает страницу конкретной инструкции по ее ID.
     * Проверяет права доступа текущего пользователя.
     * @param id ID запрашиваемой инструкции.
     * @param model Модель для передачи данных инструкции в шаблон.
     * @param redirectAttributes Атрибуты для передачи сообщений при редиректе.
     * @return Имя шаблона инструкции ("instruction") или редирект на главную/логин при отсутствии доступа/ошибке.
     */
    @GetMapping("/instruction/{id}")
    public String viewInstruction(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        // Получаем инструкцию по ID
        Optional<Instruction> instructionOpt = instructionService.getInstructionById(id);
        if (instructionOpt.isEmpty()) {
            logger.warn("Запрошена несуществующая инструкция с ID: {}", id);
            redirectAttributes.addFlashAttribute("error", "Инструкция не найдена.");
            return "redirect:/"; // Редирект на главную
        }

        Instruction instruction = instructionOpt.get();
        logger.info("Запрос на просмотр инструкции ID: {}, Title: '{}', QR: {}",
                instruction.getId(), instruction.getTitle(), instruction.getQrCode());

        // Проверяем аутентификацию пользователя
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal().toString())) {
            logger.warn("Анонимный доступ к инструкции ID: {}", id);
            redirectAttributes.addFlashAttribute("error", "Для доступа к инструкции необходимо войти.");
            return "redirect:/login"; // Редирект на страницу входа
        }

        // Получаем пользователя из контекста
        String principalName = authentication.getName();
        Optional<User> userOpt = userService.findByPhone(principalName);
        if (userOpt.isEmpty()) {
            logger.error("Аутентифицированный пользователь {} не найден в БД при доступе к инструкции ID: {}", principalName, id);
            redirectAttributes.addFlashAttribute("error", "Ошибка получения данных пользователя.");
            SecurityContextHolder.clearContext(); // Выходим из системы на всякий случай
            return "redirect:/login";
        }

        User user = userOpt.get();
        boolean isAdmin = user.getRole() == Role.ADMIN;

        // Проверяем доступ
        boolean hasAccess = isAdmin; // Админ имеет доступ всегда
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
            logger.info("Доступ к инструкции ID: {} предоставлен пользователю: {} (Админ: {})", id, principalName, isAdmin);
            model.addAttribute("instruction", instruction);
            return "instruction"; // Имя HTML шаблона для просмотра инструкции
        } else {
            // Если доступа нет - перенаправляем на главную
            logger.warn("Отказано в доступе к инструкции ID: {} для пользователя: {}", id, principalName);
            redirectAttributes.addFlashAttribute("error", "У вас нет доступа к этой инструкции.");
            return "redirect:/";
        }
    }
}