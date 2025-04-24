package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.NovofonVerificationService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid; // Для валидации User
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Убраны неиспользуемые импорты (Value, PreAuthorize, Authentication, SecurityContextHolder)
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

    // Убрали неиспользуемое поле telegramBotUsername

    public RegistrationController(
            NovofonVerificationService novofonVerificationService,
            UserService userService,
            PasswordEncoder passwordEncoder,
            InstructionService instructionService) {
        this.novofonVerificationService = novofonVerificationService;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.instructionService = instructionService;
    }

    /**
     * Отображает форму регистрации.
     * @param referralLink Необязательный параметр QR-кода из URL.
     * @param model Модель для передачи данных в шаблон.
     * @param session HTTP сессия для сохранения QR-кода.
     * @return Имя шаблона страницы регистрации ("register").
     */
    @GetMapping("/register")
    public String showRegistrationForm(
            @RequestParam(value = "ref", required = false) String referralLink,
            Model model,
            HttpSession session) {

        logger.info("Запрос на страницу регистрации с ref={}", referralLink);

        if (referralLink != null && !referralLink.isEmpty() && session.getAttribute("qrCodeForRegistration") == null) {
            session.setAttribute("qrCodeForRegistration", referralLink);
            logger.info("QR-код {} сохранен в сессии для регистрации.", referralLink);
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
     * @param user Объект пользователя с данными из формы (телефон, пароль). Валидируется.
     * @param bindingResult Результат валидации user.
     * @param model Модель для передачи данных обратно в шаблон при ошибках.
     * @param session HTTP сессия для временного хранения данных.
     * @return Имя шаблона страницы верификации ("verify_code") или обратно на "register" при ошибке.
     */
    @PostMapping("/register/send-code")
    public String sendVerificationCode(
            @Valid @ModelAttribute("user") User user,
            BindingResult bindingResult,
            Model model,
            HttpSession session) {

        // Используем UserService для нормализации
        String normalizedPhone = userService.normalizePhoneNumber(user.getPhone());
        user.setPhone(normalizedPhone); // Обновляем телефон в объекте user ДО проверок

        // Проверяем, что нормализованный номер не null (если normalizePhoneNumber может вернуть null)
        if (normalizedPhone == null) {
            bindingResult.addError(new FieldError("user", "phone", "Некорректный формат номера телефона."));
        } else {
            // Проверяем, не занят ли уже этот номер телефона
            if (userService.findByPhone(normalizedPhone).isPresent()) {
                logger.warn("Попытка регистрации с уже существующим телефоном: {}", normalizedPhone);
                bindingResult.addError(new FieldError("user", "phone", "Этот номер телефона уже зарегистрирован."));
            }
        }


        // Проверяем ошибки валидации (включая @Valid и нашу проверку на уникальность)
        if (bindingResult.hasErrors()) {
            logger.warn("Ошибки валидации при отправке кода: {}", bindingResult.getAllErrors());
            model.addAttribute("user", user); // Возвращаем user с ошибками и нормализованным (или невалидным) телефоном
            String qrCode = (String) session.getAttribute("qrCodeForRegistration");
            if (qrCode != null) {
                model.addAttribute("qrCode", qrCode);
                instructionService.findByQrCode(qrCode)
                        .ifPresent(instruction -> model.addAttribute("instructionTitle", instruction.getTitle()));
            }
            return "register"; // Возвращаем на форму регистрации с сообщениями об ошибках
        }

        // Сохраняем введенные данные (телефон, пароль) в сессию для использования на шаге верификации
        session.setAttribute("registrationPhone", normalizedPhone);
        session.setAttribute("registrationPassword", user.getPassword()); // Сохраняем НЕ хешированный пароль

        // Отправляем код верификации через Novofon
        try {
            novofonVerificationService.sendVerificationCode(normalizedPhone);
            logger.info("Отправлен код верификации Novofon на номер: {}", normalizedPhone);

            model.addAttribute("phone", normalizedPhone); // Передаем нормализованный телефон
            model.addAttribute("user", user); // Передаем user для консистентности

            return "verify_code"; // Имя HTML шаблона для ввода кода

        } catch (Exception e) {
            logger.error("Ошибка Novofon при отправке кода верификации на {}: {}", normalizedPhone, e.getMessage());
            model.addAttribute("errorMessage", "Не удалось отправить код верификации. Попробуйте позже.");
            model.addAttribute("user", user); // Возвращаем пользователя
            String qrCode = (String) session.getAttribute("qrCodeForRegistration");
            if (qrCode != null) {
                model.addAttribute("qrCode", qrCode);
                instructionService.findByQrCode(qrCode)
                        .ifPresent(instruction -> model.addAttribute("instructionTitle", instruction.getTitle()));
            }
            return "register"; // Возврат на форму регистрации
        }
    }

    /**
     * Проверяет код верификации и завершает регистрацию нового пользователя.
     * @param code Код верификации, введенный пользователем.
     * @param session HTTP сессия для получения сохраненных данных (телефон, пароль, QR).
     * @param model Модель для передачи сообщения об ошибке.
     * @param redirectAttributes Атрибуты для передачи сообщения после редиректа.
     * @return Редирект на страницу входа ("/login") при успехе или обратно на "verify_code" при ошибке.
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

        if (phone == null || rawPassword == null) {
            logger.error("Данные регистрации (телефон/пароль) не найдены в сессии.");
            redirectAttributes.addFlashAttribute("error", "Сессия истекла, попробуйте снова.");
            return "redirect:/register";
        }

        boolean isCodeValid = novofonVerificationService.verifyCode(phone, code);

        if (!isCodeValid) {
            logger.warn("Введен неверный код верификации для телефона: {}", phone);
            model.addAttribute("errorMessage", "Неверный код подтверждения.");
            model.addAttribute("phone", phone);
            User tempUser = new User();
            tempUser.setPhone(phone); // Передаем телефон, чтобы он отобразился в шаблоне
            model.addAttribute("user", tempUser); // Передаем объект user (хотя бы с телефоном)
            return "verify_code";
        }

        try {
            novofonVerificationService.clearCode(phone);
            logger.info("Код для {} успешно верифицирован и очищен.", phone);
        } catch (Exception e) {
            logger.warn("Не удалось очистить код для {}: {}", phone, e.getMessage());
        }

        User newUser = new User();
        newUser.setPhone(phone);
        // Пароль хешируется внутри userService.registerUser
        newUser.setPassword(rawPassword);
        newUser.setPhoneVerified(true);
        newUser.setRole(com.nikolay.nikolay.enums.Role.USER);
        newUser.setTelegramId(null);
        newUser.setTelegram(null);

        if (qrCode != null && !qrCode.isEmpty()) {
            newUser.setReferralLink(qrCode);
            logger.info("Новому пользователю {} назначен QR-код: {}", phone, qrCode);
        } else {
            newUser.setReferralLink("");
        }

        try {
            // Метод registerUser теперь хеширует пароль
            userService.registerUser(newUser);
            logger.info("Успешно зарегистрирован новый пользователь: {}", phone);

            session.removeAttribute("registrationPhone");
            session.removeAttribute("registrationPassword");
            session.removeAttribute("qrCodeForRegistration");

            redirectAttributes.addFlashAttribute("success", "Регистрация прошла успешно! Теперь вы можете войти.");
            return "redirect:/login";

        } catch (Exception e) {
            logger.error("Ошибка при сохранении нового пользователя {}: {}", phone, e.getMessage());
            model.addAttribute("errorMessage", "Произошла ошибка при регистрации. Возможно, телефон уже используется.");
            model.addAttribute("phone", phone);
            User tempUser = new User();
            tempUser.setPhone(phone);
            model.addAttribute("user", tempUser);
            return "verify_code";
        }
    }

    // --- МЕТОД linkTelegram УДАЛЕН ОТСЮДА ---

    // --- МЕТОД normalizePhoneNumber УДАЛЕН ОТСЮДА ---

}