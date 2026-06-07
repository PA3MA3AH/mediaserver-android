# MediaServer — Android Client

Android-приложение на Kotlin, которое автоматически синхронизирует фото и видео со смартфона на ПК через локальную сеть.

> Требует запущенного Rust-сервера на ПК. Подробнее в репозитории [mediaserver](https://github.com/PA3MA3AH/mediaserver).

---

## Как это работает

1. Приложение сканирует медиатеку устройства
2. Сравнивает файлы с уже отправленными (локальная БД через Room)
3. Новые файлы отправляет на сервер по TCP через локальную сеть
4. Галерея отображает синхронизированные медиафайлы

---

## Требования

- Android 8.0+ (API 26)
- Локальная сеть (Wi-Fi)
- Запущенный [mediaserver](https://github.com/PA3MA3AH) на ПК в той же сети

---

## Стек

| | |
|---|---|
| Язык | Kotlin |
| Минимальный SDK | 26 (Android 8.0) |
| БД | Room |
| Фоновые задачи | WorkManager |
| Сеть | OkHttp + TCP |
| Изображения | Coil |
| UI | ViewBinding, RecyclerView, SwipeRefreshLayout |

---

## Структура проекта

```
app/src/main/java/com/mediasync/
├── MainActivity.kt          — точка входа
├── GalleryActivity.kt       — галерея синхронизированных файлов
├── MediaSyncApp.kt          — Application класс
├── api/
│   └── GalleryApi.kt        — API для получения медиа с сервера
├── data/
│   ├── SyncDatabase.kt      — Room база данных
│   └── SyncedFile.kt        — модель синхронизированного файла
├── protocol/
│   └── TcpSender.kt         — отправка файлов по TCP
└── sync/
    ├── MediaScanner.kt      — сканирование медиатеки устройства
    └── SyncWorker.kt        — фоновая синхронизация (WorkManager)
```

---

## Сборка

### Локально

```bash
git clone https://github.com/PA3MA3AH/mediaserver-android.git
cd mediaserver-android
./gradlew assembleDebug
```

APK будет в `app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions

При каждом пуше в `main` автоматически собирается подписанный release APK. Скачать можно во вкладке [Actions](https://github.com/PA3MA3AH/mediaserver-android/actions) → последний билд → Artifacts → `app-release`.

---

## Скачать

Готовый APK доступен в разделе [Releases](https://github.com/PA3MA3AH/mediaserver-android/releases).

---

## Лицензия

MIT
