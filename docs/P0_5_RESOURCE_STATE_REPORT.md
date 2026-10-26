# P0.5 — ресурсная раскладка и диагностическая сборка

`0.1.5-p0`, versionCode **6**, applicationId `com.fantadrom.jjreader`. Основа и remote HEAD до изменений — `607b6d8af00f3124ce909d2214706d873d17ceea`; ветка `codex/jjreader-p0-feasibility`, Draft PR #1. P1–P5 и merge не выполнялись. **Позиция и Дмитрий остаются unresolved до трассы/сравнения Honor. Вся P0 не объявлена принятой.**

## Факты пользователя code 5

| Проверка Honor / Android 15 | Результат пользователя |
|---|---|
| Пометка x1 → поворот → пометка x2 → поворот | FAIL: возвращается x1; P0.4 фактически не устранил дефект |
| Landscape → свайп в соседний раздел | FAIL: новый ресурс становится узкой полосой |
| Шрифт/пометки после поворота, Home, блокировки, выгрузки | PASS |
| Светлана, подсветка/прокрутка, Пауза/Home/блокировка | PASS |
| Контрольная фраза без кеша, Дмитрий | HTTP101; config/SSML accepted; full name; turn.start=1, response=1, textMessages=2; binary/audio=0; SocketException за 4416 ms |
| Та же проверка, Светлана | HTTP101; audioBytes=28224; 40 непустых audio frames из 41 binary; synthesis success; close1000; playback completed |

40/41 не считается дефектом: пустое бинарное сообщение допустимо. protocolError=false означает отсутствие распознанной ошибки, а не согласие сервера синтезировать. Кеш, полное имя и общая недоступность сети не объявляются причиной. Прежние сетевые успехи Дмитрия code 3 остаются фактами. Эти результаты не проверяют code 6.

## Позиция: что установлено и чего пока нет

Android runner в среде недоступен; точная последовательность callback на Honor не воспроизведена. Прежние PASS через ReaderPort не подтверждают исправление на устройстве. В code 6 **не добавлена очередная неподтверждённая правка алгоритма позиции** и не ослаблены ready/hash/quote/range/ресурс-проверки. Сохранён приоритет последнего подтверждённого перехода P0.4, но достаточность этого алгоритма для реального navigator остаётся открытой.

На границе адаптера Readium 3.4.0 подтверждено: evaluateJavascript берёт currentReflowablePageFragment и ждёт его загрузки; currentLocator и завершённый переход DOM — разные наблюдения. Код Readium просмотрен, но одно это не устанавливает причину возвращения x1. Трасса нужна для первого реального расхождения между go, DOM, записью и новой Activity.

В существующей «Диагностике TTS» теперь общий экспорт:

- `reader`: одна последняя трасса schema 1, clientConfiguration=jj-p0.5-r1;
- `tts`: последняя TTS-сессия schema 4 (либо прежняя явно версионированная запись до нового запуска).

«Проверки» → «Начать новую трассу чтения» очищает только диагностическую трассу. Закрыть диалоги, выполнить один сценарий, затем «Диагностика TTS» → «Копировать». Никакого ADB или отправки данных приложением нет.

Трасса содержит максимум **256** событий, сохраняется атомарно в отдельном локальном файле и продолжается через новую Activity/процесс. Повторяющиеся одинаковые DOM/capture/commit/native/storage-наблюдения подавляются. Поля:

| События/поля | Назначение |
|---|---|
| CREATE/PAUSE/DESTROY, DIALOG OPEN/CLOSE | Activity lifecycle, диалоги диагностики/пометок/IME |
| activity / readerSession / store / document | Различить Activity, ReaderSession, экземпляры NoteStore и реальные DOM; документ и книга экспортируются как хеши ID |
| source / operation / revision, FREEZE/READY | Источник и принадлежность операции; старый operation виден рядом с текущим revision |
| INITIAL/STORE_LOAD | Начальный выбранный якорь, реально загруженный reader.json и locator index |
| GO с accepted | Запрос и принятие native перехода отдельно от подтверждения DOM |
| DOM target/observed/dom/document/accepted | Целевой индекс, currentLocator index, индекс фактического DOM URL, documentId и проверка совпадения; URL не экспортируется |
| RESTORE_REQUEST/ACK | Принятие JS restore и подтверждённая видимость в нужном документе |
| COMMIT / STORE_WRITE | Выбранная позиция и источник записи; синхронное повторное чтение фактического reader.json после AtomicFile.finishWrite |
| start/end, diskChapter/diskStart/diskBook, locatorChapter | Только числовые offsets/индексы и анонимный ID; обнаружить смесь главы x2 и locator x1 либо другой снимок/книгу |
| REJECT/reason | Отмена, stale revision, not-ready, отсутствие anchor/null, ошибка либо несовпадение записи/чтения; сообщения исключений не экспортируются |

