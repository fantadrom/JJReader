# P0.7.1 — Android XML и диагностика открытия / 0.1.8-p0, code9

Исправлен дефект совместимости в конце `UnifiedBuilder.build`: обязательные `DocumentBuilderFactory.setFeature(FEATURE_SECURE_PROCESSING)` и Xerces `disallow-doctype-decl` заменены реальной XML-проверкой через `android.util.Xml.newPullParser()`. Стандартный Android15 Harmony DocumentBuilderFactory поддерживает только namespaces/validation. Это подтверждает дефект в коде; исключение непосредственно с Honor в старой диагностике **не было записано**.

На Honor code8 фактически отказали прежняя книга и два EPUB, WebView155.0.8059.43. Их текст недоступен в среде; нельзя утверждать, что конкретные книги теперь открываются. Code9 Android/Honor **NOT_RUN**, instrumentation собран. P0.7 целиком не принят, проверки большого романа/памяти остаются после устранения блокера. P1–P5 и merge не выполнялись.

## XML-путь

`AndroidXhtmlValidator` вызывается самой `UnifiedDocument.prepare`, до публикации кеша. Namespace processing — штатная feature XmlPullParser API31+. Используется строгий режим по умолчанию; relaxed и обработка DTD не включаются. Валидатор проходит токены до END_DOCUMENT, требует один корень, проверяет XML1.0 characters в исходной строке, entity refs и значениях атрибутов. Ошибки вложенности, namespace, неизвестные entity отклоняются.

DOCTYPE и ENTITY declarations запрещены до подачи строки парсеру; DOCDECL дополнительно запрещён в потоке токенов. Внешний resolver не устанавливается, внешнего ввода для XML-парсера нет. Числовые ссылки и предопределённые XML entities разрешены, включая supplementary Unicode; NUL/недопустимые scalar values запрещены. Предварительный консервативный запрет также отвергает буквальный `<!DOCTYPE`/`<!ENTITY` внутри комментария/CDATA; SafeHtml не создаёт такие конструкции в обычном итоговом XHTML. DOCTYPE оригинала обрабатывается прежним SafeHtml и в проверяемый документ не переносится.

Ошибки настройки отделены как `XML_PARSER_CONFIGURATION`, разбора — `XML_INVALID`, объявлений — `XML_DTD_FORBIDDEN`. Исключение сохраняет причину для ограниченного экспорта имён классов. Подавления ошибки парсера нет.

В новых production-путях P0.7 проверены XML/JAXP, ZIP/File, URI, SHA256, UUID, AtomicFile, Mutex: других обязательных JDK-provider features не найдено. ManagementFactory/строгий JAXP остаются в desktop-тестах, а не Android-подготовке. Новая java.time.Instant доступна на minSdk31. Зависимости/SDK не менялись.

Валидатор не меняет XHTML, карту, normalisation v1, source hashes или координаты. Converter остаётся unified-v1: исправный готовый кеш не сбрасывается. При ошибке до rename staging удаляется; готовый результат не публикуется. Копия EPUB и reader.json не переписываются.

## Последняя попытка до ReaderSession

Независимый `opening-attempt-v1.json` через AtomicFile хранит schema1 и latest. В начале существующего диагностического JSON находится блок `opening`. Он доступен и без navigator/rendition/session. Поля: attemptId, versionName/code, requestedMode, activeMode или null, running/success/failed/cancelled, stage, UTC startedUtc, elapsedMs, converter/generation/cacheHit (неизвестные — null), errorCode, до четырёх exceptionClasses, фактический xmlProvider при XML-проверке. elapsedMs — wall clock delta, ограниченная снизу нулём; не CPU time.

Этапы устанавливаются перед действиями: SOURCE_OPEN, ORDER_CHECK, CACHE_CHECK, BUILD, XML_VALIDATE, CACHE_PUBLISH, DERIVED_OPEN, NAVIGATOR_INIT. BUILD после XML обозначает сборку архива/копирование ресурсов. Ошибки неподдерживаемого CSS/модели/ресурсов имеют отдельные стабильные UNSUPPORTED_* коды. Другие ошибки получают код этапа. Запись failed сохраняется **до** диалога; на экране показаны этап/код и возможность явного legacy.

