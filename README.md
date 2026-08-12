# InappUpdate — Google Play In-App Update для Godot 4

Плагин-обёртка над [Play In-App Update API](https://developer.android.com/guide/playcore/in-app-updates).
Позволяет предлагать/форсировать обновление приложения **изнутри игры**, без ручного
похода в Play Store.

> ⚠️ Только для обновления **всего приложения** (APK/AAB) через Google Play.
> Обновить нативный код (`.so` / GDExtension) как-то ещё — нельзя: линкер грузит
> нативные библиотеки только из установленного APK, а Play запрещает докачку
> исполняемого кода. Поэтому любое изменение C++/GDExtension = новый релиз в Play +
> этот плагин. PCK-патчи оставляем только для ассетов/скриптов.

Godot: **4.6** · Платформа: **Android** (на других платформах синглтон просто отсутствует, вызовы безопасны).

---

## Что внутри

```
addons/inapp_update/
├── plugin.cfg                     # регистрация EditorPlugin
├── InappUpdatePlugin.gd           # EditorExportPlugin: подключает AAR + зависимости при экспорте
├── InappUpdate.gd                 # class_name InappUpdate — нода-обёртка (сигналы/методы)
├── bin/{debug,release}/           # сюда кладутся собранные AAR
├── android/                       # исходники нативного плагина (Kotlin) для сборки AAR
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   └── src/main/
│       ├── AndroidManifest.xml    # meta-data v2 (discovery плагина)
│       └── java/com/aintdevs/inappupdate/InappUpdatePlugin.kt
└── README.md
```

---

## Два режима обновления

| Режим | Поведение | Когда |
|---|---|---|
| **Flexible** | Качается в фоне, игрок продолжает играть. Ставится и перезапускается по кнопке. | Обычные обновления. Не мешает. |
| **Immediate** | Полноэкранный блокирующий экран Play, докачка + рестарт делает сам Play. | Критичные апдейты (сломанный сейв-формат, обязательный хотфикс). Обычно при `updatePriority()` 4–5. |

---

## API (GDScript)

### Методы — действия
| Метод | Действие |
|---|---|
| `check_for_update()` | Спросить Play, есть ли апдейт (асинхронно → сигналы). |
| `start_flexible_update()` | Запустить фоновую докачку. |
| `start_immediate_update()` | Запустить блокирующее обновление. |
| `start_recommended_update() -> UpdateType` | Запустить тот тип, что вернёт `recommended_update_type()`. |
| `complete_flexible_update()` | Установить докачанный flexible-апдейт и перезапустить приложение. |
| `is_available() -> bool` | Доступен ли нативный синглтон (true только на Android с AAR). |

### Методы — определить тип апдейта из кода
Валидны **после** успешного `check_for_update()` (читают закешированный `AppUpdateInfo`).
Позволяют решить flexible/immediate **не дожидаясь** обработчика сигнала.

| Метод | Возвращает |
|---|---|
| `is_update_available() -> bool` | Есть ли апдейт. |
| `is_flexible_allowed() -> bool` | Разрешён ли flexible для этого апдейта. |
| `is_immediate_allowed() -> bool` | Разрешён ли immediate. |
| `get_update_priority() -> int` | Приоритет 0–5 (0 без Play Developer API). |
| `get_available_version_code() -> int` | versionCode доступного апдейта (-1 если нет). |
| `get_client_version_staleness_days() -> int` | Сколько дней апдейт доступен на устройстве (-1 неизвестно). |
| `get_install_status() -> int` | Сырой `InstallStatus`. |
| `recommended_update_type() -> UpdateType` | `NONE` / `FLEXIBLE` / `IMMEDIATE` по правилам ниже. |

**Enum `InappUpdate.UpdateType`:** `NONE`, `FLEXIBLE`, `IMMEDIATE`.

`recommended_update_type()` эскалирует до **IMMEDIATE**, если `priority >= immediate_priority_threshold`
(по умолчанию 4) **или** `staleness >= immediate_staleness_days` (по умолчанию 14, `-1` = выкл),
и immediate разрешён; иначе **FLEXIBLE** если разрешён; иначе **NONE**. Пороги — поля ноды:

```gdscript
updater.immediate_priority_threshold = 5   # только приоритет 5 = обязательный
updater.immediate_staleness_days = -1      # не эскалировать по «возрасту»
```

### Сигналы
| Сигнал | Смысл |
|---|---|
| `update_available(version_code: int, priority: int)` | Апдейт есть. `priority` 0–5 из Play Console. |
| `update_not_available()` | Апдейтов нет. |
| `update_check_failed(reason: String)` | Проверка сорвалась (нет сети / не из Play / и т.п.). |
| `update_download_progress(bytes_downloaded, total_bytes)` | Прогресс flexible-докачки. |
| `update_downloaded()` | Flexible скачан → зовём `complete_flexible_update()`. |
| `update_flow_canceled()` | Игрок закрыл диалог обновления. |
| `update_flow_failed(reason: String)` | Флоу/установка упали. |
| `install_status_changed(status: int)` | Сырой Play `InstallStatus` (константы в `InappUpdate.gd`). |

---

## Использование

1. Включи плагин: **Project → Project Settings → Plugins → InappUpdate → Enable**.
2. Добавь ноду `InappUpdate` в свой автолоад (или создай через `InappUpdate.new()`).

```gdscript
extends Node

@onready var updater := InappUpdate.new()

func _ready() -> void:
    add_child(updater)
    updater.update_available.connect(_on_available)
    updater.update_downloaded.connect(_on_downloaded)
    updater.update_flow_failed.connect(func(r): push_warning("update fail: %s" % r))
    updater.check_for_update()

func _on_available(_version_code: int, _priority: int) -> void:
    # Решаем тип прямо из кода, без ветвлений в обработчике:
    match updater.recommended_update_type():
        InappUpdate.UpdateType.IMMEDIATE:
            updater.start_immediate_update()
        InappUpdate.UpdateType.FLEXIBLE:
            updater.start_flexible_update()
    # либо одной строкой:  updater.start_recommended_update()

func _on_downloaded() -> void:
    # показать «Обновление готово, перезапустить?» и по подтверждению:
    updater.complete_flexible_update()
```

Тип можно определить и **вне** обработчика сигнала — например, отложить решение до
своего момента в UI:

```gdscript
if updater.is_update_available():
    if updater.is_immediate_allowed() and updater.get_update_priority() >= 4:
        updater.start_immediate_update()
    elif updater.is_flexible_allowed():
        show_soft_update_banner(updater.get_available_version_code())
```

Immediate-режим и перезапуск после докачки плагин доводит сам (в т.ч. возобновляет
прерванный immediate-апдейт при возврате в приложение — обрабатывается в `onMainResume`).

---

## Сборка AAR

Сборку сейчас делать не нужно — но когда понадобится:

**Требования:** JDK 17, Android SDK, интернет для Maven.

Gradle-wrapper (`gradlew`, `gradle/`) скопирован из Godot-шаблона `android/build/` (Gradle 8.13),
`local.properties` с `sdk.dir` создаётся автоматически при первой сборке или задай вручную.

```bash
cd addons/inapp_update/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # если нет
./gradlew assembleRelease assembleDebug
```

> **Версия Kotlin.** `org.jetbrains.kotlin.android` в `build.gradle` должна уметь читать метаданные
> `godot-*-api.jar`. Для Godot 4.6 jar собран Kotlin 2.1.0 → плагин Kotlin тоже **2.1.0**
> (1.9.x падает с `incompatible version of Kotlin ... metadata is 2.1.0`). При смене версии Godot
> сверяй и это.

Собранные AAR окажутся в `android/build/outputs/aar/`. Скопируй их в `bin/`:

```bash
cp build/outputs/aar/InappUpdatePlugin-release.aar ../bin/release/
cp build/outputs/aar/InappUpdatePlugin-debug.aar   ../bin/debug/
```

Имена файлов должны совпадать с путями в `InappUpdatePlugin.gd → _get_android_libraries()`.

> `godotVersion` в `build.gradle` должен совпадать с версией Godot, которой экспортируешь
> (сейчас `4.6.0.stable`). Артефакт `org.godotengine:godot` берётся с Maven Central.

---

## Тестирование

In-App Update **нельзя** проверить на debug-APK, поставленном через `adb install` или из Godot напрямую —
Play не знает про такую сборку. Нужен один из способов:

### Вариант A — Internal App Sharing (быстрее всего)
1. Собери и **подпиши release-ключом** AAB (`export_presets.cfg → Android release`, `export_format=AAB`).
2. Play Console → **Internal app sharing** → загрузи AAB с **бо́льшим `versionCode`**, чем установленная у тебя версия.
3. Установи на устройство **предыдущую** версию (из того же Internal App Sharing или Internal testing).
4. Открой игру → `check_for_update()` должен вернуть `update_available`.

### Вариант B — Internal testing track
1. Залей версию N в трек **Internal testing**, установи её на устройство через ссылку тестировщика.
2. Залей версию N+1 в тот же трек.
3. Запусти установленную версию N → апдейт предложится.

Полезное:
- `versionCode` управляется вашим `AutoExportVersion`/`version_checker` — новая сборка обязана иметь больший код.
- Аккаунт устройства должен быть в списке тестировщиков трека.
- Проверяй `install_status_changed` и logcat по тегу `InappUpdatePlugin` при отладке.
- Immediate-приоритет (`updatePriority` 0–5) выставляется **только через Play Developer API** при публикации релиза (в UI Console его нет) — см. ниже.

---

## Что настроить ВНЕ кода (обязательно)

Плагин работает только если выполнены условия платформы:

1. **Приложение опубликовано в Google Play** (хотя бы в закрытом/internal-треке) под тем же
   `applicationId` (`com.aintdevs.justpolitics`) и подписано **тем же ключом**, что установленная сборка.
   На «сборке из воздуха» (adb/Godot deploy) API всегда вернёт «нет апдейта».

2. **Зависимость Play Core уже подтягивается** — `com.google.android.play:app-update(-ktx):2.1.0`
   объявлена и в нативном `build.gradle`, и в `_get_android_dependencies()`. Дополнительно руками
   в проекте прописывать не нужно.

3. **Интернет-соединение** — API ходит в Play. Отдельное разрешение не требуется (даёт Play Services).

4. **`versionCode` монотонно растёт** между релизами (иначе Play не видит «новее»).

5. **Для immediate/priority-логики:** приоритет обновления (`updatePriority`, 0–5) задаётся при
   публикации релиза только через **Google Play Developer API**
   (`Edits.tracks.releases[].inAppUpdatePriority`). В веб-UI Console этого поля нет.
   Без него `priority` всегда 0 — тогда решай flexible/immediate по своей логике (например, по
   разнице `version_code`).

6. **Тестовый доступ:** аккаунт устройства добавлен в тестировщики нужного трека
   (Internal testing / Internal app sharing).

Ничего в манифесте приложения, разрешениях или `google-services.json` дополнительно править не нужно —
`meta-data` для discovery лежит внутри AAR.

---

## Ограничения / заметки

- Работает от Android с Play Services; на устройствах без Google Play (Huawei и т.п.) — недоступно, `check_for_update` вернёт fail.
- Flexible-апдейт остаётся «висеть» скачанным ~несколько дней; если игрок не установил — Play может очистить, повторная докачка стартанёт заново.
- Плагин не хранит состояние между запусками игры — после рестарта снова зови `check_for_update()`.
- Нативные `.so`/GDExtension обновляются **только** этим полным апдейтом, PCK их не заменит.
