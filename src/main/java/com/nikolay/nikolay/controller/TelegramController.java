package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.UserService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Controller
@RequestMapping("/telegram")
public class TelegramController {

    private static final Logger logger = LoggerFactory.getLogger(TelegramController.class);

    private final UserService userService;
    private final UserDetailsService userDetailsService;

    public TelegramController(UserService userService,
                              UserDetailsService userDetailsService) {
        this.userService = userService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Страница установки пароля для пользователей, зарегистрированных через Telegram
     */
    @GetMapping("/set-password")
    public String showSetPasswordForm(@RequestParam("telegram") String telegram, Model model) {
        Optional<User> userOpt = userService.findByTelegram(telegram);
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        model.addAttribute("user", userOpt.get());
        return "set_password";
    }

    /**
     * Обработка формы установки пароля
     */
    @PostMapping("/set-password")
    public String setPassword(User user) {
        Optional<User> userOpt = userService.findByTelegram(user.getTelegram());
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        User existingUser = userOpt.get();
        existingUser.setPassword(user.getPassword());

        // Обновляем телефон, если он был изменен
        if (user.getPhone() != null && !user.getPhone().isEmpty()) {
            existingUser.setPhone(user.getPhone());
        }

        userService.registerUser(existingUser);

        // Аутентифицируем пользователя
        UserDetails userDetails = userDetailsService.loadUserByUsername(existingUser.getPhone());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        logger.info("Пользователь {} успешно установил пароль и был аутентифицирован", existingUser.getTelegram());
        return "redirect:/";
    }
}