Обновления принимаются только для текущего attemptId: отмена старой корутины не перезаписывает новую попытку. Cancellation фиксируется и пробрасывается. Legacy — отдельная попытка той же книги, с тем же NoteStore; lastUnifiedFailure остаётся после успешного fallback. Начальное значение «Пометки · 0» не записывается в хранилище книги. Состояние failed не экспортируется как preparing/legacy, неизвестные времена — null. Историческая reader schema3 остаётся историей чтения, а не журналом новой подготовки; её семантика не менялась, новый блок имеет собственную schema1.

Ни текст, ни заголовки/пути/URI, ни сообщения/stack исключений, цитаты/комментарии/SSML в новом блоке не сохраняются. Телеметрии и нового экрана нет. unified остаётся режимом по умолчанию.

## Проверки и границы доказательств

- JVM/Robolectric вызывает **тот же** production XmlPullParser validator; фактический provider выводится в JUnit system-out. Это Android API в Robolectric, не запуск Android/Honor и не доказательство поведения устройства.
- Корректные namespaces epub/xml, UTF-8/кириллица/emoji, пустые теги, ampersand и числовые ссылки проверяются вместе с отрицательными nesting/namespace/entity/DTD/XXE случаями. Декларации отклоняются до возможности внешнего чтения.
- Управляемый отказ на XML_VALIDATE в реальной prepare: failed-запись до ReaderSession, удаление stage; затем реальное открытие исходной Publication в legacy и повторная unified-подготовка/открытие derived Publication. Источник и предварительно записанный reader.json неизменны.
- Отдельно проверяются redaction, сохранение lastUnifiedFailure, cancelled и поздний callback старой попытки после новой success.
- Существующая native UnifiedReadingTest сохраняет проверки реальных P0.6 якорей/пометок, unified/legacy, жестов и поворотов. Новый AndroidXmlOpenTest вызывает production validator и prepare встроенного EPUB на устройстве и печатает настоящий XML-provider. Оба набора в облаке только собираются: **NOT_RUN**.
- Большой benchmark не повторяется: содержимое, координаты и политика преобразования не менялись. Старые результаты P0.7 в выдаче помечены историческими; они не подтверждают Honor code9.

Команды Windows, JDK25/SDK37 прежние:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :edge-protocol:test :app:lintDebug :app:assembleDebugAndroidTest
# Только при подключённом тестовом устройстве:
.\gradlew.bat :app:connectedDebugAndroidTest
adb logcat -d -s JJReaderXmlTest
```

Фактические итоги сборки/проверок и provider приведены ниже после выполнения. Подпись совместимого APK должна совпадать с прежней: SHA256 `06ba3132107a954a4640ad60a29138b297449a6172bb763b8832293dc1c727ef`. Не удалять приложение ради конфликта подписи. Commit и hashes выдачи находятся в DELIVERY.json/SHA256SUMS.txt.

## Фактический итог code9

| Проверка | Результат |
| --- | --- |
| assembleDebug, assembleDebugAndroidTest | PASS |
| JVM app/edge | 67 tests, 0 failures/errors, 1 намеренный skip опционального большого benchmark |
| Production XML validator в Robolectric | PASS; `com.android.org.kxml2.io.KXmlParser`, не устройство |
| Отказ XML_VALIDATE → legacy Publication → повторная derived Publication | PASS; две сохранённые P0.6 пометки, positionV2, fontSize и байты reader.json/EPUB неизменны |
| Failed/redaction, lastUnifiedFailure, поздняя отмена, новая success | PASS |
| lint | PASS: 0 errors, 59 warnings |
| Прежний XML/DOM suite | PASS: 10 checks |
| Chromium unified: стык/якоря/Unicode/selection/8 reflows/EOF/links | PASS; desktop, Java callbacks stubbed |
| AndroidXmlOpenTest и прежний native navigator suite | Собраны, NOT_RUN |
| Honor code9 и конкретные EPUB пользователя | NOT_RUN |

Рабочий TTS, CSP, координаты, компоновка, иконка и SDK/зависимости сохранены. Code8 остаётся историческим FAIL открытия на Honor; code9 не объявляется принятым до новой проверки пользователя. [Минимальный чек-лист](HONOR_CHECKLIST.md).
