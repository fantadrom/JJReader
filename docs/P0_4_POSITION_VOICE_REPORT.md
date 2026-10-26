# P0.4 — актуальная позиция и протокол голосов

Версия `0.1.4-p0`, versionCode **5**, applicationId `com.fantadrom.jjreader`. Основа и remote HEAD перед работой — `19203bbe6e313a946e7e8dae828dad3c5e77c067`. Ветка `codex/jjreader-p0-feasibility`, Draft PR #1. Только P0; merge и P1–P5 не выполняются.

## Результаты пользователя на code 4

Honor: PASS — ширина landscape, сохранение шрифта, Светлана с первого запуска с подсветкой/автопрокруткой, Пауза/Home/блокировка и ручное продолжение. FAIL — последовательность пометка x1 → поворот → пометка x2 → поворот возвращала x1. Эти результаты не являются проверкой code 5.

Для Дмитрия session `1fe5dcc9-81be-46ee-85b0-18a6733743f8`, generation 9, block 1: три network-попытки HTTP 101, openedMs 1164/1084/1155, durationMs 4146/3984/3969, audioBytes/audioFrames=0, IO_SocketException. Handshake прошёл; распознанного аудио нет. Прежняя schema 2 не считала служебные сообщения, поэтому не позволяет заключить, что сервер вообще ничего не прислал. Причина текущего отказа не установлена. Два сетевых успеха Дмитрия в code 3 с HTTP101/close1000 остаются принятыми фактами, а не объявляются кешем.

## Почему могла оставаться старая позиция

Установленный дефект кода: `showNotes` переходил к ресурсу и вызывал `restore(note)`, но `restore` менял только scroll/ready. Он не записывал подтверждённую цель. Единственная запись зависела от следующего `capture()` — сразу после restore либо каждые 350 ms. Если viewport не возвращал anchor, `positionV2` и `locator` продолжали содержать x1. `onPause` повторял тот же необязательный capture, затем сохранял старый JSON. Новая Activity строила native navigator из этого locator и восстанавливала x1. Это первое место потери новой цели: между подтверждением restore(x2) и необязательным capture/persist, а не допустимая ошибка строки.

Второй найденный дефект: restore проверял DOM/ресурс, но не revision после каждого ожидания. Запоздалое начальное восстановление могло снимать freeze и допускать запись устаревшего якоря. Хранились также первоначальные снимки position/locator. Контролируемые регрессии проверяют обе уязвимости; точное чередование callback на конкретном Honor без Android runner не измерено.

Теперь `ReaderSession.navigateTo` владеет одним revision от native go(resource) до подтверждения видимости и синхронной записи целевого якоря через NoteStore/AtomicFile. Сохраняются одновременно positionV2 и locator. Только затем разрешается viewport-уточнение; null/no-anchor не возвращает старое состояние. Снимок первоначальной позиции удалён: restoreInitial читает актуальные данные. Проверяются отмена/revision после await, ресурс и documentId, hash/quote/range/bookId перед записью. Старая операция не снимает freeze новой и не коммитит старый anchor. При смене книги/уничтожении сессия инвалидируется; переходы отменяются на onPause, шрифтовые/IME-восстановления также привязаны к revision. Точность остаётся приблизительной по тексту.

Проверки на том же ReaderSession через узкую native-границу ReaderPort:

- x1 → пересоздание → x2 → пересоздание, 3 цикла для задержки 0 и 2500 ms, разные ресурсы;
- viewport намеренно всегда возвращает null: подтверждённая цель уже записана до поворота;
- x2 читается из нового настоящего NoteStore/AtomicFile без периодического capture;
- задержанные initial restore и capture x1 приходят после перехода x2, но не меняют ready/данные x2;
- неверный hash и неподтверждённая/инвалидированная операция не сохраняются.

Нативный ReaderRuntimeTest создаёт две пометки настоящими long-press/ActionMode в разных ресурсах, переходит через список и выполняет несколько циклов x1 → orientation → x2 → orientation → recreate при задержках 0/2500 ms. Он собран, **NOT_RUN**; JVM-модель не подтверждает поведение Android-поворота. Обычный перезапуск после x2 включён в ручной чек-лист.

