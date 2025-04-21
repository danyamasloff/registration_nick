package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.NovofonVerificationService;
import com.nikolay.nikolay.service.PhoneVerificationService;
import com.nikolay.nikolay.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Controller
public class RegistrationController {
    private final PhoneVerificationService phoneVerificationService;
    private final NovofonVerificationService novofonVerificationService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public RegistrationController(PhoneVerificationService phoneVerificationService,
                                  NovofonVerificationService novofonVerificationService,
                                  UserService userService,
                                  PasswordEncoder passwordEncoder) {
        this.phoneVerificationService = phoneVerificationService;
        this.novofonVerificationService = novofonVerificationService;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/register")
    public String showRegistrationForm(@RequestParam(value = "ref", required = false) String referralLink, Model model) {
        User user = new User();
        user.setReferralLink(referralLink);
        model.addAttribute("user", user);
        return "register";
    }

    @PostMapping("/register/send-code")
    public String sendVerificationCode(@ModelAttribute("user") User user,
                                       @RequestParam(value = "verificationType", required = false, defaultValue = "sms") String verificationType,
                                       Model model) {
        // Нормализуем номер телефона для хранения в сессии и для отправки кода
        String normalizedPhone = normalizePhoneNumber(user.getPhone());
        user.setPhone(normalizedPhone);

        // Выбираем способ верификации
        if ("call".equals(verificationType)) {
            // Верификация через звонок
            novofonVerificationService.sendVerificationCode(normalizedPhone);
        } else {
            // Верификация через SMS (по умолчанию)
            phoneVerificationService.sendVerificationCode(normalizedPhone);
        }

        model.addAttribute("user", user);
        model.addAttribute("verificationType", verificationType);
        return "verify_code";
    }

    @PostMapping("/register/verify")
    public String verifyAndRegister(@ModelAttribute("user") User user,
                                    @RequestParam String code,
                                    @RequestParam(value = "verificationType", required = false, defaultValue = "sms") String verificationType,
                                    Model model) {
        // Нормализуем номер телефона
        String normalizedPhone = normalizePhoneNumber(user.getPhone());
        user.setPhone(normalizedPhone);

        boolean isCodeValid;

        // Проверяем код в зависимости от метода верификации
        if ("call".equals(verificationType)) {
            isCodeValid = novofonVerificationService.verifyCode(normalizedPhone, code);
        } else {
            isCodeValid = phoneVerificationService.verifyCode(normalizedPhone, code);
        }

        if (!isCodeValid) {
            model.addAttribute("errorMessage", "Неверный код");
            model.addAttribute("verificationType", verificationType);
            return "verify_code";
        }

        try {
            // Ищем пользователя по нормализованному номеру
            Optional<User> userDB = userService.findByPhone(normalizedPhone);
            if (userDB.isPresent()) {
                User existingUser = userDB.get();

                // Очищаем код верификации в зависимости от метода
                if ("call".equals(verificationType)) {
                    novofonVerificationService.clearCode(normalizedPhone);
                } else {
                    phoneVerificationService.clearCode(normalizedPhone);
                }

                // Добавляем новую referralLink, если она передана
                String newLink = user.getReferralLink();
                if (newLink != null && !newLink.isBlank()) {
                    String existingLinks = existingUser.getReferralLink();
                    if (existingLinks == null || existingLinks.isBlank()) {
                        existingUser.setReferralLink(newLink);
                    } else if (!existingLinks.contains(newLink)) {
                        existingUser.setReferralLink(existingLinks + "," + newLink);
                    }
                }

                // Перехешируем пароль, если он был изменен
                if (user.getPassword() != null && !user.getPassword().startsWith("$2a$")) {
                    existingUser.setPassword(passwordEncoder.encode(user.getPassword()));
                }

                userService.registerUser(existingUser);
            } else {
                // Очищаем код верификации в зависимости от метода
                if ("call".equals(verificationType)) {
                    novofonVerificationService.clearCode(normalizedPhone);
                } else {
                    phoneVerificationService.clearCode(normalizedPhone);
                }

                // Устанавливаем нормализованный номер телефона
                user.setPhone(normalizedPhone);

                // Хешируем пароль если он не хеширован
                if (user.getPassword() != null && !user.getPassword().startsWith("$2a$")) {
                    user.setPassword(passwordEncoder.encode(user.getPassword()));
                }

                userService.registerUser(user);
            }

            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "register";
        }
    }

    /**
     * Нормализует номер телефона в соответствии с требованиями модели
     * @param phone исходный номер телефона
     * @return нормализованный номер в формате +XXXXXXXX
     */
    private String normalizePhoneNumber(String phone) {
        if (phone == null) {
            return null;
        }

        // Удаляем все нецифровые символы
        String digits = phone.replaceAll("[^\\d]", "");

        // Если номер начинается с 8 для России, заменяем на 7
        if (digits.startsWith("8") && digits.length() == 11) {
            digits = "7" + digits.substring(1);
        }

        // Проверяем длину и добавляем код страны 7 для номеров из 10 цифр
        if (digits.length() == 10) {
            digits = "7" + digits;
        }

        // Добавляем + в начало, чтобы соответствовать паттерну валидации
        return "+" + digits;
    }
}