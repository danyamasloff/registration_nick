package com.nikolay.nikolay.repository;

import com.nikolay.nikolay.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    Optional<User> findByTelegram(String telegram);

    Optional<User> findByTelegramId(Long telegramId);

    // Добавляем методы для прямого обновления полей Telegram
    @Modifying
    @Query("UPDATE User u SET u.telegramId = :telegramId, u.telegram = :telegram WHERE u.id = :userId")
    int updateTelegramFields(@Param("userId") Long userId,
                             @Param("telegramId") Long telegramId,
                             @Param("telegram") String telegram);

    @Modifying
    @Query(value = "UPDATE users SET telegram_id = :telegramId, telegram = :telegram WHERE phone = :phone",
            nativeQuery = true)
    int updateTelegramFieldsByPhone(@Param("phone") String phone,
                                    @Param("telegramId") Long telegramId,
                                    @Param("telegram") String telegram);
}