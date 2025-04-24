package com.nikolay.nikolay.service;

import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserService userService; // Внедряем UserService

    public CustomUserDetailsService(UserRepository userRepository, UserService userService) { // Добавляем userService в конструктор
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // --- ВАЖНО: Нормализуем 'username' (который является телефоном) ПЕРЕД поиском ---
        String normalizedPhone = userService.normalizePhoneNumber(username);
        if (normalizedPhone == null) {
            throw new UsernameNotFoundException("Некорректный формат номера телефона: " + username);
        }

        // Ищем пользователя по НОРМАЛИЗОВАННОМУ номеру
        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь с телефоном " + normalizedPhone + " не найден"));

        // Создаем UserDetails (стандартная логика Spring Security)
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getPhone()) // Используем нормализованный телефон как username
                .password(user.getPassword()) // Передаем хешированный пароль
                .roles(user.getRole().name()) // Передаем роль
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(false)
                .build();
    }
}
