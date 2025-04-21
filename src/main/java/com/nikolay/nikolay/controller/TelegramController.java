package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.dto.TelegramAuthDTO;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.TelegramAuthService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

import java.io.IOException;
import java.util.Optional;

@Controller
@RequestMapping("/telegram")
public class TelegramController {

    private static final Logger logger = LoggerFactory.getLogger(TelegramController.class);

    private final TelegramAuthService telegramAuthService;
    private final UserService userService;
    private final UserDetailsService userDetailsService;

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.auth.redirect-uri}")
    private String redirectUri;

    public TelegramController(TelegramAuthService telegramAuthService,
                              UserService userService,
                              UserDetailsService userDetailsService) {
        this.telegramAuthService = telegramAuthService;
        this.userService = userService;
        this.userDetailsService = userDetailsService;
    }

    @GetMapping("/login")
    public String showTelegramLoginPage(Model model) {
        model.addAttribute("botUsername", botUsername);
        model.addAttribute("redirectUri", redirectUri);
        return "telegram_login";
    }

    @GetMapping("/callback")
    public String handleTelegramCallback(TelegramAuthDTO authData,
                                         @RequestParam(value = "ref", required = false) String referralLink,
                                         HttpServletResponse response) throws IOException {

        logger.info("Received Telegram callback for user @{}", authData.getUsername());

        // Add referral link if provided
        if (referralLink != null && !referralLink.isEmpty()) {
            authData.setReferralLink(referralLink);
        }

        // Validate Telegram data
        if (!telegramAuthService.validateTelegramResponse(authData)) {
            logger.warn("Invalid Telegram authentication data received");
            return "redirect:/login?error=telegram_invalid";
        }

        // Find or create user
        Optional<User> userOpt = userService.findByTelegram(authData.getUsername());
        User user;

        if (userOpt.isPresent()) {
            // Existing user - update information if needed
            user = userOpt.get();
            logger.info("Found existing user with Telegram username: @{}", authData.getUsername());
        } else {
            // New user - register with Telegram
            user = telegramAuthService.registerUserWithTelegram(authData);
            logger.info("Created new user from Telegram data: @{}", authData.getUsername());
        }

        // If user needs to set password, redirect to set password page
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            return "redirect:/telegram/set-password?telegram=" + user.getTelegram();
        }

        // Authenticate the user
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getPhone());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        return "redirect:/";
    }

    @GetMapping("/set-password")
    public String showSetPasswordForm(@RequestParam("telegram") String telegram, Model model) {
        Optional<User> userOpt = userService.findByTelegram(telegram);
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        model.addAttribute("user", userOpt.get());
        return "set_password";
    }

    @PostMapping("/set-password")
    public String setPassword(User user) {
        Optional<User> userOpt = userService.findByTelegram(user.getTelegram());
        if (userOpt.isEmpty()) {
            return "redirect:/login?error=user_not_found";
        }

        User existingUser = userOpt.get();
        existingUser.setPassword(user.getPassword());
        userService.registerUser(existingUser);

        // Аутентифицируем пользователя
        UserDetails userDetails = userDetailsService.loadUserByUsername(existingUser.getPhone());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        return "redirect:/";
    }