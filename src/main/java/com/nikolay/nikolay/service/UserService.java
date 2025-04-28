package com.nikolay.nikolay.service;

import com.nikolay.nikolay.model.User;
import com.nikolay.nikolay.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Сервис для управления пользователями.
 * ДОПОЛНЕНО: Улучшено логирование и добавлен метод для обновления по ID.
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final String REFERRAL_LINK_SEPARATOR = ",";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> findByTelegramId(Long telegramId) {
        // Добавим проверку на null ID
        if (telegramId == null) {
            return Optional.empty();
        }
        return userRepository.findByTelegramId(telegramId);
    }

    /**
     * Находит пользователя по его ID
     * @param id ID пользователя
     * @return Optional с найденным пользователем или пустой Optional
     */
    public Optional<User> findById(Long id) {
        if (id == null) {
            logger.warn("Попытка найти пользователя по null ID");
            return Optional.empty();
        }
        return userRepository.findById(id);
    }

    public Optional<User> findByPhone(String phone) {
        String normalizedPhone = normalizePhoneNumber(phone);
        if (normalizedPhone == null) {
            logger.warn("Попытка поиска пользователя по некорректному номеру: {}", phone);
            return Optional.empty();
        }
        logger.debug("Поиск пользователя по нормализованному номеру: {}", normalizedPhone); // Добавим debug лог
        return userRepository.findByPhone(normalizedPhone);
    }

    @Transactional
    public User registerUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("Пользователь не может быть null.");
        }

        logger.info("Сохранение/Обновление пользователя: ID={}, Phone={}, Telegram ID={}, Telegram={}",
                user.getId(), user.getPhone(), user.getTelegramId(), user.getTelegram());

        // Нормализация телефона (только если он не null)
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            String normalized = normalizePhoneNumber(user.getPhone());
            if (normalized == null) {
                logger.error("Не удалось нормализовать номер телефона: {}", user.getPhone());
                throw new IllegalArgumentException("Некорректный формат номера телефона: " + user.getPhone());
            }
            user.setPhone(normalized);
        } else if (user.getId() == null) {
            // Телефон обязателен для нового пользователя
            throw new IllegalArgumentException("Номер телефона обязателен для нового пользователя.");
        }


        // Хеширование пароля (только если он не null, не пустой и еще не хеширован)
        if (user.getPassword() != null && !user.getPassword().isBlank() && !user.getPassword().startsWith("$2a$")) {
            logger.debug("Хеширование пароля для пользователя с телефоном: {}", user.getPhone());
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }

        try {
            // Используем saveAndFlush для немедленной синхронизации с БД
            User savedUser = userRepository.saveAndFlush(user);
            logger.info("Пользователь успешно сохранен/обновлен через saveAndFlush: ID={}, Phone={}, Telegram ID={}, Telegram={}",
                    savedUser.getId(), savedUser.getPhone(), savedUser.getTelegramId(), savedUser.getTelegram());

            if (user.getTelegramId() != null) {
                entityManager.flush();
                entityManager.clear();
                // Перезагружаем пользователя из БД
                User reloadedUser = userRepository.findById(savedUser.getId()).orElse(null);
                if (reloadedUser == null || !user.getTelegramId().equals(reloadedUser.getTelegramId())) {
                    logger.warn("Данные Telegram не обновились после saveAndFlush для ID={}. Ожидался ID: {}, Фактический: {}. Попытка прямого обновления...",
                            savedUser.getId(), user.getTelegramId(), reloadedUser != null ? reloadedUser.getTelegramId() : "null");
                    // Если стандартный saveAndFlush не сработал (что странно, но возможно),
                    // пробуем наш надежный прямой метод обновления
                    boolean updatedDirectly = updateTelegramInfo(savedUser.getId(), user.getTelegramId(), user.getTelegram());
                    if (updatedDirectly) {
                        logger.info("Данные Telegram для ID={} успешно обновлены прямым запросом.", savedUser.getId());
                        // Перезагружаем еще раз, чтобы вернуть актуальное состояние
                        entityManager.flush();
                        entityManager.clear();
                        savedUser = userRepository.findById(savedUser.getId()).orElse(savedUser); // Возвращаем обновленного пользователя
                    } else {
                        logger.error("КРИТИЧЕСКАЯ ОШИБКА: Не удалось обновить данные Telegram для ID={} ни одним из способов!", savedUser.getId());
                        // В этом случае лучше выбросить исключение, чтобы транзакция откатилась
                        throw new RuntimeException("Не удалось обновить данные Telegram для пользователя ID: " + savedUser.getId());
                    }
                } else {
                    logger.info("Проверка после перезагрузки: Данные Telegram ID={} для пользователя ID={} совпадают.", reloadedUser.getTelegramId(), savedUser.getId());
                }
            }

            return savedUser;
        } catch (Exception e) {
            logger.error("Ошибка при сохранении/обновлении пользователя ID={}, Phone={}: {}", user.getId(), user.getPhone(), e.getMessage(), e);
            // Оборачиваем в RuntimeException, чтобы откатить транзакцию
            throw new RuntimeException("Не удалось сохранить/обновить пользователя: " + e.getMessage(), e);
        }
    }

    /**
     * Метод для прямого обновления полей Telegram по ID пользователя.
     * Использует JPQL для большей безопасности и переносимости, чем нативный SQL.
     * РЕКОМЕНДУЕТСЯ для привязки Telegram к существующему пользователю.
     * @param userId ID пользователя для обновления.
     * @param telegramId Новый Telegram ID (может быть null для отвязки).
     * @param telegramUsername Новый Telegram Username (может быть null).
     * @return true, если обновление прошло успешно (хотя бы одна строка обновлена), иначе false.
     */
    @Transactional
    public boolean updateTelegramInfo(Long userId, Long telegramId, String telegramUsername) {
        if (userId == null) {
            logger.error("Попытка обновить информацию Telegram без ID пользователя.");
            return false;
        }

        logger.info("Начало обновления Telegram данных для ID={}: telegramId={}, telegramUsername={}",
                userId, telegramId, telegramUsername);

        try {
            // Сначала проверим, существует ли пользователь и загрузим его
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                logger.error("Пользователь с ID={} не найден для обновления Telegram.", userId);
                return false;
            }

            // Логируем текущие значения
            logger.info("Текущие данные: telegramId={}, telegram={}",
                    user.getTelegramId(), user.getTelegram());

            // Прямое обновление полей объекта
            user.setTelegramId(telegramId);
            user.setTelegram(telegramUsername);

            // Сохраняем и синхронизируем с БД
            userRepository.saveAndFlush(user);

            // Очищаем кэш
            entityManager.flush();
            entityManager.clear();

            // Проверяем результат
            User updatedUser = userRepository.findById(userId).orElse(null);
            if (updatedUser != null) {
                logger.info("После обновления: telegramId={}, telegram={}",
                        updatedUser.getTelegramId(), updatedUser.getTelegram());

                // Проверка успешности обновления
                boolean telegramIdUpdated = (telegramId == null && updatedUser.getTelegramId() == null) ||
                        (telegramId != null && telegramId.equals(updatedUser.getTelegramId()));
                boolean telegramUsernameUpdated = (telegramUsername == null && updatedUser.getTelegram() == null) ||
                        (telegramUsername != null && telegramUsername.equals(updatedUser.getTelegram()));

                return telegramIdUpdated && telegramUsernameUpdated;
            }

            return false;
        } catch (Exception e) {
            logger.error("Ошибка при обновлении данных Telegram для пользователя ID={}: {}",
                    userId, e.getMessage(), e);
            throw new RuntimeException("Ошибка обновления Telegram данных: " + e.getMessage(), e);
        }
    }

    public void handleReferralLink(User user, String newReferralLink) {
        if (user == null || newReferralLink == null || newReferralLink.isBlank()) {
            return;
        }

        String currentLinksString = user.getReferralLink();
        List<String> currentLinks = (currentLinksString == null || currentLinksString.isBlank())
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(currentLinksString.split(REFERRAL_LINK_SEPARATOR)));

        // Используем trim() для удаления возможных пробелов вокруг ссылок
        String trimmedNewLink = newReferralLink.trim();

        if (!currentLinks.contains(trimmedNewLink)) {
            logger.debug("Добавление новой реферальной ссылки '{}' для пользователя ID {}", trimmedNewLink, user.getId());
            currentLinks.add(trimmedNewLink);
            // Сохраняем ссылки без лишних пробелов
            user.setReferralLink(String.join(REFERRAL_LINK_SEPARATOR, currentLinks));
        } else {
            logger.debug("Реферальная ссылка '{}' уже существует для пользователя ID {}", trimmedNewLink, user.getId());
        }
    }

    /**
     * Нормализует номер телефона к международному формату +7XXXXXXXXXX.
     * @param phone Исходный номер телефона.
     * @return Нормализованный номер или null, если формат некорректен.
     */
    public String normalizePhoneNumber(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^\\d]", "");

        if (digits.startsWith("8") && digits.length() == 11) {
            digits = "7" + digits.substring(1);
        } else if (digits.length() == 10 && !digits.startsWith("7")) { // Если 10 цифр и не начинается с 7
            digits = "7" + digits;
        } else if (digits.startsWith("7") && digits.length() == 11) {

        } else if (digits.length() == 11 && !digits.startsWith("7")) {

            if (phone.startsWith("+") && digits.length() >= 11) { // Проверяем исходную строку на '+'
                return "+" + digits;
            } else {
                logger.warn("Неопределенный формат номера (11 цифр, не начинается с 7): {}", phone);
                return null; // Считаем некорректным для РФ
            }
        }
        else {
            // Длина не 10 или 11 (после удаления 8ки) - некорректный формат для РФ
            logger.warn("Некорректная длина номера после удаления нецифровых символов: {} (исходный: {})", digits, phone);
            return null;
        }

        // Проверка итоговой длины (должна быть 11 для +7...)
        if (digits.length() != 11 || !digits.startsWith("7")) {
            logger.warn("Финальная проверка: Некорректный формат номера: {} (исходный: {})", digits, phone);
            return null;
        }

        // Добавляем +
        return "+" + digits;
    }
}