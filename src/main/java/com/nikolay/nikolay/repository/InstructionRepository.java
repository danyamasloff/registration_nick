package com.nikolay.nikolay.repository;

import com.nikolay.nikolay.model.Instruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Репозиторий для доступа к данным инструкций (сущность Instruction) в базе данных.
 * Предоставляет стандартные CRUD операции и пользовательские методы поиска.
 */
@Repository
public interface InstructionRepository extends JpaRepository<Instruction, Long> {

    /**
     * Поиск инструкции по её уникальному QR-коду.
     * @param qrCode QR-код для поиска.
     * @return Optional с найденной инструкцией, если существует, иначе Optional.empty().
     */
    Optional<Instruction> findByQrCode(String qrCode);
}