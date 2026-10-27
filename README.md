# JJReader · P2 / 0.3.0-p2 / code13

P2 добавляет полноценные пометки: пять цветов, комментарий, сочетание цвета и текста, пересечения, просмотр/редактирование, удаление с отменой, устойчивый черновик и список по месту в книге. Старые записи дополнены `noteVersion=2` и прежним жёлтым цветом без изменения ID, цитат, комментариев и диапазонов.

Контекст N=350 на сторону настраивается для каждой книги. Общий модуль `report/` строит логические главы по TOC и его фрагментам, контекст и детерминированные группы с нумерацией. Логическая глава пересекает ресурсы Readium; внутри одного XHTML тоже возможны разные главы. Неоднозначность помечается ресурсной границей. Отдельный просмотр «Контексты» использует эту же модель; DOCX и окончательное копирование отчёта остаются P3.

Переход между документами закрыт непрозрачным слоем до ACK целевой позиции, WebView visual-state callback и pre-draw. Фиксированной задержки показа нет. Семантика TAP/GESTURE/начала и точных якорей P1.1 сохранена. Основной режим — sections, unified остаётся диагностикой.

[ТЗ](docs/JJReader_TZ.md), [отчёт P2](docs/P2_NOTES_CONTEXT_REPORT.md), [короткая проверка Honor](docs/HONOR_CHECKLIST.md). P1.1 принят пользователем и слит в master a9fee52. **Эта сборка P2 на Honor и Android instrumentation NOT_RUN**; JVM/Robolectric/Chromium обозначены отдельно. Рабочие пять фраз TTS сохранены; P3–P5 и merge P2 не выполнялись. Исторические отчёты P0/P1 относятся к прежним версиям.

## Сборка в Windows / PowerShell

