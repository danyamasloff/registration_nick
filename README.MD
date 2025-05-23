# Система инструкций к медицинским аппаратам (Проект Nikolay)

Веб-приложение на Spring Boot для управления и распространения инструкций к медицинским аппаратам. Включает систему регистрации и аутентификации пользователей с поддержкой верификации по телефону (звонок через Novofon) и входа/регистрации через Telegram.

## Основные функции

* Регистрация пользователей по номеру телефона с верификацией звонком (TTS-код через Novofon).
* Стандартная авторизация по номеру телефона и паролю.
* Регистрация/авторизация через Telegram Login Widget.
    * *Текущая реализация:* После авторизации Telegram требует ввода и верификации номера телефона (звонком Novofon) и установки пароля. Реализовано создание нового пользователя или поиск по Telegram ID. Логика связывания с существующим аккаунтом по номеру телефона требует доработки (см. раздел "Потенциальные улучшения").
* (Предположительно) Доступ к инструкциям.
* (Предположительно) Панель администратора.
* (Предположительно) Защита контента.

## Технологии

* Java 17
* Spring Boot 3.4.4 (или ваша актуальная версия)
* Spring Security 6.x
* Spring Data JPA (Hibernate)
* Thymeleaf (+ Thymeleaf Spring Security Extras)
* MySQL 8.0+
* Novofon Call API v4.0 (для верификации звонком TTS)
* Telegram Bot API (через Login Widget)
* Maven

## Предварительные требования

* JDK 17 или выше
* Apache Maven 3.6+
* Сервер MySQL 8.0 или выше
* **Аккаунт Novofon:**
    * Зарегистрированный виртуальный номер телефона.
    * Сгенерированные **API Key (`key`) и API Secret (`secret`)**.
    * Настроенный **белый список IP (IP Whitelist)** для API (см. Настройка).
* **Telegram Бот:**
    * Созданный через `@BotFather`.
    * Полученный **Bot Token**.
    * Установленный **домен для Login Widget** (`/setdomain`).

## Установка и Запуск ЛОКАЛЬНО

1.  **Клонировать репозиторий:**
    ```bash
    git clone [https://github.com/danyamasloff/registration_nick.git](https://github.com/danyamasloff/registration_nick.git)
    cd registration_nick
    ```

2.  **Настроить базу данных MySQL:**
    * Подключитесь к вашему локальному серверу MySQL (например, запущенному в Docker).
    * Создайте базу данных (если еще не создана):
        ```sql
        CREATE DATABASE manuals_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        ```

3.  **Настроить Novofon (для локального теста):**
    * Войдите в личный кабинет Novofon.
    * Получите API Key и Secret (Телефония -> Пользователи АТС -> Администратор -> API -> Активировать ключи -> Сгенерировать Secret).
    * **Добавьте ваш текущий внешний IP-адрес** в белый список API ("Настройки -> Правила и настройки безопасности" -> "API"). Узнать IP: `myip.com`.

4.  **Настроить Telegram Бота (для локального теста):**
    * Получите токен и имя пользователя бота от `@BotFather`.
    * Временно установите домен для Login Widget с помощью `/setdomain`, используя публичный URL из шага 6 (например, от `cloudpub.ru`).

5.  **Настроить `application.properties` (для локального запуска):**
    * Откройте файл `src/main/resources/application.properties`.
    * Заполните секции `spring.datasource.*`, `novofon.*`, `telegram.*`, используя данные для **локальной** БД, ваши ключи Novofon/Telegram и **временный публичный URL** для `telegram.auth.redirect-uri` (см. шаг 6).
    * Для первого локального запуска `spring.jpa.hibernate.ddl-auto=update` может быть удобен для создания таблиц.

