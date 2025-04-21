package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.NovofonVerificationService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Controller
public class CompleteRegistrationController {

    private static final Logger logger = LoggerFactory.getLogger(CompleteRegistrationController.class);

    private final UserService userService;
    private final NovofonVerificationService novofonVerificationService;
    private final UserDetailsService userDetailsService;

    public CompleteRegistrationController(UserService userService,
                                          NovofonVerificationService novofonVerificationService,
                                          UserDetailsService userDetailsService) {
        this.userService = userService;
        this.novofonVerificationService = novofonVerificationService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Страница завершения регистрации после авторизации через Telegram
     */
    @GetMapping("/complete-registration")
    public String showCompleteRegistrationForm(HttpSession session, Model model) {
        // Получаем ID пользователя из сессии
        Long userId = (Long) session.getAttribute("telegramUserId");
        if (userId == null) {
            return "redirect:/login?error=session_expired";
        }

        // Получаем пользователя из базы данных
        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        User user = userOpt.get();
        model.addAttribute("user", user);
        return "complete_registration";
    }

    /**
     * Обработка отправки формы с телефоном и паролем
     */
    @PostMapping("/complete-registration/send-code")
    public String sendVerificationCode(@RequestParam("userId") Long userId,
                                       @RequestParam("phone") String phone,
                                       @RequestParam("password") String password,
                                       HttpSession session,
                                       Model model) {

        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        User user = userOpt.get();

        // Обновляем поля пользователя
        user.setPhone(phone);
        user.setPassword(password);

        // Сохраняем в сессии данные для проверки
        session.setAttribute("registrationUserId", userId);
        session.setAttribute("registrationPhone", phone);
        session.setAttribute("registrationPassword", password);

        // Отправляем код верификации
        try {
            novofonVerificationService.sendVerificationCode(phone);

            model.addAttribute("user", user);
            return "verify_phone";
        } catch (Exception e) {
            logger.error("Ошибка при отправке кода верификации", e);
            model.addAttribute("errorMessage", "Ошибка при отправке кода верификации: " + e.getMessage());
            model.addAttribute("user", user);
            return "complete_registration";
        }
    }

    /**
     * Проверка кода верификации и завершение регистрации
     */
    @PostMapping("/complete-registration/verify")
    public String verifyAndCompleteRegistration(@RequestParam("code") String code,
                                                HttpSession session,
                                                Model model) {

        Long userId = (Long) session.getAttribute("registrationUserId");
        String phone = (String) session.getAttribute("registrationPhone");
        String password = (String) session.getAttribute("registrationPassword");

        if (userId == null || phone == null || password == null) {
            return "redirect:/login?error=session_expired";
        }

        // Проверяем код верификации
        if (!novofonVerificationService.verifyCode(phone, code)) {
            Optional<User> userOpt = userService.findById(userId);
            if (userOpt.isPresent()) {
                model.addAttribute("user", userOpt.get());
                model.addAttribute("errorMessage", "Неверный код верификации");
                return "verify_phone";
            } else {
                return "redirect:/login?error=user_not_found";
            }
        }

        // Очищаем код верификации
        novofonVerificationService.clearCode(phone);

        // Ищем существующего пользователя с таким телефоном
        Optional<User> existingUserByPhoneOpt = userService.findByPhone(phone);

        if (existingUserByPhoneOpt.isPresent() && !existingUserByPhoneOpt.get().getId().equals(userId)) {
            // Существует другой пользователь с таким телефоном - связываем аккаунты
            User telegramUser = userService.findById(userId).orElse(null);
            User existingUser = existingUserByPhoneOpt.get();

            if (telegramUser != null && existingUser != null) {
                // Связываем существующий аккаунт с Telegram
                existingUser.setTelegramId(telegramUser.getTelegramId());
                existingUser.setTelegram(telegramUser.getTelegram());

                // Обновляем пароль, если он был изменен
                existingUser.setPassword(password);

                // Обновляем флаг подтверждения телефона
                existingUser.setPhoneVerified(true);

                // Если у Telegram-пользователя была реферальная ссылка, добавляем её к существующему
                if (telegramUser.getReferralLink() != null && !telegramUser.getReferralLink().isEmpty()) {
                    if (existingUser.getReferralLink() == null || existingUser.getReferralLink().isEmpty()) {
                        existingUser.setReferralLink(telegramUser.getReferralLink());
                    } else if (!existingUser.getReferralLink().contains(telegramUser.getReferralLink())) {
                        existingUser.setReferralLink(existingUser.getReferralLink() + "," + telegramUser.getReferralLink());
                    }
                }

                // Сохраняем обновленного существующего пользователя
                userService.registerUser(existingUser);

                // Удаляем временного пользователя, созданного через Telegram
                userService.deleteUser(telegramUser.getId());

                // Аутентифицируем пользователя
                authenticateUser(existingUser.getPhone());

                // Очищаем данные сессии
                clearSessionData(session);

                return "redirect:/";
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

                // Сохраняем пользователя
                userService.registerUser(user);

                // Аутентифицируем пользователя
                authenticateUser(user.getPhone());

                // Очищаем данные сессии
                clearSessionData(session);

                return "redirect:/";
            }
        }

        return "redirect:/login?error=registration_failed";
    }

    /**
     * Аутентифицирует пользователя с указанным телефоном
     */
    private void authenticateUser(String phone) {
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(phone);
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            logger.info("Пользователь {} успешно аутентифицирован", phone);
        } catch (Exception e) {
            logger.error("Ошибка при аутентификации пользователя", e);
        }
    }

    /**
     * Очищает данные сессии после завершения регистрации
     */
    private void clearSessionData(HttpSession session) {
        session.removeAttribute("telegramUserId");
        session.removeAttribute("registrationUserId");
        session.removeAttribute("registrationPhone");
        session.removeAttribute("registrationPassword");
    }
}