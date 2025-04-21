package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.User;
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
    private final PhoneVerificationService verificationService;
    private final UserService userService;

    private final PasswordEncoder passwordEncoder;

    public RegistrationController(PhoneVerificationService verificationService, UserService userService, PasswordEncoder passwordEncoder) {
        this.verificationService = verificationService;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/register")
    public String showRegistrationForm(@RequestParam(value = "ref", required = false) String referralLink, Model model) {
        User user = new User();
        // Передаем значение referralLink в модель
        user.setReferralLink(referralLink);
        model.addAttribute("user", user);
        return "register"; // возвращаем имя шаблона
    }


    @PostMapping("/register/send-code")
    public String sendVerificationCode(@ModelAttribute("user") User user, Model model) {
        verificationService.sendVerificationCode(user.getPhone());
        model.addAttribute("user", user);
        System.out.println("getReferralLink  -------- sendVerificationCode" + user.getReferralLink());
        return "verify_code"; // переходим на страницу ввода кода
    }

    @PostMapping("/register/verify")
    public String verifyAndRegister(@ModelAttribute("user") User user,
                                    @RequestParam String code,
                                    Model model) {
        if (!verificationService.verifyCode(user.getPhone(), code)) {
            model.addAttribute("errorMessage", "Неверный код");
            return "verify_code";
        }
        System.out.println("getReferralLink  --------register/verify " + user.getReferralLink());
        try {
            Optional<User> userDB = userService.findByPhone(user.getPhone());
            if (userDB.isPresent()) {
                User existingUser = userDB.get();
                verificationService.clearCode(existingUser.getPhone());

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

                // Перезашифровываем пароль, если он был изменен
                if (user.getPassword() != null && !user.getPassword().startsWith("$2a$")) { // Проверяем, что пароль не зашифрован
                    existingUser.setPassword(passwordEncoder.encode(user.getPassword()));
                }

                userService.registerUser(existingUser); // обновляем пользователя
            } else {
                verificationService.clearCode(user.getPhone());
                userService.registerUser(user); // новый пользователь
            }

            return "redirect:/login";

        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "register";
        }
    }

}