6.  **Настроить публичный доступ (ОБЯЗАТЕЛЬНО для теста Telegram Login):**
    * Используйте сервис туннелирования, например, **`cloudpub.ru`** (рекомендовано для РФ).
    * Зарегистрируйтесь на `cloudpub.ru`, скачайте/настройте клиент по их [документации](https://cloudpub.ru/docs/).
    * Запустите туннель на порт `8090`: `cloudpub http 8090` (или аналогичная команда).
    * Скопируйте **публичный `https://` URL**, который выдаст `cloudpub`.
    * **Используйте этот URL:**
        * Установите **домен** (часть без `https://`) в `@BotFather` (шаг 4).
        * Установите **полный URL** (с `/login/telegram/callback`) в `telegram.auth.redirect-uri` (шаг 5).
        * **Перезапустите** ваше Spring Boot приложение после изменения `application.properties`.
        * **Не закрывайте** терминал с `cloudpub`.

7.  **Собрать и запустить проект локально:**
    ```bash
    mvn clean install
    mvn spring-boot:run
    ```
    * Приложение будет доступно локально (`http://localhost:8090`) и через туннель (`https://...cloudpub.ru`). Тестировать Telegram Login нужно через **публичный URL**.

---

## Развертывание на сервере (Production/Staging)

Если у вас есть выделенный сервер (VPS или другой хостинг) с публичным IP-адресом и/или доменным именем, выполните следующие шаги для развертывания:

1.  **Предварительные требования на сервере:**
    * Установлена **Java (JDK) 17** или выше.
    * Установлен и настроен сервер **MySQL 8.0+**.
    * Создана база данных `manuals_db` (как в п.2 локальной установки).
    * Настроен доступ к серверу по сети (например, открыты порты 80, 443 и/или 8090 в firewall).

2.  **Настройка Novofon и Telegram для сервера:**
    * **IP Whitelist Novofon:** Добавьте **статический IP-адрес вашего сервера** в белый список API Novofon (см. п.3 локальной установки).
    * **Домен Telegram:** Установите через `@BotFather` (`/setdomain`) **основное доменное имя вашего сервера** (например, `your-app-domain.com`).

3.  **Конфигурация приложения для сервера:**
    * **Не используйте `application.properties` из репозитория напрямую для production!** Создайте отдельный файл `application.properties` **на сервере**.
    * Скопируйте содержимое вашего локального `application.properties`, но **измените значения** на актуальные для сервера:
        * `spring.datasource.url`: Укажите хост (`localhost`, если MySQL на том же сервере, или IP/хост сервера БД) и **имя базы данных на сервере**.
        * `spring.datasource.username`, `spring.datasource.password`: Укажите **логин и пароль для MySQL на сервере**.
        * `novofon.api.secret`, `novofon.virtual_number`: Укажите **боевые ключи Novofon**.
        * `telegram.bot.token`, `telegram.bot.username`: Укажите **боевые данные Telegram**.
        * `telegram.auth.redirect-uri`: Укажите **полный URL вашего сервера** с путем к callback (например, `https://your-app-domain.com/login/telegram/callback`).
        * **ВАЖНО:** `spring.jpa.hibernate.ddl-auto`: Установите значение **`validate`** (проверять схему) или **`none`** (не трогать схему). **Никогда не используйте `update` или `create` для рабочей базы данных!**
    * **Размещение конфигурации:** Положите этот настроенный `application.properties` **на сервере** в одну из локаций, откуда Spring Boot его автоматически подхватит:
        * В ту же папку, где будет лежать `.jar` файл приложения.
        * В подпапку `/config` в той же папке, где лежит `.jar`.
        * (Другие способы: переменные окружения, Spring Cloud Config и т.д. - см. [документацию Spring Boot по внешней конфигурации](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)).

4.  **Сборка исполняемого JAR:**
    * На вашей локальной машине (или на сервере, если там есть Maven) выполните команду для сборки **толстого JAR-файла**, включающего все зависимости:
        ```bash
        mvn clean package
        # Или mvn clean install, если 'package' не срабатывает сразу
        ```
    * В папке `target/` вашего проекта появится файл вида `Nikolay-0.0.1-SNAPSHOT.jar`.

5.  **Копирование JAR на сервер:**
    * Скопируйте созданный `.jar` файл на ваш сервер (используя `scp`, `rsync`, FTP или любой другой способ).

6.  **Запуск приложения на сервере:**
    * Подключитесь к серверу по SSH.
    * Перейдите в папку, куда вы скопировали JAR-файл (и где лежит ваш настроенный `application.properties`).
    * Запустите приложение командой:
        ```bash
        java -jar Nikolay-0.0.1-SNAPSHOT.jar
        # Можно добавить параметры JVM, если нужно: java -Xmx512m -jar ...
        ```
    * Приложение запустится и будет использовать `application.properties` из текущей папки. Оно будет доступно по адресу вашего сервера и порту `8090` (или порту, указанному в `server.port`).

7.  **(Рекомендуется) Запуск как сервис:**
    * Чтобы приложение работало в фоновом режиме, перезапускалось после сбоев или перезагрузки сервера, его нужно настроить как системный сервис.
    * Spring Boot JAR-файлы могут быть легко интегрированы с `systemd` (на большинстве современных Linux). Прочитайте [документацию Spring Boot по запуску как сервис](https://docs.spring.io/spring-boot/docs/current/reference/html/deployment.html#deployment.installing). Это потребует создания небольшого `.service` файла для `systemd`.

8.  **(Опционально) Настройка Reverse Proxy (Nginx/Apache):**
    * Часто перед Spring Boot приложением ставят веб-сервер Nginx или Apache. Он может:
        * Обслуживать запросы на стандартные порты 80/443 и перенаправлять их на порт вашего приложения (8090).
        * Настраивать HTTPS (SSL/TLS).
        * Более эффективно отдавать статические файлы.
        * Выполнять балансировку нагрузки (если у вас несколько экземпляров приложения).
    * Это стандартная практика для production-окружений.

---

## Структура проекта (Основные папки)

* `src/main/java/com/nikolay/nikolay/`
    * `controller` - Контроллеры Spring MVC.
    * `model` - Сущности JPA (Entity).
    * `repository` - Интерфейсы Spring Data JPA.
    * `service` - Сервисный слой (бизнес-логика).
    * `config` - Классы конфигурации Spring (включая Security).
* `src/main/resources/`
    * `application.properties` - Файл конфигурации (для локальной разработки).
    * `templates` - HTML-шаблоны Thymeleaf.
    * `static` - Статические ресурсы (CSS, JS, изображения).

## Основные URL (Примеры)

* `/` - Главная страница (может требовать логина).
* `/login` - Страница входа.
* `/register` - Страница регистрации (через звонок).
* `/verify` - Страница ввода кода подтверждения (после звонка).
* `/complete-registration` - Страница завершения регистрации после входа через Telegram (требует ввода и верификации телефона + установки пароля).
* `/admin` - (Предположительно) Панель администратора.

## Потенциальные улучшения / Текущие особенности

* **Связывание аккаунтов:** Текущая реализация входа через Telegram создает нового пользователя, если пользователь с таким Telegram ID не найден. Если пользователь ранее регистрировался по телефону, будет создан отдельный аккаунт. Требуется доработка логики на странице `/complete-registration` для проверки существующего номера телефона и связывания аккаунтов.
* **Обработка ошибок API:** Добавить более детальную обработку кодов ошибок от Novofon API.
* **Безопасность паролей:** Убедиться, что при всех операциях с пользователями используется надежное хеширование паролей (`PasswordEncoder`).
* **Первый администратор:** Процесс создания первого администратора не автоматизирован. Требуется ручное добавление или реализация начальной настройки.