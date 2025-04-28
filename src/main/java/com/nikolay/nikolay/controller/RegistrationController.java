package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.enums.Role;
import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.NovofonVerificationService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

/**
 * Контроллер для обработки регистрации по номеру телефона и верификации.
 */
@Controller
public class RegistrationController {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationController.class);

    private final NovofonVerificationService novofonVerificationService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final InstructionService instructionService;
    private final UserDetailsService userDetailsService;

    public RegistrationController(
            NovofonVerificationService novofonVerificationService,
            UserService userService,
            PasswordEncoder passwordEncoder,
            InstructionService instructionService,
            UserDetailsService userDetailsService) {
        this.novofonVerificationService = novofonVerificationService;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.instructionService = instructionService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Отображает форму регистрации.
     */
    @GetMapping("/register")
    public String showRegistrationForm(
            @RequestParam(value = "ref", required = false) String referralLink,
            Model model,
            HttpSession session) {

        logger.info("Запрос на страницу регистрации с ref={}", referralLink);

        if (referralLink != null && !referralLink.isEmpty()) {
            session.setAttribute("qrCodeForRegistration", referralLink);
            logger.info("QR-код {} сохранен в сессии для регистрации.", referralLink);

            // Получаем название инструкции для отображения
            Optional<Instruction> instructionOpt = instructionService.findByQrCode(referralLink);
            instructionOpt.ifPresent(instruction -> model.addAttribute("instructionTitle", instruction.getTitle()));
        }

        if (!model.containsAttribute("user")) {
            model.addAttribute("user", new User());
        }
        model.addAttribute("qrCode", session.getAttribute("qrCodeForRegistration"));

        return "register";
    }

    /**
     * Принимает данные с формы регистрации, отправляет код верификации на телефон.
     * Обрабатывает как новых, так и существующих пользователей.
     */
    @PostMapping("/register/send-code")
    public String sendVerificationCode(
            @Valid @ModelAttribute("user") User user,
            BindingResult bindingResult,
            Model model,
            HttpSession session) {

        // Нормализуем телефон
        String normalizedPhone = userService.normalizePhoneNumber(user.getPhone());
        user.setPhone(normalizedPhone);

        // Проверяем, что нормализованный номер не null
        if (normalizedPhone == null) {
            bindingResult.addError(new FieldError("user", "phone", "Некорректный формат номера телефона."));
            populateModelForErrors(model, user, session);
            return "register";
        }

        // Проверяем, существует ли пользователь с таким телефоном
        Optional<User> existingUserOpt = userService.findByPhone(normalizedPhone);
        boolean isExistingUser = existingUserOpt.isPresent();

        if (isExistingUser) {
            logger.info("Обнаружен существующий пользователь с телефоном {}. Режим добавления инструкции.", normalizedPhone);
            session.setAttribute("existingUserMode", true);
            session.setAttribute("existingUserId", existingUserOpt.get().getId());
        } else {
            // Проверяем ошибки валидации для нового пользователя
            if (bindingResult.hasErrors()) {
                logger.warn("Ошибки валидации при отправке кода: {}", bindingResult.getAllErrors());
                populateModelForErrors(model, user, session);
                return "register";
            }
            session.setAttribute("existingUserMode", false);
        }

        // Сохраняем данные в сессии
        session.setAttribute("registrationPhone", normalizedPhone);
        session.setAttribute("registrationPassword", user.getPassword());

        // Отправляем код верификации через Novofon
        try {
            novofonVerificationService.sendVerificationCode(normalizedPhone);
            logger.info("Отправлен код верификации на номер: {}", normalizedPhone);

            model.addAttribute("phone", normalizedPhone);
            model.addAttribute("user", user);
            model.addAttribute("existingUser", isExistingUser);

            return "verify_code";
        } catch (Exception e) {
            logger.error("Ошибка при отправке кода верификации: {}", e.getMessage());
            model.addAttribute("errorMessage", "Не удалось отправить код верификации. Попробуйте позже.");
            populateModelForErrors(model, user, session);
            return "register";
        }
    }

    /**
     * Проверяет код верификации и завершает регистрацию нового пользователя
     * или обновляет доступы существующего.
     */
    @PostMapping("/register/verify")
    public String verifyAndRegister(
            @RequestParam String code,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes) {

        String phone = (String) session.getAttribute("registrationPhone");
        String rawPassword = (String) session.getAttribute("registrationPassword");
        String qrCode = (String) session.getAttribute("qrCodeForRegistration");
        Boolean isExistingUser = (Boolean) session.getAttribute("existingUserMode");
        Long existingUserId = (Long) session.getAttribute("existingUserId");

        if (phone == null) {
            logger.error("Телефон не найден в сессии.");
            redirectAttributes.addFlashAttribute("error", "Сессия истекла, попробуйте снова.");
            return "redirect:/register";
        }

        // Проверяем код верификации
        boolean isCodeValid = novofonVerificationService.verifyCode(phone, code);
        if (!isCodeValid) {
            logger.warn("Введен неверный код верификации для телефона: {}", phone);
            model.addAttribute("errorMessage", "Неверный код подтверждения.");
            model.addAttribute("phone", phone);
            model.addAttribute("existingUser", isExistingUser != null && isExistingUser);
            User tempUser = new User();
            tempUser.setPhone(phone);
            model.addAttribute("user", tempUser);
            return "verify_code";
        }

        // Код верификации верный, очищаем его
        novofonVerificationService.clearCode(phone);
        logger.info("Код для {} успешно верифицирован и очищен.", phone);

        try {
            // Обрабатываем в зависимости от режима (существующий/новый пользователь)
            if (Boolean.TRUE.equals(isExistingUser)) {
                // Обработка существующего пользователя
                Optional<User> existingUserOpt;

                if (existingUserId != null) {
                    existingUserOpt = userService.findById(existingUserId);
                } else {
                    existingUserOpt = userService.findByPhone(phone);
                }

                if (existingUserOpt.isEmpty()) {
                    logger.error("Пользователь с ID {} не найден при обновлении доступов", existingUserId);
                    redirectAttributes.addFlashAttribute("error", "Пользователь не найден");
                    return "redirect:/register";
                }

                User existingUser = existingUserOpt.get();

                // Проверяем пароль, если он был введен
                if (rawPassword != null && !rawPassword.isEmpty() && !passwordEncoder.matches(rawPassword, existingUser.getPassword())) {
                    logger.warn("Неверный пароль для существующего пользователя {}", phone);
                    model.addAttribute("errorMessage", "Неверный пароль.");
                    model.addAttribute("phone", phone);
                    model.addAttribute("existingUser", true);
                    User tempUser = new User();
                    tempUser.setPhone(phone);
                    model.addAttribute("user", tempUser);
                    return "verify_code";
                }

                // Добавляем новый QR-код к существующему пользователю
                if (qrCode != null && !qrCode.isEmpty()) {
                    userService.handleReferralLink(existingUser, qrCode);
                    existingUser = userService.registerUser(existingUser);
                    logger.info("Пользователю {} добавлен доступ к коду {}", phone, qrCode);
                }

                // Аутентифицируем пользователя
                authenticateUser(existingUser.getPhone());

                // Очищаем данные сессии
                clearSessionData(session);

                // Если есть QR-код, перенаправляем на соответствующую инструкцию
                if (qrCode != null && !qrCode.isEmpty()) {
                    Optional<Instruction> instructionOpt = instructionService.findByQrCode(qrCode);
                    if (instructionOpt.isPresent()) {
                        redirectAttributes.addFlashAttribute("success",
                                "Доступ открыт! Инструкция: " + instructionOpt.get().getTitle());
                        return "redirect:/instruction/" + instructionOpt.get().getId();
                    }
                }

                redirectAttributes.addFlashAttribute("success", "Вы успешно получили доступ к инструкции!");
                return "redirect:/";

            } else {
                // Регистрация нового пользователя
                User newUser = new User();
                newUser.setPhone(phone);
                newUser.setPassword(rawPassword); // Хеширование происходит в userService.registerUser
                newUser.setPhoneVerified(true);
                newUser.setRole(Role.USER);
                newUser.setReferralLink(qrCode != null ? qrCode : "");

                User savedUser = userService.registerUser(newUser);
                logger.info("Зарегистрирован новый пользователь: {}", phone);

                // Аутентифицируем нового пользователя
                authenticateUser(savedUser.getPhone());

                // Очищаем данные сессии
                clearSessionData(session);

                // Перенаправляем на инструкцию, если есть QR-код
                if (qrCode != null && !qrCode.isEmpty()) {
                    Optional<Instruction> instructionOpt = instructionService.findByQrCode(qrCode);
                    if (instructionOpt.isPresent()) {
                        redirectAttributes.addFlashAttribute("success",
                                "Регистрация успешна! Вам открыт доступ к инструкции: " + instructionOpt.get().getTitle());
                        return "redirect:/instruction/" + instructionOpt.get().getId();
                    }
                }

                redirectAttributes.addFlashAttribute("success", "Регистрация успешно завершена!");
                return "redirect:/";
            }

        } catch (Exception e) {
            logger.error("Ошибка при регистрации/обновлении пользователя {}: {}", phone, e.getMessage());
            model.addAttribute("errorMessage", "Произошла ошибка. Попробуйте снова.");
            model.addAttribute("phone", phone);
            User tempUser = new User();
            tempUser.setPhone(phone);
            model.addAttribute("user", tempUser);
            return "verify_code";
        }
    }

    /**
     * Аутентифицирует пользователя после успешной регистрации или проверки
     */
    private void authenticateUser(String phone) {
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(phone);
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            logger.info("Пользователь {} успешно аутентифицирован", phone);
        } catch (Exception e) {
            logger.error("Ошибка при аутентификации пользователя {}: {}", phone, e.getMessage());
            throw e; // Перебрасываем исключение для обработки на верхнем уровне
        }
    }

    /**
     * Заполняет модель данными при ошибках валидации
     */
    private void populateModelForErrors(Model model, User user, HttpSession session) {
        model.addAttribute("user", user);
        String qrCode = (String) session.getAttribute("qrCodeForRegistration");
        if (qrCode != null) {
            model.addAttribute("qrCode", qrCode);
            instructionService.findByQrCode(qrCode)
                    .ifPresent(instruction -> model.addAttribute("instructionTitle", instruction.getTitle()));
        }
    }

    /**
     * Очищает данные сессии после завершения регистрации/авторизации
     */
    private void clearSessionData(HttpSession session) {
        session.removeAttribute("registrationPhone");
        session.removeAttribute("registrationPassword");
        session.removeAttribute("qrCodeForRegistration");
        session.removeAttribute("existingUserMode");
        session.removeAttribute("existingUserId");
    }
}