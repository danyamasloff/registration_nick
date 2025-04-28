package com.nikolay.nikolay.model;

import jakarta.persistence.*;

/**
 * Сущность, представляющая инструкцию к медицинскому аппарату.
 */
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

    @Column(nullable = false)
    private boolean available = false; // Флаг доступности инструкции (true/false). По умолчанию false.

    // Поле href используется только для отображения в UI и вычисляется динамически.
    @Transient
    private String href;

    // Геттеры и сеттеры
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }
}