package com.nikolay.nikolay.service;

import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.repository.InstructionRepository;
import org.slf4j.Logger; // Импортируем логгер
import org.slf4j.LoggerFactory; // Импортируем логгер
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Сервис для управления инструкциями.
 * Предоставляет бизнес-логику для работы с сущностями Instruction.
 */
@Service
public class InstructionService {

    private static final Logger logger = LoggerFactory.getLogger(InstructionService.class); // Добавляем логгер

    private final InstructionRepository instructionRepository;
    private final FileStorageService fileStorageService; // Предполагаем, что этот сервис нужен для файлов инструкций

    // Конструктор для внедрения зависимостей
    public InstructionService(InstructionRepository instructionRepository, FileStorageService fileStorageService) {
        this.instructionRepository = instructionRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Получает список всех инструкций.
     * @return Список всех инструкций.
     */
    public List<Instruction> getAllInstructions() {
        logger.debug("Запрос на получение всех инструкций");
        return instructionRepository.findAll();
    }

    /**
     * Получает инструкцию по её уникальному идентификатору (ID).
     * @param id ID инструкции.
     * @return Optional с найденной инструкцией или Optional.empty().
     */
    public Optional<Instruction> getInstructionById(Long id) {
        logger.debug("Запрос на получение инструкции по ID: {}", id);
        return instructionRepository.findById(id);
    }

    /**
     * Находит инструкцию по её уникальному QR-коду.
     * @param qrCode QR-код инструкции.
     * @return Optional с найденной инструкцией или Optional.empty().
     */
    public Optional<Instruction> findByQrCode(String qrCode) {
        logger.debug("Поиск инструкции по QR-коду: {}", qrCode);
        return instructionRepository.findByQrCode(qrCode);
    }

    /**
     * Сохраняет (создает или обновляет) инструкцию в базе данных.
     * @param instruction Инструкция для сохранения.
     */
    public void saveInstruction(Instruction instruction) {
        // Используем логгер вместо System.out.println
        logger.info("Сохранение инструкции ID: {}, Title: '{}'", instruction.getId(), instruction.getTitle());
        // Здесь может быть логика сохранения файлов через fileStorageService, если контент - это ссылка на файл
        instructionRepository.save(instruction);
        logger.debug("Инструкция ID: {} успешно сохранена.", instruction.getId());
    }

    /**
     * Удаляет инструкцию по её ID.
     * @param id ID инструкции для удаления.
     */
    public void deleteInstruction(Long id) {
        logger.info("Запрос на удаление инструкции ID: {}", id);
        // Здесь может быть логика удаления связанных файлов через fileStorageService
        instructionRepository.deleteById(id);
        logger.info("Инструкция ID: {} успешно удалена.", id);
    }
}