## Сравнение первичных клиентов

Получены и сверены с commit-ссылками:

- edge-tts [`data_classes.py`](https://github.com/rany2/edge-tts/blob/bd44c1d1aaebdc1db7f47456ee12431b42ec7cac/src/edge_tts/data_classes.py) и [`communicate.py`](https://github.com/rany2/edge-tts/blob/bd44c1d1aaebdc1db7f47456ee12431b42ec7cac/src/edge_tts/communicate.py).
- Readest [`edgeTTS.ts`](https://github.com/readest/readest/blob/32dc7856f1cd2b2c1b3abb2347b8221193b42b73/apps/readest-app/src/libs/edgeTTS.ts) и [`ssml.ts`](https://github.com/readest/readest/blob/32dc7856f1cd2b2c1b3abb2347b8221193b42b73/apps/readest-app/src/utils/ssml.ts).

| Область | Сверка и решение |
|---|---|
| Voice Name | edge-tts TTSConfig явно преобразует ShortName в полное Microsoft Server Speech имя. JJReader ранее отправлял short. Теперь отдельные wireName для обоих голосов: `Microsoft Server Speech Text to Speech Voice (ru-RU, DmitryNeural)` / `(ru-RU, SvetlanaNeural)`. ShortName остаётся UI/cache ID. |
| Доступность голосов | Актуальный сервисный список Name/ShortName/Locale **не получен**: CONNECT proxy 403 (HTTP 000). Это ограничение сети Codex. Статический список Readest содержит ru-RU ID, но не доказывает работу сервиса. Полные имена получены из подтверждённого алгоритма TTSConfig, а не из успешно загруженного каталога Microsoft. |
| SSML/XML | Независимый компактный formatter сохраняет namespace/prosody, экранирует XML и заменяет недопустимые code points пробелами (включая C0, unpaired surrogate, FFFE/FFFF), сохраняя русский Unicode и emoji. edge-tts тоже заменяет несовместимые C0 пробелами и XML-экранирует текст. |
| Locale | edge-tts использует en-US на speak, фактический voice задаётся полным именем; Readest передаёт lang. JJReader сохраняет соответствующий голосам ru-RU, не меняет это одновременно с wireName. |
| Headers/timestamp | Config: X-Timestamp, Content-Type, Path:speech.config; SSML: X-RequestId, Content-Type, X-Timestamp, Path:ssml. UTC JS-style timestamp и суффикс Z на SSML соответствуют edge-tts (там специально отмечен Edge bug); они сохранены. Readest использует JS Date без дополнительного Z. Порядок/пробел после двоеточия различаются, причин менять рабочую структуру не установлено. |
| Audio/config | Совпадает audio-24khz-48kbitrate-mono-mp3; metadata обе false в JJReader, edge-tts выбирает одну границу, Readest запрашивает wordBoundary. P0 не использует metadata для подсветки, config сохранён. |
| Endpoint/auth | Текущий Readest уже использует другой endpoint и параметр ключа; это документированное отличие его клиента, не доказательство необходимости миграции JJReader. В Honor handshake проходит, Светлана работает. Endpoint, токен, Sec-MS-GEC, HTTP headers JJReader не менялись; перебора endpoint/fallback нет. |
| Messages | edge-tts различает response/turn.start/audio.metadata/turn.end и проверяет binary headers; Readest тоже разбирает текстовые Path. JJReader раньше молча игнорировал почти весь текст. Теперь считает все полученные text/binary сообщения, разрешённые Path и безопасно выявляет service error. Бинарный не-audio кадр учитывается, но не попадает в аудиобуфер. |

Полный клиент/код Readest не переносился. Форматирование и маленький allow-list parser написаны независимо по структуре протокола; существующие LGPL-лицензии edge-protocol и THIRD_PARTY сохранены.

**Это исправление подтверждённого отличия запроса, но ещё не доказанное устранение отказа Дмитрия.** Short/full A/B и лестница fallback не добавлены: без доступа к Microsoft они не дадут результата. Итоговый wire-формат выбран по первичному клиенту; runtime-результат ожидается от Honor. Нет оснований объявлять голос неподдерживаемым или приписывать текущий SocketException сети пользователя/блокировке/Microsoft.

## Проверка голосов и диагностика schema 3

В существующем диалоге «Диагностика TTS» добавлена кнопка «Проверить голоса». Только явное нажатие запускает фиксированную фразу **«Проверка голоса. Сегодня ясный день.»**: Дмитрий, затем Светлана; одна попытка на голос, без кеша, prefetch или автоматического fallback. Успешный синтез воспроизводится, ошибка первого голоса не скрывает проверку второго. Оба результата остаются в одной копируемой сессии purpose=voiceCheck. Текст показан перед действием, сообщения книги/комментарии в этой проверке не используются. Обычная озвучка сохраняет кеш, одну предзагрузку и прежний максимум 3 попыток для временных ошибок. NO_AUDIO и SERVER_PROTOCOL_ERROR не повторяются вслепую.

Пауза/Home/блокировка/смена книги/голоса/новый старт отменяют проверку и запрос. Позднее finally старой проверки не освобождает плеер нового поколения. MediaPlayer освобождается после каждой фразы. После отмены нет автопродолжения; обычную очередь надо запускать явно.

Новые безопасные поля: wireNameKind=full, configSendAccepted/ssmlSendAccepted (только принятие в очередь отправки!), textMessages/binaryMessages, счётчики paths: turn.start/response/audio/turn.end/audio.metadata/other, protocolError и protocolCategory. Сохранены source, попытки, session/generation/block, аудиобайты/кадры и раздельные synth/playback timings.

Для service error анализируются только известные JSON/header позиции: error/error.code/errorCode/status/context.synthesis.status и X-ErrorCode/Error-Code/Status. Наружу выходят только категории invalid_request/authorization/voice_not_found/service_unavailable/rate_limited/server_error/server_error_other. Неизвестный error code превращается в server_error_other; неизвестный Path — other. Неизвестный новый формат сообщения может остаться нераспознанным, но будет учтён счётчиками. Raw message/body/stack/SSML/URL/token/cookies/цитаты не сохраняются. Старый JSON schema 1/2 остаётся читаемым/копируемым до новой сессии; версия явно указана. Диагностика локальная, автоматической отправки нет.

## Проверки и ограничения

| Проверка | Итог |
|---|---|
| assembleDebug / assembleDebugAndroidTest | PASS |
| App JVM/Robolectric + edge-protocol | PASS, 46 тестов, 0 failures/errors |
| lintDebug | PASS, 0 errors / 54 warnings, предупреждения не скрыты |
| Node XML/DOM | PASS, 10 проверок |
| Desktop Chromium на Readium AAR CSS/JS | PASS, 7 сценариев |
| Сертификат/applicationId/code 5 | PASS, прежняя подпись |
| Android/Honor code 5, реальные orientation/MediaPlayer/process restart | NOT_RUN |
| Каталог голосов Microsoft в Codex | BLOCKED, CONNECT 403 |
| Реальный синтез Microsoft / Дмитрий на code 5 | NOT_RUN |

Новые тесты: точные полные имена обоих голосов и валидный XML с русским Unicode/XML-символами; сервисные сообщения без аудио/ноль binary; non-audio binary; разрешённая категория server error без утечки payload; настоящее локальное WS-аудио; send acceptance и фактический outbound SSML; последовательная uncached-проверка с двумя результатами и отмена без второго запроса. Сохранились регрессии retry, повреждённых буферов/кеша, prefetch, generation, шрифта/пометок/XHTML/CSP/ширины. MockWebServer и JVM/Chromium не подтверждают Microsoft или Honor.

Схема книг/пометок не менялась. Обновлять поверх прежней версии без удаления/очистки/переимпорта. SHA256 сертификата: `06ba3132107a954a4640ad60a29138b297449a6172bb763b8832293dc1c727ef`; ключ не экспортируется. Непрерывное чтение/выделение через ресурсы остаётся отдельным открытым вопросом перед P1.
