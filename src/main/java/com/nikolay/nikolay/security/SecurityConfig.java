package com.nikolay.nikolay.security;

import com.nikolay.nikolay.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity; // Для @PreAuthorize
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity; // Добавлено для ясности
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher; // Для logout

/**
 * Конфигурация Spring Security для веб-приложения.
 * Определяет правила доступа к URL, настройки формы входа, выхода и провайдер аутентификации.
 */
@Configuration
@EnableWebSecurity // Явно включаем поддержку веб-безопасности Spring
@EnableMethodSecurity(prePostEnabled = true) // Включаем поддержку аннотаций @PreAuthorize и т.д.
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService; // Сервис для загрузки данных пользователя по логину (телефону)
    private final PasswordEncoder passwordEncoder; // Бин для хеширования и проверки паролей

    // Конструктор для внедрения зависимостей
    public SecurityConfig(CustomUserDetailsService customUserDetailsService, PasswordEncoder passwordEncoder) {
        this.customUserDetailsService = customUserDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Определяет основную цепочку фильтров безопасности Spring Security.
     * Настраивает авторизацию запросов, форму входа, выход и CSRF.
     * @param http Объект HttpSecurity для конфигурации.
     * @return Сконфигурированный объект SecurityFilterChain.
     * @throws Exception Возможные исключения при конфигурации.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Включаем поддержку CORS для запросов от Telegram
                .cors().and()
                // Настраиваем провайдер аутентификации (для проверки логина/пароля)
                .authenticationProvider(authenticationProvider())
                // Настраиваем авторизацию HTTP-запросов
                .authorizeHttpRequests(auth -> auth
                        // --- Правила доступа ---
                        // Доступ к админке только для пользователей с ролью ADMIN
                        .requestMatchers("/admin/**").hasRole("ADMIN") // Важно: hasRole ожидает "ADMIN", а не "ROLE_ADMIN"
                        // Публичные маршруты: доступны всем (включая анонимных)
                        .requestMatchers(
                                "/",                   // Главная страница
                                "/register",           // Страница регистрации
                                "/register/send-code", // Отправка кода
                                "/register/verify",    // Проверка кода
                                "/login",              // Страница входа
                                "/telegram-callback",  // Callback от Telegram (должен быть доступен без аутентификации)
                                "/process-telegram-auth", // Обработка данных от Telegram
                                "/static/**",          // Статические ресурсы (CSS, JS, изображения из /static/)
                                "/uploads/**",         // Статические ресурсы из /static/uploads/ (если используется)
                                "/css/**", "/js/**", "/images/**" // Явное разрешение для стандартных папок статики
                        ).permitAll()
                        // Маршрут для инициирования привязки Telegram - требует аутентификации
                        // (Можно настроить здесь или использовать @PreAuthorize в контроллере)
                        .requestMatchers("/profile", "/profile/link-telegram").authenticated()
                        // Все остальные запросы требуют аутентификации
                        .anyRequest().authenticated()
                )
                // Настраиваем форму входа
                .formLogin(form -> form
                        .loginPage("/login")                // URL страницы входа
                        .loginProcessingUrl("/login")       // URL, на который отправляется форма (стандартный Spring Security)
                        .defaultSuccessUrl("/", true)       // URL после успешного входа (всегда на главную)
                        .failureUrl("/login?error=true")    // URL при ошибке входа
                        .permitAll()                        // Разрешаем доступ к странице входа всем
                )
                // Настраиваем выход из системы
                .logout(logout -> logout
                        // Используем AntPathRequestMatcher для явного указания метода POST для /logout
                        // .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST")) // Рекомендуется для CSRF
                        .logoutUrl("/logout")               // URL для выхода (можно оставить GET для простоты без CSRF)
                        .logoutSuccessUrl("/login?logout=true") // URL после успешного выхода
                        .invalidateHttpSession(true)        // Делаем сессию недействительной
                        .deleteCookies("JSESSIONID")        // Удаляем cookie сессии
                        .permitAll()                        // Разрешаем доступ к URL выхода всем
                )
                // Настраиваем CSRF (Cross-Site Request Forgery) защиту
                .csrf(csrf -> csrf
                        // ВНИМАНИЕ: Отключение CSRF упрощает разработку, но СНИЖАЕТ БЕЗОПАСНОСТЬ.
                        // В реальном приложении рекомендуется включить CSRF и использовать токены в формах,
                        // особенно для POST, PUT, DELETE запросов.
                        // Если приложение stateless (например, REST API с JWT), CSRF можно отключать.
                        // Для session-based приложения лучше оставить включенным: .csrf(Customizer.withDefaults())
                        .disable() // Пока отключаем для простоты
                );

        return http.build();
    }

    /**
     * Создает и настраивает DaoAuthenticationProvider.
     * Этот провайдер использует UserDetailsService для загрузки данных пользователя
     * и PasswordEncoder для проверки пароля.
     * @return Настроенный DaoAuthenticationProvider.
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        // Устанавливаем сервис для загрузки пользовательских данных
        authProvider.setUserDetailsService(customUserDetailsService);
        // Устанавливаем энкодер паролей для проверки хеша
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    /**
     * Предоставляет бин AuthenticationManager, используемый Spring Security.
     * @param authConfig Конфигурация аутентификации.
     * @return Бин AuthenticationManager.
     * @throws Exception Возможные исключения.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}