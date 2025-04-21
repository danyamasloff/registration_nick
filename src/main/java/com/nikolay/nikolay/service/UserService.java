package com.nikolay.nikolay.service;

import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> findByPhone(String phone) {
        // Нормализуем номер телефона при поиске
        return userRepository.findByPhone(normalizePhoneNumber(phone));
    }

    /**
     * Находит пользователя по имени пользователя Telegram
     * @param telegram имя пользователя Telegram (без @)
     * @return Optional с найденным пользователем или пустой Optional
     */
    public Optional<User> findByTelegram(String telegram) {
        return userRepository.findByTelegram(telegram);
    }

    public void registerUser(User user) {
        // Нормализуем телефон перед сохранением
        user.setPhone(normalizePhoneNumber(user.getPhone()));

        // Хешируем пароль, если он не пустой и не захеширован
        if (user.getPassword() != null && !user.getPassword().isEmpty() && !user.getPassword().startsWith("$2a$")) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        userRepository.save(user);
    }

    /**
     * Нормализует номер телефона в соответствии с требованиями модели
     * @param phone исходный номер телефона
     * @return нормализованный номер в формате +XXXXXXXXXX
     */
    public String normalizePhoneNumber(String phone) {
        if (phone == null) {
            return null;
        }

        // Удаляем все нецифровые символы
        String digits = phone.replaceAll("[^\\d]", "");

        // Если номер начинается с 8 для России, заменяем на 7
        if (digits.startsWith("8") && digits.length() == 11) {
            digits = "7" + digits.substring(1);
        }

        // Проверяем длину и добавляем код страны 7 для номеров из 10 цифр
        if (digits.length() == 10) {
            digits = "7" + digits;
        }

        // Добавляем + в начало, чтобы соответствовать паттерну валидации
        return "+" + digits;
    }
}