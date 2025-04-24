package com.nikolay.nikolay.dto;

/**
 * Data Transfer Object для хранения данных, полученных от Telegram Login Widget.
 * Поля используют snake_case для совместимости с API Telegram.
 */
public class TelegramAuthDTO {
    private Long id;               // ID пользователя Telegram
    private String first_name;     // Имя пользователя (в snake_case как в API Telegram)
    private String last_name;      // Фамилия пользователя (может быть null)
    private String username;       // Имя пользователя в Telegram (без @)
    private String photo_url;      // URL фотографии профиля (может быть null)
    private String auth_date;      // Дата аутентификации (Unix timestamp)
    private String hash;           // Хеш для проверки подлинности данных
    private String referralLink;   // Реферальная ссылка (не часть стандартного API Telegram)

    // Геттеры и сеттеры
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFirst_name() {
        return first_name;
    }

    public void setFirst_name(String first_name) {
        this.first_name = first_name;
    }

    public String getLast_name() {
        return last_name;
    }

    public void setLast_name(String last_name) {
        this.last_name = last_name;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhoto_url() {
        return photo_url;
    }

    public void setPhoto_url(String photo_url) {
        this.photo_url = photo_url;
    }

    public String getAuth_date() {
        return auth_date;
    }

    public void setAuth_date(String auth_date) {
        this.auth_date = auth_date;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getReferralLink() {
        return referralLink;
    }

    public void setReferralLink(String referralLink) {
        this.referralLink = referralLink;
    }

    // Вспомогательные методы
    public String getFirstName() {
        return first_name;
    }

    public String getLastName() {
        return last_name;
    }

    public String getPhotoUrl() {
        return photo_url;
    }

    public String getAuthDate() {
        return auth_date;
    }

    /**
     * Возвращает полное имя пользователя (Имя Фамилия)
     */
    public String getFullName() {
        if (last_name != null && !last_name.isEmpty()) {
            return first_name + " " + last_name;
        }
        return first_name != null ? first_name : "";
    }
}