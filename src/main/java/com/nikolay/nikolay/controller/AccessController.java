/*
package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.service.InstructionService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

*/
/**
 * Контроллер для обработки доступа по QR-кодам
 *//*

@Controller
public class AccessController {

    private static final Logger logger = LoggerFactory.getLogger(AccessController.class);

    private final InstructionService instructionService;

    public AccessController(InstructionService instructionService) {
        this.instructionService = instructionService;
    }

    */
/**
     * Обрабатывает запросы по QR-коду и перенаправляет на страницу входа
     *//*

    @GetMapping("/register")
    public String handleQrAccess(
            @RequestParam(value = "ref", required = false) String referralCode,
            Model model,
            HttpSession session) {

        // Проверяем наличие QR-кода в запросе
        if (referralCode == null || referralCode.isEmpty()) {
            logger.warn("Получен запрос без QR-кода");
            return "redirect:/login?error=no_qrcode";
        }

        logger.info("Получен запрос с QR-кодом: {}", referralCode);

        // Сохраняем код в сессии для использования после аутентификации
        session.setAttribute("qrCode", referralCode);

        // Находим инструкцию по коду
        Optional<Instruction> instructionOpt = instructionService.findByQrCode(referralCode);

        if (instructionOpt.isPresent()) {
            Instruction instruction = instructionOpt.get();
            logger.info("Найдена инструкция: {}", instruction.getTitle());

            // Добавляем информацию об инструкции в модель
            model.addAttribute("instructionFound", true);
            model.addAttribute("instructionTitle", instruction.getTitle());
            model.addAttribute("qrCode", referralCode);

            // Перенаправляем на страницу входа
            return "redirect:/login?qrCode=" + referralCode;
        } else {
            logger.warn("Инструкция с кодом {} не найдена", referralCode);
            return "redirect:/login?error=invalid_qrcode";
        }
    }
}*/
