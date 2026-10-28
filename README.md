REST API для общения с локальной моделью Ollama

Приложение на Spring Boot предоставляет REST API для диалогов с локальной моделью Ollama. Каждый диалог получает conversationId; передавайте его в следующих запросах, чтобы сохранить контекст.

Требования
Java 25
Docker Compose
Запуск Ollama

Из корня проекта запустите контейнеры:

powershell
docker compose up -d

Ollama будет доступна приложению и на хосте по адресу http://localhost:11431. При первом запуске Compose скачает модель qwen3.5:4b в отдельный Docker volume. Файлы модели и кэш не хранятся в репозитории. По умолчанию используется CPU; чтобы включить GPU, настройте поддержку GPU в Docker Compose для своей системы.

Загрузка модели занимает несколько минут. Прогресс и ошибки видны в логе:

powershell
docker logs -f harness-ollama-pull

Убедиться, что модель загружена, можно так:

powershell
docker exec harness-ollama ollama list

Пока модель не загружена, запросы к API завершаются ошибкой MODEL_ERROR (HTTP 502).

Контейнерам заданы DNS-серверы 8.8.8.8 и 1.1.1.1 (см. docker-compose.yml): встроенный DNS Docker в некоторых окружениях не может определить адрес registry.ollama.ai, и модель не скачивается (server misbehaving). Если эти серверы недоступны в вашей сети, замените их в блоках dns на свои.

Запуск приложения

В отдельном окне PowerShell выполните:

powershell
.\mvnw.cmd spring-boot:run

Приложение запустится на http://localhost:8080.

Spring AI 2.1.0-M1 — milestone-версия, поэтому в pom.xml подключён репозиторий Spring Milestones (https://repo.spring.io/milestone).

Настройка

Параметры можно переопределить переменными окружения:

Переменная	Значение по умолчанию	Назначение
OLLAMA_BASE_URL	http://localhost:11431	Адрес Ollama
OLLAMA_MODEL	qwen3.5:4b	Имя модели
OLLAMA_NUM_PREDICT	512	Максимальное число токенов в ответе; при меньшем значении ответ обрывается, при большем растёт время ответа
CHAT_MAX_HISTORY_TURNS	20	Число последних пар вопрос–ответ в контексте диалога
CHAT_MAX_CONVERSATIONS	1000	Максимальное число диалогов в памяти; при превышении самые старые вытесняются
CHAT_CONVERSATION_TTL_MINUTES	60	Через сколько минут без обращений диалог удаляется
CHAT_SYSTEM_PROMPT	краткий промпт на английском (см. application.properties)	Системный промпт для каждого запроса к модели; пустое значение отключает его
CHAT_TIMEZONE	Europe/Moscow	Часовой пояс для текущих даты и времени, которые передаются модели

Модель, num-predict и temperature задаются только в application.properties (spring.ai.ollama.chat.options.*); приложение не переопределяет их в коде.

Пример настройки в PowerShell:

powershell
$env:OLLAMA_BASE_URL = "http://localhost:11431"
$env:OLLAMA_MODEL = "qwen3.5:4b"
$env:OLLAMA_NUM_PREDICT = "1024"
$env:CHAT_MAX_HISTORY_TURNS = "20"
$env:CHAT_TIMEZONE = "Europe/Moscow"
.\mvnw.cmd spring-boot:run
API диалога

Отправьте POST /api/chat с сообщением. conversationId можно не указывать: сервер создаст UUID и вернёт его в ответе.

powershell
$firstReply = Invoke-RestMethod `
  -Method Post `
-Uri "http://localhost:8080/api/chat" `
  -ContentType "application/json" `
-Body (@{ message = "Привет! Меня зовут Анна." } | ConvertTo-Json)

$firstReply

Ответ содержит идентификатор диалога и сообщение модели:

json
{
"conversationId": "идентификатор-диалога",
"message": "Ответ модели"
}

Чтобы продолжить диалог, передайте полученный conversationId:

powershell
$nextReply = Invoke-RestMethod `
  -Method Post `
-Uri "http://localhost:8080/api/chat" `
  -ContentType "application/json" `
-Body (@{
conversationId = $firstReply.conversationId
message = "Как меня зовут?"
} | ConvertTo-Json)

$nextReply

В теле запроса доступны поля:

Поле	Обязательное	Описание
message	Да	Сообщение пользователя; не пустое, не длиннее 8000 символов
conversationId	Нет	Идентификатор предыдущего диалога, не длиннее 64 символов; если не указан, создаётся новый

Ответ генерируется целиком, поэтому на CPU запрос может выполняться минуту и дольше. Параллельные запросы в один диалог выполняются по очереди.

Ошибки

Ошибки API возвращаются в общем формате:

json
{
"status": 503,
"code": "MODEL_UNAVAILABLE",
"message": "The model service is currently unavailable",
"details": []
}

Некорректное тело запроса и ошибки валидации (пустое или слишком длинное сообщение, слишком длинный conversationId) возвращают HTTP 400. При недоступности Ollama возвращается HTTP 503 (MODEL_UNAVAILABLE), при ошибке модели, в том числе если модель ещё не загружена, — HTTP 502 (MODEL_ERROR). Внутренние ошибки возвращают HTTP 500 (INTERNAL_ERROR); подробности исключения остаются в логе приложения.

Хранение истории

История диалогов хранится в памяти приложения отдельно для каждого conversationId. В контекст модели включаются последние 20 пар вопрос–ответ (или значение CHAT_MAX_HISTORY_TURNS). Число диалогов в памяти ограничено (CHAT_MAX_CONVERSATIONS), а диалог без обращений удаляется через CHAT_CONVERSATION_TTL_MINUTES минут. История сбрасывается при перезапуске и не разделяется между несколькими экземплярами приложения.

В каждый запрос к модели добавляется системное сообщение с системным промптом и текущими датой и временем (у модели нет собственных часов). Оно не сохраняется в истории диалога и не расходует лимит пар вопрос–ответ.

Аутентификации нет: любой, кто знает conversationId, может продолжить чужой диалог, поэтому не публикуйте сервис в открытую сеть без защиты.