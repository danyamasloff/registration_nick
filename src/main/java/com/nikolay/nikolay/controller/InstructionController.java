package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.enums.Role;
import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Controller
public class InstructionController {
    private final InstructionService instructionService;
    private final UserService userService;

    public InstructionController(InstructionService instructionService, UserService userService) {
        this.instructionService = instructionService;
        this.userService = userService;
    }

    @GetMapping("/")
    public String home(Model model) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Проверяем, авторизован ли пользователь
        boolean isAuthenticated = authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getName());

        // Если пользователь авторизован, получаем его данные
        String phone = isAuthenticated ? authentication.getName() : null;
        Optional<User> userOpt = isAuthenticated ? userService.findByPhone(phone) : Optional.empty();
        String referralLink = null;
        boolean isAdmin = false;

        if (isAuthenticated && userOpt.isPresent()) {
            User user = userOpt.get();
            referralLink = user.getReferralLink();
            isAdmin = user.getRole() == Role.ADMIN; // Проверяем, является ли пользователь администратором
        }

        // Получаем все инструкции
        List<Instruction> instructions = instructionService.getAllInstructions();

        // Преобразуем referralLink в список ссылок, если он не пустой
        List<String> referralLinks = referralLink != null ? Arrays.asList(referralLink.split(",")) : new ArrayList<>();

        // Мы можем использовать переменную "final" или "эффективно final" для значения "referralLinks"
        final List<String> finalReferralLinks = referralLinks;
        final boolean finalIsAdmin = isAdmin;

        // Добавляем флаг доступности и правильный URL для каждой инструкции
        instructions.forEach(instruction -> {
            // Проверяем, доступна ли инструкция для пользователя
            boolean isAvailable = finalIsAdmin || (isAuthenticated && finalReferralLinks.contains(instruction.getQrCode()));
            instruction.setAvailable(isAvailable);

            // Если инструкция доступна, устанавливаем правильный URL, иначе ссылка на "javascript:void(0)"
            instruction.setHref(isAvailable ? "/instruction/" + instruction.getId() : "javascript:void(0)");
        });

        // Добавляем атрибуты в модель
        model.addAttribute("instructions", instructions);
        model.addAttribute("referralLink", referralLink); // Передаем referralLink в модель, если он есть

        return "index";
    }


    @GetMapping("/instruction/{id}")
    public String viewInstruction(@PathVariable Long id, Model model) {
        Instruction instruction = instructionService.getInstructionById(id)
                .orElseThrow(() -> new IllegalArgumentException("Инструкция не найдена"));
        model.addAttribute("instruction", instruction);
        return "instruction";
    }
}