Например, GO x2 без ACK/COMMIT указывает на незавершённый переход; COMMIT/STORE_WRITE x2, затем запись x1 другой Activity/store — на перезапись; диск x2, но INITIAL/DOM x1 — на расхождение загрузки/восстановления. До получения такой трассы эти варианты остаются гипотезами. Обновление не записывает смесь ресурсов специально ради прохождения проверки и не принудительно выставляет ready.

Запись NoteStore из Activity/ReaderSession/редактора отмечается отдельно. Чтение с диска выполняется после записи, а не из того же JSON в памяти. Имена/пути книг, href/URL, цитаты, текст/комментарии и stack/message отсутствуют в экспорте. Диагностика не меняет схему книг/пометок и не очищает их.

## Ширина: установленный дефект жизненного цикла и проверенное изменение

В code 5 правило ширины устанавливалось только `LocatorBridge.call` в одном document. Это не покрывало новый/предзагруженный документ до следующего вызова моста. В Chromium воспроизведено: без этого правила при 1000 CSS px viewport остаются body 640 px и p 480 px. Теперь правило помещается в **каждый HTML/XHTML rendition** в TransformingContainer/SafeHtml до инъекции Readium. Это не изменение исходного EPUB: его байты и внутренняя копия сохраняются.

Правило `html:root[style*=readium-scroll-on] body` имеет большую специфичность, чем scroll-правило Readium: max-width:none и padding-left/right:16px. Поэтому порядок последующего ReadiumCSS-after не возвращает 40rem. Style имеет один id, повторная трансформация идемпотентна. JS-мост больше не вставляет стиль; успех capture/restore вообще не требуется для ширины. Общий путь TransformingContainer обслуживает открытие, swipe, кнопки, пометки, восстановление и предзагрузку ресурсов.

Chromium на настоящих AAR assets Readium: c1 → c2 → c3 → c2 → c1 в landscape, в каждом новом документе `window.JJ` ещё undefined, один style, p=968 px при viewport=1000. Проверены font preference и возврат portrait=398 px при viewport=430. Сохранены выделение, reload/resize-якорь, синяя декорация и блокировка book script. Это настольные документы/переходы, **не Android swipe**.

Многодокументный тест также воспроизвёл CSP-блокировку встроенных AAR шрифтов AccessibleDfA/iAWriter из `readium/readium-css/fonts/`. Добавлен только этот trusted engine font path в font-src. EPUB networking/script-защита и TLS не отключались. Шрифты уже входят в Readium AAR с лицензиями.

## Голоса: диагностическое A/B, обычный маршрут сохранён

Первичные источники проверены заново:

