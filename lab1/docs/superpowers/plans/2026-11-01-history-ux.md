# History timeline and UX implementation plan

**Goal:** отдельная вкладка истории и согласованное поведение карточки.
**Spec:** текущий запрос пользователя и `docs/CODEX_UI_WORKFLOW_B3.md`.
**Architecture:** history.js форматирует только реальные HistoryEntryDto;
order-detail.js управляет вкладками, загрузкой, доступом и уведомлениями.
**Constraints:** без изменений Java/бизнес-логики, CDN, шрифтов, Node/npm.

## Tasks

1. [x] RED: HTTP проверка нового history.js, browser assertions для вкладки,
   CREATE snapshot, ACTION old/new/action, UPDATE old/new, date/user/type.
2. [x] Добавить timeline-вкладку с ARIA tab semantics и keyboard navigation,
   компактный responsive layout и отдельные состояния empty/error/loading.
3. [x] Смена пользователя обновляет только /access, сохраняет объект, граф,
   timeline, раскрытые записи и текущую вкладку. Старые ответы игнорируются.
4. [x] Coalesce повторной загрузки, disable повторной операции, уведомления
   loading/success/error и восстановление keyboard focus после команд.
5. [x] Реальный Chrome: данные timeline совпадают с API, отказ без новых
   записей, сохранность при смене ролей, повторные refresh, ошибка истории,
   пустая история, размеры 1366/1024/390/320. Регрессия предыдущих сценариев.
6. [x] Полный Maven clean test package, JAR/static checks, README/report.

## Review focus

Переименование заказа не меняет CREATE snapshot; недоступная история не
считается пустой; отказ не создаёт событие; граф не создаётся в скрытой вкладке;
успех commit отличается от ошибки последующей загрузки; точные значения и null.
Независимый агент недоступен в текущем аккаунте; review выполняется вручную.

## Verification ledger

- RED: `/js/history.js` возвращал 404 (`.tools/history-ux-red.log`).
  GREEN: WebResourceTest — 3/3 (`.tools/history-ux-resources.log`).
- Chrome history suite: PASS, 8 реальных записей; id/timestamp/user/operation
  сравниваются с API. Проверены исходный CREATE snapshot, значения UPDATE,
  состояния и действие ACTION, отсутствие вымышленной причины, сохранность
  DOM/вкладки/графа при смене пользователя, один GET /access, aria/focus,
  загрузка/отказ/успех, повторные команды/refresh, сбой истории и реальный [].
- Регрессии order_process_smoke и dashboard_smoke — PASS, включая все
  10 состояний / 19 переходов Б.3 и 3/6 другого шаблона, возвраты, роли,
  финальное состояние, безопасный текст, точные числа и сохранность черновика.
- `mvn -o clean test package`, Maven 3.9.9 / JDK 21.0.10: BUILD SUCCESS,
  375 случаев, failures/errors/skipped=0 (`.tools/history-ux-final-build.log`).
  Подтверждено также суммированием Surefire XML; все 8 UI-ресурсов есть в JAR,
  тестовый launcher отсутствует в production JAR.
- Снимки timeline просмотрены вручную; ширины 1366/1024/390/320 проверены
  браузером без горизонтального переполнения и внешних ресурсов.
- Ручной review: все записи строятся textContent; только API создаёт события;
  недоступная история сохраняется с предупреждением; stale reads отменяются;
  post-commit read всегда новый; граф не строится в скрытом контейнере.
  Production Java / бизнес-логика на этом этапе не менялись.

Первый браузерный запуск по ошибке обратился к уже работающему IDE-серверу
на 7070 и создал один тестовый заказ `UX-RENAMED-3967ab3` (исходный номер
`UX-CREATED-3dec791`). Прежние заказы не изменялись, созданный заказ не удалён.
Далее использован только отдельный store с сервером на свободном порту.
В BrowserTestServer и всех трёх сценариях добавлены проверки fixture;
мутирующие тесты теперь отказываются работать на 7070 без запуска операций.
