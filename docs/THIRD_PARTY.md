# Сторонние компоненты P0

| Компонент | Версия / источник | Лицензия |
|---|---|---|
| Readium Kotlin Toolkit shared / streamer / navigator | 3.4.0, tag commit 1b1f6b308a7b6f968b2bf1e66c84912879466f75, https://github.com/readium/kotlin-toolkit | BSD-3-Clause |
| EdgeToken (изолированный модуль `edge-protocol`) | Java-порт `drm.py`, https://github.com/rany2/edge-tts/tree/bd44c1d1aaebdc1db7f47456ee12431b42ec7cac | LGPL-3.0-or-later; это не MIT |
| OkHttp | 4.12.0, https://github.com/square/okhttp | Apache-2.0 |
| jsoup | 1.23.2, https://github.com/jhy/jsoup | MIT |
| AndroidX / Compose | точные прямые версии в Gradle, https://android.googlesource.com/platform/frameworks/support | Apache-2.0 |
| Kotlin / coroutines | Kotlin 2.4.20, зависимости Readium, https://github.com/JetBrains/kotlin | Apache-2.0 |
| jsdom, только проверка DOM в Node | 26.1.0, не входит в APK, https://github.com/jsdom/jsdom | MIT |

Полные тексты и уведомления включены в `app/src/main/assets/licenses`, в том числе Readium BSD, Apache-2.0, jsoup MIT, LGPL и GPL v3. Оригинальные уведомления upstream Edge сохранены в `edge-protocol/LICENSE`, GPL v3 — в `edge-protocol/COPYING`, изменения порта — в `edge-protocol/NOTICE`. Python-клиент edge-tts в приложение не встраивается. Протокол WebSocket реализован отдельным Kotlin-адаптером; портирован только алгоритм Sec-MS-GEC v2 в отдельном Java-модуле. Встроенных персональных ключей, Azure-аккаунта и прокси-сервера приложения нет. Публичный идентификатор протокола не является Azure subscription key.

## Пересборка и замена LGPL-библиотеки

Вместе с APK предоставлен полный исходный проект и Gradle Wrapper. В debug-сборке нет обфускации, минификации или технического запрета на изменение LGPL-библиотеки. Разрешается модификация этой библиотеки и обратная разработка для отладки её модификаций в соответствии с LGPL; дополнительных ограничений на эти действия не вводится.

1. Изменить `edge-protocol/src/main/java/com/fantadrom/edge/EdgeToken.java` либо заменить модуль, сохранив интерфейс `generate(long)` и `CLIENT`.
2. В Windows/PowerShell выполнить `.\gradlew.bat :edge-protocol:test :app:assembleDebug`.
3. Установить пересобранный APK. Для обновления существующей установки использовать тот же debug-ключ по инструкции README. Полная пересборка из поставленных исходников выполняет повторную линковку приложения с изменённой библиотекой; отдельного платного инструмента или сервера нет.

Readium используется как готовый движок; форка/переписывания движка нет. Из его документации использована идея декорации в поле, уведомление BSD включено. Код Readest не использован. Оригинальные материалы брендинга пользователя не перелицензируются этим документом.

Кандидат для отдельного решения при обязательной бесшовной прокрутке — foliate-js, https://github.com/johnfactotum/foliate-js, MIT (автор John Factotum, 2022). Его Android-интеграция и пригодность в P0 не проверялись и не обещаются.
