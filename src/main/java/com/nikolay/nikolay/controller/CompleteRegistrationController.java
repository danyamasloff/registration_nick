/*
package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.enums.Role;
import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.NovofonVerificationService;
import com.nikolay.nikolay.service.TelegramAuthService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;


@Controller
public class CompleteRegistrationController {

    private static final Logger logger = LoggerFactory.getLogger(CompleteRegistrationController.class);

    private final UserService userService;
    private final NovofonVerificationService novofonVerificationService;
    private final TelegramAuthService telegramAuthService;
    private final InstructionService instructionService; // Добавляем сервис инструкций

    public CompleteRegistrationController(
            UserService userService,
            NovofonVerificationService novofonVerificationService,
            TelegramAuthService telegramAuthService,
            InstructionService instructionService) {
        this.userService = userService;
        this.novofonVerificationService = novofonVerificationService;
        this.telegramAuthService = telegramAuthService;
        this.instructionService = instructionService;
    }


    @GetMapping("/complete-registration")
    public String showCompleteRegistrationForm(HttpSession session, Model model) {
        // Получаем данные Telegram из сессии
        Long telegramId = (Long) session.getAttribute("telegramId");
        String telegramUsername = (String) session.getAttribute("telegramUsername");
        String telegramFirstName = (String) session.getAttribute("telegramFirstName");
        String referralLink = (String) session.getAttribute("telegramReferralLink");

        // Проверяем, сохранен ли оригинальный QR-код для последующего перенаправления
        String originalReferralLink = (String) session.getAttribute("originalReferralLink");
        if (originalReferralLink != null && !originalReferralLink.isEmpty()) {
            logger.info("Найден оригинальный QR-код для перенаправления: {}", originalReferralLink);
        }

        if (telegramId == null) {
            return "redirect:/login?error=session_expired";
        }

        // Находим или создаем пользователя
        User user;
        Optional<User> userOpt = userService.findByTelegramId(telegramId);

        if (userOpt.isPresent()) {
            // Пользователь уже существует
            user = userOpt.get();
            logger.info("Найден существующий пользователь с Telegram ID: {}", telegramId);

            // Обновляем реферальную ссылку при необходимости
            if (referralLink != null && !referralLink.isEmpty()) {
                userService.handleReferralLink(user, referralLink);
                userService.registerUser(user);
            }
        } else {
            // Создаем нового пользователя на основе данных Telegram
            user = new User();
            user.setTelegramId(telegramId);
            user.setTelegram(telegramUsername);

            // Генерируем временный телефон и пароль
            user.setPhone(generateTemporaryPhone(telegramId));
            user.setPassword(generateTemporaryPassword());

            // Устанавливаем роль и реферальную ссылку
            user.setRole(Role.USER);
            user.setReferralLink(referralLink != null ? referralLink : "");

            // Сохраняем пользователя
            user = userService.registerUser(user);
            logger.info("Создан новый пользователь с Telegram ID: {}", telegramId);
        }

        // Сохраняем ID пользователя в сессии
        session.setAttribute("telegramUserId", user.getId());

        // Добавляем данные в модель
        model.addAttribute("user", user);
        model.addAttribute("telegramUsername", telegramUsername);
        model.addAttribute("telegramFirstName", telegramFirstName);

        return "complete_registration";
    }



    @PostMapping("/complete-registration/send-code")
    public String sendVerificationCode(
            @RequestParam("userId") Long userId,
            @RequestParam("phone") String phone,
            @RequestParam("password") String password,
            HttpSession session,
            Model model) {

        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        User user = userOpt.get();

        // Проверяем, что пароль соответствует требованиям
        if (password.length() < 6) {
            model.addAttribute("errorMessage", "Пароль должен содержать минимум 6 символов");
            model.addAttribute("user", user);
            return "complete_registration";
        }

        // Обновляем поля пользователя (временно, до подтверждения)
        user.setPhone(phone);
        user.setPassword(password);

        // Сохраняем в сессии данные для проверки
        session.setAttribute("registrationUserId", userId);
        session.setAttribute("registrationPhone", phone);
        session.setAttribute("registrationPassword", password);

        // Отправляем код верификации
        try {
            novofonVerificationService.sendVerificationCode(phone);
            logger.info("Отправлен код верификации на номер: {}", phone);

            // Добавляем данные в модель для страницы верификации
            model.addAttribute("user", user);
            model.addAttribute("phone", phone);
            return "verify_phone";
        } catch (Exception e) {
            logger.error("Ошибка при отправке кода верификации", e);
            model.addAttribute("errorMessage", "Ошибка при отправке кода верификации: " + e.getMessage());
            model.addAttribute("user", user);
            return "complete_registration";
        }
    }



    @PostMapping("/complete-registration/verify")
    public String verifyAndCompleteRegistration(
            @RequestParam("code") String code,
            HttpSession session,
            Model model) {

        Long userId = (Long) session.getAttribute("registrationUserId");
        String phone = (String) session.getAttribute("registrationPhone");
        String password = (String) session.getAttribute("registrationPassword");
        String originalReferralLink = (String) session.getAttribute("originalReferralLink");

        if (userId == null || phone == null || password == null) {
            return "redirect:/login?error=session_expired";
        }

        // Проверяем код верификации
        if (!novofonVerificationService.verifyCode(phone, code)) {
            Optional<User> userOpt = userService.findById(userId);
            if (userOpt.isPresent()) {
                model.addAttribute("user", userOpt.get());
                model.addAttribute("phone", phone);
                model.addAttribute("errorMessage", "Неверный код верификации");
                return "verify_phone";
            } else {
                return "redirect:/login?error=user_not_found";
            }
        }

        // Код верификации верный - очищаем его
        novofonVerificationService.clearCode(phone);
        logger.info("Успешно подтвержден код для номера: {}", phone);

        // Ищем существующего пользователя с таким телефоном
        Optional<User> existingUserByPhoneOpt = userService.findByPhone(phone);

        // Переменная для хранения пользователя, который будет аутентифицирован
        User authenticatedUser = null;

        if (existingUserByPhoneOpt.isPresent() && !existingUserByPhoneOpt.get().getId().equals(userId)) {
            // Существует другой пользователь с таким телефоном - связываем аккаунты
            User telegramUser = userService.findById(userId).orElse(null);
            User existingUser = existingUserByPhoneOpt.get();

            if (telegramUser != null && existingUser != null) {
                // Связываем существующий аккаунт с Telegram
                logger.info("Связываем существующий аккаунт телефона {} с Telegram ID: {}",
                        existingUser.getPhone(), telegramUser.getTelegramId());

                existingUser.setTelegramId(telegramUser.getTelegramId());
                existingUser.setTelegram(telegramUser.getTelegram());

                // Обновляем пароль, если он был изменен
                existingUser.setPassword(password);

                // Обновляем флаг подтверждения телефона
                existingUser.setPhoneVerified(true);

                // Если у Telegram-пользователя была реферальная ссылка, добавляем её к существующему
                if (telegramUser.getReferralLink() != null && !telegramUser.getReferralLink().isEmpty()) {
                    userService.handleReferralLink(existingUser, telegramUser.getReferralLink());
                }

                // Добавляем originialReferralLink, если он есть
                if (originalReferralLink != null && !originalReferralLink.isEmpty()) {
                    userService.handleReferralLink(existingUser, originalReferralLink);
                }

                // Сохраняем обновленного существующего пользователя
                existingUser = userService.registerUser(existingUser);
                authenticatedUser = existingUser;

                // Удаляем временного пользователя, созданного через Telegram
                userService.deleteUser(telegramUser.getId());
                logger.info("Удален временный пользователь с Telegram ID: {}", telegramUser.getTelegramId());
            }
        } else {
            // Нет существующего пользователя с таким телефоном - обновляем текущего
            Optional<User> userOpt = userService.findById(userId);

            if (userOpt.isPresent()) {
                User user = userOpt.get();

                // Обновляем данные пользователя
                user.setPhone(phone);
                user.setPassword(password);
                user.setPhoneVerified(true);

                // Добавляем originalReferralLink, если он есть
                if (originalReferralLink != null && !originalReferralLink.isEmpty()) {
                    userService.handleReferralLink(user, originalReferralLink);
                }

                // Сохраняем пользователя
                logger.info("Обновляем данные пользователя с Telegram ID: {}, устанавливаем телефон: {}",
                        user.getTelegramId(), phone);
                user = userService.registerUser(user);
                authenticatedUser = user;
            }
        }

        // Если пользователь успешно аутентифицирован
        if (authenticatedUser != null) {
            // Аутентифицируем пользователя
            telegramAuthService.authenticateUser(authenticatedUser.getPhone());

            // Очищаем данные сессии
            clearSessionData(session);

            // Проверяем, есть ли originalReferralLink для перенаправления на инструкцию
            if (originalReferralLink != null && !originalReferralLink.isEmpty()) {
                // Ищем инструкцию по QR-коду
                Optional<Instruction> instructionOpt = instructionService.findByQrCode(originalReferralLink);
                if (instructionOpt.isPresent()) {
                    logger.info("Перенаправляем на инструкцию с ID: {} после завершения регистрации",
                            instructionOpt.get().getId());
                    return "redirect:/instruction/" + instructionOpt.get().getId();
                }
            }

            return "redirect:/"; // Если QR-код не найден, перенаправляем на главную
        }

        return "redirect:/login?error=registration_failed";
    }


    private void clearSessionData(HttpSession session) {
        session.removeAttribute("telegramId");
        session.removeAttribute("telegramUsername");
        session.removeAttribute("telegramFirstName");
        session.removeAttribute("telegramLastName");
        session.removeAttribute("telegramReferralLink");
        session.removeAttribute("telegramUserId");
        session.removeAttribute("registrationUserId");
        session.removeAttribute("registrationPhone");
        session.removeAttribute("registrationPassword");
        session.removeAttribute("originalReferralLink"); // Очищаем оригинальный QR-код
    }



    private String generateTemporaryPhone(Long telegramId) {
        // Используем telegramId и текущее время для создания уникального номера
        String uniqueNumber = String.valueOf(Math.abs((telegramId + System.currentTimeMillis()) % 10000000000L));
        // Дополняем нулями до 10 цифр
        uniqueNumber = String.format("%010d", Long.parseLong(uniqueNumber));
        return "+7" + uniqueNumber;
    }


    private String generateTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[12]; // 12 байт дадут 16 символов в base64
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
*/
