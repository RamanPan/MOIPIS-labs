# Order details and process graph implementation plan

**Goal:** полная карточка заказа с локальным Cytoscape, командами и историей.
**Spec:** `docs/CODEX_UI_WORKFLOW_B3.md` и текущий запрос пользователя.
**Architecture:** app.js сохраняет список/создание/роутинг; order-detail.js
управляет карточкой, graph.js — только визуализацией ProcessGraphDto.
Все изменения выполняет существующий WorkflowEngine через HTTP.
**Stack:** Java 21, Maven/Javalin 7.2.4, Cytoscape WebJar 3.34.1, vanilla JS.

## Tasks

1. [x] RED: WebResourceTest проверяет локальный JS из WebJar и graph/detail modules.
   Подключить Maven dependency и enableWebjars, проверить реальный путь в JAR.
2. [x] Реализовать граф: все nodes/edges из /process, generic layout, active,
   initial/final/visited, zoom/pan/fit, информация по выбору узла, текстовый fallback.
3. [x] Реализовать карточку: /object + /process + /access + /history, выбранный
   пользователь, динамические действия, сериализация команд через busy-флаг,
   PATCH одного параметра по canEditParameters/ editableParameterIds.
4. [x] После успеха/отказа синхронизировать все данные, защищаться от старых
   ответов при навигации/смене пользователя, сохранять ввод при отказе редактирования.
5. [x] Browser smoke: все 10 состояний/19 дуг Б.3, возвраты/отложения/финал,
   false condition, роли, история; другой шаблон с петлёй, параллельными условными
   дугами и выборочным редактированием; fallback и мобильная ширина.
6. [x] Maven clean test package, проверка обоих собранных JAR, README/report.

## Constraints and review focus

Никаких enum/массивов статусов, ДНФ в JS или прямого изменения stateId.
Клик узла только показывает данные. Отмена fetch не отменяет принятую команду.
POST/PATCH не повторяются автоматически. Доступ скрывается при сбое его загрузки.
Формы сохраняют точные числовые токены и null. История кликов не источник состояния.
Существующий движок на этом этапе не изменяется. Работа в текущем workspace.

## Execution ledger

- RED: `.tools/order-process-red.log`: локальный Cytoscape возвращал 404.
- GREEN: `.tools/order-process-resources.log`: ресурс и три новых модуля
  возвращают 200 с JavaScript MIME; проверено содержимое dependency JAR.
- Generic layout: force layout был заменён на breadthfirst с поворотом
  слева направо после проверки реального screenshot; фиксированных координат
  или идентификаторов состояний Б.3 в визуализации нет.
- Browser PASS: `.tools/order-process-browser/result.json`, 10/19 элементов Б.3,
  24 записи истории, все состояния, возвраты, отложения, терминальность,
  блокировка повторной команды и устаревших прав, другой шаблон 3/6, петля,
  условные параллельные дуги, выборочное редактирование и fallback.
- Regression PASS: `.tools/order-process-dashboard-regression.log`.
- Review: самостоятельно проверены async-generation guards, запрет повторных
  команд, очистка доступа при ошибке, безопасный DOM, null/exact numbers и
  освобождение Cytoscape при навигации. Отдельный агент недоступен в текущем
  аккаунте (известное ограничение предыдущего этапа).
- Итог: `.tools/order-process-final-build.log`, clean test package — BUILD SUCCESS,
  375 тестовых случаев, failures/errors/skipped=0. В application JAR проверены
  все семь UI-ресурсов, в WebJar — фактический JS (435503 байта). Сервер остановлен.
