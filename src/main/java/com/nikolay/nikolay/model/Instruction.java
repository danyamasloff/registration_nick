package com.nikolay.nikolay.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Сущность, представляющая инструкцию к медицинскому аппарату.
 */
@Data // Генерирует геттеры, сеттеры, equals, hashCode, toString
@NoArgsConstructor // Генерирует конструктор без аргументов
@Entity // Указывает, что это JPA сущность
@Table(name = "instructions") // Явно указываем имя таблицы
public class Instruction {

    @Id // Первичный ключ
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Автоинкремент ID
    private Long id;

    @Column(nullable = false) // Поле не может быть null в БД
    private String title; // Название инструкции

    @Lob // Указывает, что поле может хранить большие данные (текст)
    @Column(nullable = false, columnDefinition = "TEXT") // Явно указываем тип колонки TEXT для совместимости
    private String content; // Содержимое инструкции (HTML или простой текст)

    @Column(unique = true) // QR-код должен быть уникальным (если это требуется)
    private String qrCode; // Уникальный QR-код, связанный с инструкцией

    // --- ИСПРАВЛЕНО ---
    // Убираем @Transient, чтобы поле сохранялось в БД
    // Добавляем @Column, чтобы указать, что оно не может быть null
    @Column(nullable = false)
    private boolean available = false; // Флаг доступности инструкции (true/false). По умолчанию false.

    // Поле href используется только для отображения в UI и вычисляется динамически.
    // Оставляем @Transient, так как его не нужно хранить в базе данных.
    @Transient
    private String href;

    // Ручные геттеры и сеттеры больше не нужны, так как используется @Data от Lombok.
    // Lombok автоматически сгенерирует:
    // getId(), setId(), getTitle(), setTitle(), getContent(), setContent(),
    // getQrCode(), setQrCode(), isAvailable(), setAvailable()
    // getHref(), setHref() - также будут сгенерированы для @Transient поля

}