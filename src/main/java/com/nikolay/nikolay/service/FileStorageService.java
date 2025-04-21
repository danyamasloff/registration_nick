package com.nikolay.nikolay.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final String UPLOAD_DIR = System.getProperty("user.dir") + "/uploads"; // Внешняя папка

    public String saveFile(MultipartFile file) {
        try {
            // Создаём директорию, если её нет
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Генерируем уникальное имя файла
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : "";
            String uniqueFilename = UUID.randomUUID() + extension;

            // Сохраняем файл
            Path filePath = uploadPath.resolve(uniqueFilename);
            file.transferTo(filePath.toFile());

            System.out.println("Файл сохранён: " + filePath.toAbsolutePath());
            return "/uploads/" + uniqueFilename; // Относительный путь для фронта
        } catch (IOException e) {
            System.out.println("Ошибка при сохранении файла" + e);
            throw new RuntimeException("Не удалось сохранить файл", e);
        }
    }
}