- [Readest edgeTTS.ts, commit f1e1836d](https://github.com/readest/readest/blob/f1e1836d019b94061e7363a54bf27ea65a54c358/apps/readest-app/src/libs/edgeTTS.ts): публичный маршрут `api.msedgeservices.com/tts/cognitiveservices/websocket/v1`, публичный параметр Ocp-Apim-Subscription-Key, тот же public Edge token и Sec-MS-GEC version;
- [edge-tts constants.py, commit bd44c1d](https://github.com/rany2/edge-tts/blob/bd44c1d1aaebdc1db7f47456ee12431b42ec7cac/src/edge_tts/constants.py): прежний speech.platform.bing.com / TrustedClientToken и адрес каталога.

Каталог Microsoft и доступ к альтернативному адресу блокируются прокси Codex: CONNECT 403 / HTTP000. Каталог Name/ShortName/Locale не получен. Это ограничение облака, не вывод о сети пользователя или причине Дмитрия. Реальный A/B в Codex **NOT_RUN**.

Явный путь на устройстве: «Диагностика TTS» → «Проверки» → «Сравнить маршруты: 4 сетевых запроса». Перед запуском показаны число/назначение запросов и фиксированный текст. Выполняются последовательно:

1. current / Дмитрий;
2. current / Светлана;
3. readest / Дмитрий;
4. readest / Светлана.

На каждый вариант одна попытка, без кеша, prefetch, retry или автоподмены голоса. Ошибка одного варианта не скрывает остальные результаты. SSML, полный wire name, locale, prosody/audio/config, HTTP headers, token algorithm и timeout одинаковы; меняются только подтверждённый route и его публичное имя key parameter. Это контролируемое сравнение маршрута, а не копия всех изменений клиента Readest. Сохраняется тот же текст «Проверка голоса. Сегодня ясный день.».

**Production-путь не переключён**: обычная Светлана/Дмитрий используют прежний current маршрут и кеш/лимит 3 временных попыток. Альтернативный клиент не открывает соединение до явного сравнения; после решения экспериментальный путь надо убрать либо обоснованно принять отдельно. Пауза/Home/смена голоса/книги/новый старт отменяют проверку без автопродолжения. Новые URL/секреты пользователя, Python, платный Azure/API, fallback-лестница не добавлены. Лицензии сохранены.

В schema 4 добавлены route=current/readest, ssmlProfile=full-ru-default-v1, clientConfiguration=jj-p0.5-r1, purpose=routeComparison. Для response учитываются разрешённые status/error поля: responseFormat=json/empty/other, responseHasStatus, responseStatus=success/error/other/absent, responseHasError и прежняя безопасная protocolCategory. Сохраняются все text/binary counts, разрешённые Path, непустые audio frames/bytes и отдельные synth/playback timings. Неизвестное содержимое не экспортируется. Отсутствие распознанной ошибки и status=success сами по себе не доказывают успешный синтез: нужны audio/turn.end. Локальная отмена маркируется CANCELLED; внешний SocketException остаётся socket_error без догадок о причине.

## Автоматические проверки и границы

| Проверка | Результат code 6 |
|---|---|
| assembleDebug / assembleDebugAndroidTest | PASS |
| JVM/Robolectric/protocol | PASS, 51 тест, 0 failures/errors |
| lintDebug | PASS, 0 errors / 54 warnings, предупреждения не скрыты |
| XML/DOM | PASS, 10 проверок |
| Desktop Chromium | PASS, 8 сценариев, включая пять отдельных загрузок документов без моста |
| Совместимая подпись/applicationId/versionCode 6 | PASS |
| Android navigator swipe/orientation/recreate/process restart | NOT_RUN |
| Реальная причина позиции Honor | UNRESOLVED, ожидается трасса |
| Реальный каталог/синтез Microsoft/A/B | BLOCKED/NOT_RUN в Codex |
| Реальное устранение отказа Дмитрия | UNRESOLVED, ожидается A/B Honor |

Новые локальные тесты проверяют долговечность/ограничение/приватность трассы, свежий AtomicFile read-back, идемпотентную XHTML-инъекцию CSS, безопасный response-summary и четыре последовательных запроса mock-сервисам с правильными key parameters/одинаковым SSML-профилем без fallback. Число тестов не является критерием приёмки на устройстве.

Исполняемые Android-регрессии: прежняя точная цепочка UI-пометок x1 → rotate → x2 → rotate с задержками 0/2500 ms и recreate; новая настоящая swipe-последовательность через три ресурса с raw JS-геометрией/DOM URL; `crossResourceProcessRestartProbe` с настоящими UI-пометками и двумя фазами, свежим NoteStore и native context x2. Для последней подготовлен `tools/android_process_restart.ps1 -CrossResource`: force-stop между prepare/verify, новый PID, без очистки данных. При FAIL теста позиции копируемая числовая трасса включается в сообщение проверки. Эти тесты собраны, а не запущены в облаке. Обычному пользователю ADB не нужен.

Обновление поверх прежней облачной версии, без очистки/переимпорта. Сертификат SHA256 `06ba3132107a954a4640ad60a29138b297449a6172bb763b8832293dc1c727ef`, ключ в пакет не включается. Компоновка кнопок, непрерывное чтение/выделение между ресурсами и архитектурное решение перед P1 не менялись.
