# Workflow web backend implementation plan

**Goal:** локальный HTTP backend без HTML, с существующей очередью и JSON-store.
**Architecture:** Javalin 7.2.4 → DTO/controller → WorkflowEngine; читающий адаптер
для списков и подписей. Новый getAccess использует общие проверки ядра.
**Spec:** `docs/CODEX_UI_WORKFLOW_B3.md`, `docs/ui-integration-plan.md` и текущий запрос.
**Tech Stack:** Java 21, Maven, Jackson 2.20.1, JUnit 5, Javalin 7.2.4.

## Constraints

- Только loopback 127.0.0.1:7070 в WebApplication; port 0 в тестах.
- Никаких HTML, Spring, Node.js, новых хранилищ или правил Б.3 в контроллере.
- Ответ команды после commit; GET access не вычисляет ДНФ.
- `demo/web/store.json` по умолчанию; существующие файлы не сбрасываются.
- Существующие пользовательские изменения и main demo сохранить; без коммитов.

## Tasks

1. [x] Добавить WorkflowAccessTest: доступ без вычисления условий, чужая роль,
   terminal, выборочное редактирование без исходящих действий. Получить RED.
   Добавить getAccess/WorkflowAccess и общую политику; сохранить порядок ошибок.
   Проверить существующие engine/scenario tests.
2. [x] Добавить HTTP integration tests на реальном FileRepository @TempDir:
   справочники, список, создание, process/access, команды, история, ошибки,
   сохранность при отказе и перезапуске. Получить RED. Добавить Javalin,
   DTO/controller/query adapter/error mapper/server factory, прогнать тесты.
3. [x] Проверить отложенную запись и конкурентные HTTP-команды через latches;
   проверить static resource из test classpath и локальный bind.
   Добавить WebApplication, профиль web, lifecycle и конфигурацию пути.
4. [x] Выполнить clean test, dependency:tree и реальный запуск main на 7070.
   Сохранить настоящий HTTP request/response, дополнить README и docs/report.
   Провести независимое review и устранить подтверждённые проблемы.

## Review focus

Пропущенный value против null; точность decimal/больших целых; сохранённый
templateId после копирования; отказ atomic move без раннего успеха; остановка
при принятых командах. Каждый сценарий проверяется тестами соответствующего слоя.

Решение: продолжаем в предоставленном workspace с имеющимися незафиксированными
документами; отдельную ветку/worktree и коммиты этот этап не создаёт.

## Execution ledger

- RED: отсутствующий getAccess — `.tools/web-access-red.log`.
- RED: отсутствующий WebServerFactory — `.tools/web-http-red.log`.
- RED: отсутствующий WebApplication — `.tools/web-lifecycle-red.log`.
- GREEN: 88 целевых случаев — `.tools/web-backend-focused.log`.
- Дополнительные проверки выявили coercion числового userId в строку;
  явно запрещён Textual coercion в HTTP mapper. Для неоднозначности тестовой
  ДНФ исправлен входной fixture: quantity=100 вместо отсутствующего значения.
- Полный `clean test`: 373 случая, 0 failures/errors/skipped,
  `.tools/web-backend-clean-test.log`. Из них 349 существующих и 24 новых.
- Независимое read-only review блокирующих замечаний не выявило.
  Добавлены проверки graceful stop и отказа запуска на занятом порту.
- Cytoscape/WebJar не подключается в текущем backend-этапе: нет HTML/JS.
- Dependency tree: Javalin 7.2.4, Jackson BOM 2.20.1 (annotations 2.20),
  SLF4J api/simple 2.0.20, JUnit 5.13.4; Jackson 3 и Spring отсутствуют.
  Лог: `.tools/web-dependency-tree.log`.
- Smoke: профиль web запущен с `.tools/web-smoke/store.json`; сокет
  слушает только 127.0.0.1:7070. GET objects → 200 и пять объектов;
  POST complete → 200 registration → composition_approval;
  GET process → standard_order_supply, 10 состояний, 19 переходов.
  Ответ сохранён в `.tools/web-smoke/action-response.json`; сервер остановлен.
