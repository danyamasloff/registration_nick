/*
package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.User;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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


    @GetMapping("/login-success")
    public String loginSuccess(HttpSession session) {
        Long userId = (Long) session.getAttribute("authenticatedUserId");
        if (userId == null) {
            return "redirect:/login?error=session_expired";
        }

        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        User user = userOpt.get();

        // Аутентифицируем пользователя
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getPhone());
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            logger.info("Пользователь с телефоном {} успешно аутентифицирован через Telegram", user.getPhone());
        } catch (Exception e) {
            logger.error("Ошибка при аутентификации пользователя", e);
            return "redirect:/login?error=authentication_failed";
        }

        // Очищаем данные сессии
        session.removeAttribute("authenticatedUserId");

        return "redirect:/";
    }
}
*/
