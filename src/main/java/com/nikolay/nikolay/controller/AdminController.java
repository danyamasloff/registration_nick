package com.nikolay.nikolay.controller;

import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.service.FileStorageService;
import com.nikolay.nikolay.service.InstructionService;
import com.nikolay.nikolay.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
public class AdminController {
    private final InstructionService instructionService;
    private final UserService userService;
    private final FileStorageService fileStorageService;

    public AdminController(InstructionService instructionService, UserService userService, FileStorageService fileStorageService) {
        this.instructionService = instructionService;
        this.userService = userService;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public String adminPanel() {
        return "admin/index";
    }

    @GetMapping("/instructions")
    public String listInstructions(Model model) {
        List<Instruction> instructions = instructionService.getAllInstructions();
        model.addAttribute("instructions", instructions);
        return "admin/instructions";
    }

    @GetMapping("/instructions/new")
    public String newInstructionForm(Model model) {
        model.addAttribute("instruction", new Instruction());
        return "admin/instruction_form";
    }

    @PostMapping("/instructions/save")
    public String saveInstruction(@ModelAttribute("instruction") Instruction instruction) {
        System.out.println(instruction);
        instructionService.saveInstruction(instruction);
        return "redirect:/admin/instructions";
    }

    @GetMapping("/instructions/edit/{id}")
    public String editInstruction(@PathVariable Long id, Model model) {
        Instruction instruction = instructionService.getInstructionById(id)
                .orElseThrow(() -> new IllegalArgumentException("Инструкция не найдена"));
        model.addAttribute("instruction", instruction);
        return "admin/instruction_form";
    }

    @GetMapping("/instructions/delete/{id}")
    public String deleteInstruction(@PathVariable Long id) {
        instructionService.deleteInstruction(id);
        return "redirect:/admin/instructions";
    }

    @GetMapping("/users")
    public String listUsers(Model model) {
        List<User> users = userService.getAllUsers();
        model.addAttribute("users", users);
        return "admin/users";
    }

    @GetMapping("/users/export")
    @ResponseBody
    public void exportUsers(HttpServletResponse response) throws IOException {
        List<User> users = userService.getAllUsers();

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=users.csv");
        response.setCharacterEncoding("UTF-8");

        PrintWriter writer = new PrintWriter(response.getOutputStream(), true);

        // Добавляем BOM для корректного отображения в Excel
        writer.write('\uFEFF');

        // Используем точку с запятой как разделитель
        writer.println("ID;Телефон;Telegram;Реферальная ссылка;Роль");

        for (User user : users) {
            writer.println(
                    "\"" + user.getId() + "\";" +
                            "\"" + user.getPhone() + "\";" +
                            "\"" + (user.getTelegram() != null ? user.getTelegram() : "") + "\";" +
                            "\"" + user.getReferralLink() + "\";" +
                            "\"" + user.getRole() + "\""
            );
        }

        writer.flush();
        writer.close();
    }

    @PostMapping("/instructions/uploadImage")
    @ResponseBody
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой!");
        }
        if (!file.getContentType().startsWith("image/")) {
            throw new IllegalArgumentException("Допустимы только изображения!");
        }

        String imageUrl = fileStorageService.saveFile(file);
        return Map.of("location", imageUrl);
    }

}
