# REST API для общения с локальной моделью Ollama

Приложение на Spring Boot предоставляет REST API для диалогов с локальной моделью Ollama. Каждый диалог получает `conversationId`; передавайте его в следующих запросах, чтобы сохранить контекст.

## Требования

- Java 25
- Docker Compose

## Запуск Ollama

Из корня проекта запустите контейнеры:

```powershell
docker compose up -d
```

Ollama будет доступна приложению и на хосте по адресу `http://localhost:11431`. При первом запуске Compose скачает модель `qwen3.5:4b` в отдельный Docker volume. Файлы модели и кэш не хранятся в репозитории. По умолчанию используется CPU; чтобы включить GPU, настройте поддержку GPU в Docker Compose для своей системы.

## Запуск приложения

В отдельном окне PowerShell выполните:

```powershell
.\mvnw.cmd spring-boot:run
```

Приложение запустится на `http://localhost:8080`.

Параметры можно переопределить переменными окружения:

| Переменная | Значение по умолчанию | Назначение |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11431` | Адрес Ollama |
| `OLLAMA_MODEL` | `qwen3.5:4b` | Имя модели |
| `CHAT_MAX_HISTORY_TURNS` | `20` | Число последних пар вопрос–ответ в контексте диалога |

Пример настройки в PowerShell:

```powershell
$env:OLLAMA_BASE_URL = "http://localhost:11431"
$env:OLLAMA_MODEL = "qwen3.5:4b"
$env:CHAT_MAX_HISTORY_TURNS = "20"
.\mvnw.cmd spring-boot:run
```

## API диалога

Отправьте `POST /api/chat` с сообщением. `conversationId` можно не указывать: сервер создаст UUID и вернёт его в ответе.

```powershell
$firstReply = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/chat" `
  -ContentType "application/json" `
  -Body (@{ message = "Привет! Меня зовут Анна." } | ConvertTo-Json)

$firstReply
```

Ответ содержит идентификатор диалога и сообщение модели:

```json
{
  "conversationId": "идентификатор-диалога",
  "message": "Ответ модели"
}
```

Чтобы продолжить диалог, передайте полученный `conversationId`:

```powershell
$nextReply = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/chat" `
  -ContentType "application/json" `
  -Body (@{
    conversationId = $firstReply.conversationId
    message = "Как меня зовут?"
  } | ConvertTo-Json)

$nextReply
```

В теле запроса доступны поля:

| Поле | Обязательное | Описание |
| --- | --- | --- |
| `message` | Да | Сообщение пользователя; пустое значение отклоняется с HTTP 400 |
| `conversationId` | Нет | Идентификатор предыдущего диалога; если не указан, создаётся новый |

## Ошибки

Ошибки API возвращаются в общем формате:

```json
{
  "status": 503,
  "code": "MODEL_UNAVAILABLE",
  "message": "The model service is currently unavailable",
  "details": []
}
```

Некорректное тело запроса и ошибки валидации возвращают HTTP 400. При недоступности Ollama возвращается HTTP 503 (`MODEL_UNAVAILABLE`), при ошибке модели — HTTP 502 (`MODEL_ERROR`). Внутренние ошибки возвращают HTTP 500 (`INTERNAL_ERROR`); подробности исключения остаются в логе приложения.

## Хранение истории

История диалогов хранится в памяти приложения отдельно для каждого `conversationId`. В контекст модели включаются последние 20 пар вопрос–ответ (или значение `CHAT_MAX_HISTORY_TURNS`). История сбрасывается при перезапуске и не разделяется между несколькими экземплярами приложения.
