# JJReader · P0.7.1 / 0.1.8-p0 / code 9

**P0.7.1:** устранены обязательные JAXP/Xerces setFeature в Android-подготовке: используется production namespace-aware XmlPullParser с запретом DTD/entities. В начало диагностики добавлена сохраняемая последняя попытка открытия со stage/code, классами исключений и XML-provider. [Отчёт P0.7.1](docs/P0_7_1_ANDROID_XML_REPORT.md). Honor code8 не открывал unified; code9 на Honor NOT_RUN.

Android-прототип EPUB и Edge TTS. P0.7 добавляет единую вертикальную ленту через производный EPUB с одним XHTML в существующем стабильном Readium 3.4.0. Соседние исходные разделы находятся в одном DOM; отдельный горизонтальный переход не нужен. Исходная копия EPUB, UUID, модель v1 и сохранённые пометки остаются прежними. Общий адаптер переводит source-local координаты в DOM для пометок, позиции, процента и пятифразной озвучки через границу.

В «Диагностика TTS» → «Проверки» доступны оглавление и переключение unified/legacy без переимпорта. Версия, режим, converter/generation и WebView показаны в диагностике. Межраздельная пометка отклоняется с объяснением. Для сложного неподдерживаемого EPUB предлагается прежний режим.

**Историческая desktop-проверка P0.7 (code8; не подтверждает Android-открытие):** сборка, JVM, lint, строгий XML после Readium и desktop Chromium проходят. Большие синтетические фикстуры: 1 011 991 CP / 80 разделов и 3 025 092 CP / 200. На большей открытие Chromium заняло 3,4–4,6 с, reflow шрифта до 2,3 с; суммарный RSS renderer ~801 MiB (desktop, включая spare). Решение по пригодности подхода **требует Honor**; Android/Honor code8 NOT_RUN. Полный DOM не виртуализируется. [Архитектура, совместимость, ограничения, таблицы результатов и команды Windows](docs/P0_7_CONTINUOUS_READING_REPORT.md), [короткий чек-лист Honor](docs/HONOR_CHECKLIST.md).

Пользователь подтвердил на Honor ABR-LX1 / Android15 для **правильного code7** сохранение x2 после x1 → поворот → x2 → поворот, отсутствие накопительного дрейфа и полную ширину landscape. Ранее подтверждены шрифт, пометки, внутренняя копия и оба голоса/подсветка; Дмитрий иногда требовал повтора. Прежняя неуспешная трасса относилась к ошибочно запущенной старой версии. Эти подтверждения не заменяют проверку unified code8.

Рабочий TTS endpoint, голоса, SSML, кеш и retry сохранены; предел пробы — пять фраз. Новых сетевых маршрутов и фонового плеера нет. CSS scoped на раздел; обычные заголовки/абзацы/выделение и локальные изображения/шрифты поддерживаются в описанных пределах. Сложная позиционная/вертикальная вёрстка, неоднозначный readingOrder и нелинейные HTML-сноски требуют legacy. P1–P5 не начаты, merge не выполняется.

История: [P0](docs/P0_REPORT.md), [P0.1 XHTML](docs/P0_1_XHTML_REPORT.md), [P0.2 runtime](docs/P0_2_RUNTIME_REPORT.md), [P0.3 layout/retry](docs/P0_3_LAYOUT_TTS_REPORT.md), [P0.4 position/protocol](docs/P0_4_POSITION_VOICE_REPORT.md), [P0.5 resources/trace](docs/P0_5_RESOURCE_STATE_REPORT.md), [P0.6 ACK recovery](docs/P0_6_RESTORE_ACK_REPORT.md).

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

`applicationId = com.fantadrom.jjreader`, `versionName = 0.1.8-p0`, `versionCode = 9`. Использован тот же debug-ключ среды, что для code 1–8. SHA-256 сертификата:

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

Данные: `files/books-v1/<UUID>/book.epub`, `book.json`, `reader.json`; текущая книга — `files/books-v1/current.json`. EPUB остаётся неизменной копией. Производный кеш unified хранится отдельно в `files/renditions-v1`, не заменяя источник; HTML фильтруется: скрипты книги и внешние ресурсы отключены; DRM, включая обфусцированные шрифты с encryption.xml, и fixed layout отклоняются. В P0 каждый импорт получает новый ID. Предыдущие каталоги сохраняются, интерфейса полки и дедупликации ещё нет. Не применять очистку данных приложения между проверками. P1 должен сохранить эту схему или явно мигрировать её.

Нормализация: порядок spine, без разметки, nav, script/style, hidden/aria-hidden, SVG/math и alt. ASCII-пробелы и NBSP схлопываются в один пробел; блочные границы — два LF; хвостовые пробелы/LF удаляются. Без NFC-преобразования; code points не смешиваются с UTF-16 DOM offset. Границы якорей расширяются до grapheme через Intl.Segmenter. При его отсутствии сложные составные символы требуют отдельной проверки. Разделители между ресурсами в счётчик не добавляются. CSS-скрытый издателем текст пока может учитываться: это ограничение P0.

Полной полки, жестов/яркости/тем, полноценных цветовых пометок, экспорта DOCX, плеера P4 и приёмки P5 нет.

## Материалы и лицензии

Утверждённый `design/branding/launcher_icon_source.png` не изменён; подготовлены обычные/adaptive ресурсы пяти плотностей с тёмным фоном и полями. `design/branding/fantadrom_logo.webp` сохранён без изменений, в интерфейсе и APK не используется. Дополнительного экрана запуска с задержкой нет. Синтетический EPUB и иконки воспроизводятся `tools/prepare_assets.py` (Pillow); для обычной сборки Python не нужен и в Android-приложение он не входит.

**В APK включена библиотека Edge protocol под LGPL-3.0-or-later.** [Лицензии, источники и пересборка](docs/THIRD_PARTY.md). Исходники библиотеки, лицензии и полный исходный проект поставлены вместе с APK. Readium — BSD-3-Clause; OkHttp/AndroidX/Kotlin — Apache-2.0; jsoup — MIT. Readest не копировался.

## Данные и проверки P0.2

В `reader.json` добавляется `positionV2` с ID книги/ресурсом, диапазоном code points, цитатой и hash. Первые события navigator не пишут поверх него. После проверки правильного DOM выполняется переход и проверка видимости; запись позиции разрешается только затем. Во время IME/редактора запись заморожена. Старые пометки, черновики и доступный legacy DOM range сохраняются; уже не записанный старой версией диапазон восстановить из отсутствующих данных невозможно.

`fontUnits=readium-scale-v1` явно помечает прежнюю шкалу без умножения на 100. Кеш содержит максимум 12 MP3; диагностика различает cache/network и сохраняет только разрешённые метаданные. Endpoint и сообщения Edge-протокола прежние.

`tools/verify_browser_runtime.cjs` принимает каталог `assets`, извлечённый из AAR Readium 3.4.0, путь к установленному Chromium и каталог результатов с синтетической `diagram.png` из примера. Он проверяет реальную CSS-геометрию/CSP в настольном Chromium; Java callbacks замещены, это не Android runtime. Native `ReaderRuntimeTest` использует настоящую P0 Activity в изолированном каталоге: long-press/ActionMode, шрифт, пометки, черновик, recreate, orientation и видимость декорации. Необязательный разработческий `tools/android_process_restart.ps1` выполняет две instrumentation-фазы с force-stop и проверкой нового PID, без очистки данных. Для обычной обратной связи ADB не нужен: «Диагностика TTS» → «Копировать».