Открыть эту папку в Android Studio. Нужны JDK 25, SDK Platform 37 и Build Tools 37.0.0. Версии зафиксированы: AGP 9.4.1, Gradle Wrapper 9.6.0 с SHA-256, Kotlin / Compose compiler 2.4.20, minSdk 31, compileSdk / targetSdk 37. Readium 3.4.0 требует compileSdk 37 и Kotlin 2.4 metadata, поэтому Kotlin обновлён относительно пустого проекта. Остальной исходный стек сохранён. В Android Studio выбрать Gradle JDK 25; существующий `gradle-daemon-jvm.properties` также фиксирует JDK 25.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :edge-protocol:test :app:lintDebug :app:assembleDebugAndroidTest
```

APK: `app\build\outputs\apk\debug\app-debug.apk`. Instrumentation APK: `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`. Для запуска Android-тестов нужен подключённый аппарат или настроенный эмулятор:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

В облаке эти тесты только собраны, запуск **NOT_RUN**. Изолированные тесты используют отдельный каталог и не очищают книги пользователя.

## Подпись и обновление без потери пометок

`applicationId = com.fantadrom.jjreader`, `versionName = 0.3.0-p2`, `versionCode = 13`. Использован тот же debug-ключ среды, что для code 1–12. SHA-256 сертификата:

`06ba3132107a954a4640ad60a29138b297449a6172bb763b8832293dc1c727ef`

Для следующих облачных сборок сохранить `/workspace/android-user/debug.keystore`. Это **debug**, не ключ будущего production-релиза. Ключ не публикуется в Git и не включается в пакет выдачи. Облачные обновления следует собирать в этой же среде с сохранённым ключом. При локальной установке из Android Studio нужно продолжать использовать тот же локальный ключ, которым установлена предыдущая версия.

При локальной сборке Android Studio использует собственный debug-ключ. Если у вас уже есть подходящий ключ с указанным выше отпечатком, перед сборкой можно указать его путь:

```powershell
$env:JJREADER_DEBUG_KEYSTORE = (Resolve-Path "C:\ВашиКлючи\jjreader-debug.keystore").Path
.\gradlew.bat :app:assembleDebug
```

При конфликте подписи не удалять приложение с пометками; вернуться в среду исходной подписи. Для независимой проверки изменённой LGPL-библиотеки можно пересобрать проект со своим debug-ключом и отдельным applicationId, сохранив рабочую установку и её данные.

XML/DOM-регрессия запускается после JVM-тестов: они создают `app/build/xhtml-regression` из реального SafeHtml и injector из AAR Readium. Robolectric 4.16.1 / API 35 нужен для Android Uri внутри injector, его кеш изолирован в `work/`; это JVM-проверка, не Android WebView runtime. Для Node нужны зависимости из `tools/package-lock.json` и путь к `readium-reflowable.js` именно из AAR 3.4.0:

```powershell
npm ci --prefix tools
node tools\verify_dom.cjs "C:\Путь\readium-reflowable.js"
```

## Устройство исходников и данных

- `books/`: атомарный импорт, UUID внутренней книги, проверка ZIP без распаковки на диск.
- `engine/`: Readium, фильтрация HTML, мост DOM ↔ локатор. `assets/locator-bridge.js` не открывает Java-интерфейс книге.
- `text/`: каноническая модель v1 и счётчик Unicode code points.
- `notes/`: версия схемы 1, AtomicFile для пометок, черновика, позиции и размера шрифта.
- `tts/`: прямой WebSocket, сегментация, MediaPlayer, аудиофокус, отмена и поколение запуска.
- `edge-protocol/`: изолированная Java-библиотека Sec-MS-GEC v2, LGPL-3.0-or-later.

Данные: `files/books-v1/<UUID>/book.epub`, `book.json`, `reader.json`; текущая книга — `files/books-v1/current.json`. EPUB остаётся неизменной копией. Производный кеш unified хранится отдельно в `files/renditions-v1`, не заменяя источник; HTML фильтруется: скрипты книги и внешние ресурсы отключены; DRM, включая обфусцированные шрифты с encryption.xml, и fixed layout отклоняются. Новый ID получает только новый исходный SHA256; прежние дубли доступны через «Книги». P1 расширяет полку, сохраняя прежние каталоги и схему данных. Не применять очистку данных приложения между проверками.

Нормализация: порядок spine, без разметки, nav, script/style, hidden/aria-hidden, SVG/math и alt. ASCII-пробелы и NBSP схлопываются в один пробел; блочные границы — два LF; хвостовые пробелы/LF удаляются. Без NFC-преобразования; code points не смешиваются с UTF-16 DOM offset. Границы якорей расширяются до grapheme через Intl.Segmenter. При его отсутствии сложные составные символы требуют отдельной проверки. Разделители между ресурсами в счётчик не добавляются. CSS-скрытый издателем текст пока может учитываться: это ограничение P0.

Полка, яркость, темы и навигация реализованы в P1. Полные цветовые пометки/контекст P2, экспорт DOCX P3, полный плеер P4 и приёмка P5 остаются отдельными этапами.

## Материалы и лицензии

Утверждённый `design/branding/launcher_icon_source.png` не изменён; подготовлены обычные/adaptive ресурсы пяти плотностей с тёмным фоном и полями. `design/branding/fantadrom_logo.webp` сохранён без изменений, в интерфейсе и APK не используется. Дополнительного экрана запуска с задержкой нет. Синтетический EPUB и иконки воспроизводятся `tools/prepare_assets.py` (Pillow); для обычной сборки Python не нужен и в Android-приложение он не входит.

**В APK включена библиотека Edge protocol под LGPL-3.0-or-later.** [Лицензии, источники и пересборка](docs/THIRD_PARTY.md). Исходники библиотеки, лицензии и полный исходный проект поставлены вместе с APK. Readium — BSD-3-Clause; OkHttp/AndroidX/Kotlin — Apache-2.0; jsoup — MIT. Readest не копировался.

## Данные и проверки P0.2

В `reader.json` добавляется `positionV2` с ID книги/ресурсом, диапазоном code points, цитатой и hash. Первые события navigator не пишут поверх него. После проверки правильного DOM выполняется переход и проверка видимости; запись позиции разрешается только затем. Во время IME/редактора запись заморожена. Старые пометки, черновики и доступный legacy DOM range сохраняются; уже не записанный старой версией диапазон восстановить из отсутствующих данных невозможно.

`fontUnits=readium-scale-v1` явно помечает прежнюю шкалу без умножения на 100. Кеш P1 разделён по UUID, максимум12 MP3 на книгу; диагностика различает cache/network и сохраняет только разрешённые метаданные. Endpoint и сообщения Edge-протокола прежние.

`tools/verify_browser_runtime.cjs` принимает каталог `assets`, извлечённый из AAR Readium 3.4.0, путь к установленному Chromium и каталог результатов с синтетической `diagram.png` из примера. Он проверяет реальную CSS-геометрию/CSP в настольном Chromium; Java callbacks замещены, это не Android runtime. Native `ReaderRuntimeTest` использует настоящую P0 Activity в изолированном каталоге: long-press/ActionMode, шрифт, пометки, черновик, recreate, orientation и видимость декорации. Необязательный разработческий `tools/android_process_restart.ps1` выполняет две instrumentation-фазы с force-stop и проверкой нового PID, без очистки данных. Для обычной обратной связи ADB не нужен: «Диагностика TTS» → «Копировать».
