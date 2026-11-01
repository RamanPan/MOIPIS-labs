# План интеграции локального web-интерфейса WorkflowEngine

Дата аудита: 2026-11-01. Статус: предложение для следующего этапа.
В этом этапе добавлен только этот документ; Java-код, pom.xml, seed и UI не изменялись.

## 1. Основания и границы проверки

Прочитаны [ТЗ для UI](CODEX_UI_WORKFLOW_B3.md), [README](../README.md),
[pom.xml](../pom.xml), [архитектура](architecture.md), [отчёт](report.md),
[описание проверок](testing.md), [демонстрационные заказы](../orders.md),
исходники компонентов и существующие тесты.

Файлы `.doc`/`.docx`, включая игнорируемые файлы, в корне Git-репозитория
`MOIPIS-labs` и его подкаталогах не найдены. Поэтому исходное задание 3-1-1
и рисунок Б.3 из Word непосредственно не проверены. Десять состояний и
девятнадцать переходов подтверждены по seed и тестам, но это не независимая
сверка с методичкой. При появлении оригинала сверить рисунок с seed до изменения
конфигурации; UI должен показывать фактический сохранённый шаблон.

Пользователь запросил аудит и проектирование. Установка зависимостей в проект,
создание HTTP-сервера, страниц и новых тестов относятся к следующему этапу.

## 2. Текущая архитектура

Один Maven-артефакт `ru.moipis:workflow-engine:1.0-SNAPSHOT`, обычный JAR,
без Maven-подмодулей и `module-info.java`. Базовый пакет — **`ru.moipis.workflow`**,
а не пример `ru.leti.workflow` из UI-ТЗ. `maven.compiler.release=21`.
Jackson BOM/Databind/JavaTime — 2.20.1; JUnit Jupiter — 5.13.4;
Surefire — 3.5.4; Compiler Plugin — 3.14.1; Exec Plugin — 3.6.4.
Текущая точка входа Exec Plugin — `demo.DemoApplication`.

```text
src/main/java/ru/moipis/workflow/
  catalog/       CatalogService, модели классов, параметров, пользователей и ролей
  template/      TemplateCatalogService, модели шаблонов и политик редактирования
  condition/     ConditionEvaluator, DefaultConditionEvaluator, предикаты и ДНФ
  statemachine/  StateMachine, DefaultStateMachine, очередь, hooks, factory
  engine/        WorkflowEngine, DefaultWorkflowEngine, ObjectRuntime, модели, ошибки
  repository/    Repository, StoreData, ObjectChange, file/FileRepository
  demo/          DemoApplication
src/main/resources/seed/initial-data.json
src/test/java/ru/moipis/workflow/  14 тестовых классов и WorkflowFixture
src/test/resources/templates/    3 дополнительных тестовых шаблона
```

Это небольшой монолит с пакетами по компонентам и интерфейсом хранилища.
Движок организует бизнес-операции; универсальный автомат исполняет события;
конкретные правила Б.3 описаны данными. HTTP, контроллеров, transport DTO,
OpenAPI и `public/` сейчас нет. `Catalog` — отдельный интерфейс поиска/сохранения
определений, **его реализации нет**, и `CatalogService` его не реализует.
Нельзя проектировать интеграцию так, будто `Catalog.findUser()` уже работает.

Сильные стороны: готовые асинхронные команды, единый путь записи, изоляция
снимков, внедряемые `Clock` и `AtomicFileMover`, тесты отказов и перезапуска.
Модели находятся у компонентов-владельцев, поэтому на уровне укрупнённых пакетов
есть обратные зависимости: например, `ConditionContext.from(BusinessObject)`
связывает модели condition и engine. Это не основание переносить модели или
переписывать ядро ради UI; приоритет такой реорганизации низкий.

Вспомогательные скрипты skills создали отчёты зависимостей, concurrency hotspots
и API context. Для чистоты рабочего дерева они перенесены в игнорируемый
`.tools/ui-audit/`. Скрипт зависимостей объединяет подпакеты и учитывает тесты,
поэтому его cycle hints не следует считать доказательством циклов между сервисами.
Выводы ниже основаны на чтении production-кода.

## 3. Реальные публичные интерфейсы

Пути в этом разделе относительны к `src/main/java/ru/moipis/workflow/`.
Ниже перечислены существующие методы, не предполагаемый API из задания.

### WorkflowEngine

`engine/WorkflowEngine.java` → `engine/DefaultWorkflowEngine.java`.

```java
CompletableFuture<BusinessObject> createObject(
    String userId, String classId, Map<String, JsonNode> parameters);
CompletableFuture<EventResult> executeAction(
    String userId, String objectId, String actionId);
CompletableFuture<EventResult> updateParameter(
    String userId, String objectId, String parameterId, JsonNode value);
BusinessObject getObject(String objectId);
List<String> getAvailableActions(String userId, String objectId);
List<HistoryEntry> getHistory(String objectId);
void registerHandler(String actionId, EventHandler handler);
void close();
```

Конструкторы реализации:

```java
DefaultWorkflowEngine(Repository repository) throws IOException;
DefaultWorkflowEngine(CatalogService catalog, TemplateCatalogService templates,
    ConditionEvaluator evaluator, Repository repository, StateMachineFactory factory);
```

`EventResult` содержит `previousStateId`, `currentStateId`, `handlerResult`.
`getAvailableActions` возвращает уникальные **id**, учитывает роли и текущее
состояние, не вычисляет предикаты. Нет `performAction`, `listObjects`,
`getProcess`, `canEditParameters` или `getAccess`.

### StateMachine

`statemachine/StateMachine.java` → `statemachine/DefaultStateMachine.java`.

```java
void start();
CompletableFuture<EventResult> send(Event event);
void stop();
StateMachineLifecycle getLifecycle();
StateDefinition getCurrentState();
void addState(StateDefinition state);
void registerHandler(String stateId, String eventType, EventHandler handler);
void setExecutionHooks(EventPreparer preparer, EventCommitter committer);
void addTransition(TransitionDefinition transition);
List<TransitionDefinition> getTransitions(String stateId, String eventType);
EventProcessingResult processEvent(Event event);
// default-методы интерфейса:
void close();
String currentStateId();
void registerState(String stateId);
void registerTransition(String from, String eventType, String to);
```

У `DefaultStateMachine` четыре публичных конструктора: с `String initialStateId`
или `StateDefinition initialState`, каждый также имеет вариант с
`BiPredicate<DnfCondition, Event> conditionEvaluator`.
`processEvent` — синхронный wrapper `send(...).join()`, запрещённый на своём worker.
Web-команды должны вызывать WorkflowEngine, не StateMachine напрямую.

### CatalogService

`catalog/CatalogService.java`, final-класс без отдельной реализации:

```java
CatalogService(Collection<ObjectClassDefinition> definitions);
ObjectClassDefinition getClass(String classId);
Map<String, ParameterDefinition> getEffectiveParameters(String classId);
boolean isInstanceOf(String classId, String expectedClassId);
List<ObjectClassDefinition> getAncestors(String classId);
```

Нет перечисления всех классов, пользователей и ролей. Списки доступны в
`Repository.load().classes()/users()/roles()`. Каталог классов фиксируется при
создании сервиса; редактор классов и live reload для UI не предлагаются.

### TemplateCatalogService

`template/TemplateCatalogService.java`, final-класс:

```java
TemplateCatalogService(CatalogService classes, Repository repository) throws IOException;
ProcessTemplate getTemplate(String templateId) throws IOException;
ProcessTemplate resolveTemplate(String classId) throws IOException;
ProcessTemplate copyTemplate(String sourceId, String targetClassId,
    String newTemplateId) throws IOException;
List<ProcessTemplate> getTemplates() throws IOException;
StateMachineConfiguration prepareStateMachineConfiguration(String classId) throws IOException;
```

Поиск — собственный шаблон, затем ближайший предок. Существующие объекты
привязаны к сохранённому `templateId`; для них нельзя каждый раз безусловно
вызывать `resolveTemplate(classId)`. Новая собственная копия класса не должна
менять граф ранее созданных объектов.

### ConditionEvaluator

`condition/ConditionEvaluator.java` → `condition/DefaultConditionEvaluator.java`:

```java
boolean evaluate(DnfCondition condition,
    Map<String, PredicateDefinition> predicates, ConditionContext context);
boolean evaluatePredicate(PredicateDefinition predicate, ConditionContext context);
// Конструктор реализации:
DefaultConditionEvaluator(CatalogService classes);
```

Поддерживаются `filled`, `=`, `!=`, `<`, `<=`, `>`, `>=`, `in_range`, проверка
принадлежности классу, AND/OR/NOT. Эффективный диапазон выбирается по реальному
классу объекта. Ноль и пустая строка считаются заполненными; null/отсутствие — нет.
HTTP-адаптер и JavaScript не должны вычислять эти условия, в том числе для
фильтрации кнопок доступных действий.

### Repository / FileRepository

`repository/Repository.java` → `repository/file/FileRepository.java`:

```java
StoreData loadOrInitialize() throws IOException;
StoreData load() throws IOException;
void save(StoreData data) throws IOException;
StoreData update(UnaryOperator<StoreData> change) throws IOException;
HistoryEntry commit(ObjectChange change) throws IOException;
// Конструкторы FileRepository:
FileRepository(); // data/store.json
FileRepository(Path storePath, Clock clock);
FileRepository(Path storePath, Clock clock, AtomicFileMover mover);
```

Repository не реализует AutoCloseable; вызывать несуществующий `repository.close()`
не нужно. `load()` возвращает независимый снимок. Web-адаптеру допустимо чтение
через этот интерфейс, но `save/update/commit` не являются путём HTTP-изменений.

### История

`HistoryService` **не найден**. Его обязанности уже выполняют
`WorkflowEngine.getHistory(objectId)` и атомарный `Repository.commit(ObjectChange)`.
Новый пишущий сервис истории не нужен.

`HistoryEntry` = `(id, objectId, userId, Instant timestamp, operation, JsonNode details)`.
Операции и поля details:

| operation | Содержимое details |
| --- | --- |
| CREATE | `initialState`, `parameters` |
| ACTION | `actionId`, `oldState`, `newState` |
| UPDATE_PARAMETER | `parameterId`, `oldValue`, `newValue` |

В `BusinessObject` есть только `id`, `classId`, `stateId`, `parameters`, `templateId`.
Отдельных `name`, `createdAt`, `updatedAt`, версии объекта нет.

## 4. Очередь, futures и момент фиксации

1. `createObject` копирует входные параметры и использует один служебный
   `workflow-create-worker`. Проверяет класс, шаблон, роль создания, типы;
   вызывает `commit(CREATE)` и только затем завершает future объектом.
2. `executeAction` и `updateParameter` вызывают внутренний `send` движка.
   Для objectId лениво создаётся один `ObjectRuntime` с одним StateMachine.
   Изменение параметра использует зарезервированный eventType `update_parameter`.
3. `StateMachine.send` копирует payload/context и помещает `EventEnvelope`
   с `CompletableFuture<EventResult>` в `LinkedBlockingQueue`. Один platform worker
   последовательно исполняет FIFO. У разных объектов workers независимы.
4. Внутри worker `ObjectRuntime.prepare` читает свежий объект и пользователя,
   проверяет согласованность classId/templateId/stateId, конечное состояние,
   роли, разрешённость редактирования, тип параметра или наличие действия.
5. Автомат выбирает переход по ДНФ: ноль истинных — отказ; более одного —
   неоднозначность. Затем исполняет handler. Для update используется handler
   без смены состояния и тот же путь commit.
6. Commit hook формирует ObjectChange. FileRepository под lock перечитывает
   последний JSON, сверяет ожидаемый объект, сохраняет объект и одну запись
   истории в одном новом снимке.
7. Снимок записывается во временный файл рядом с store, выполняется
   `FileChannel.force(true)`, затем `ATOMIC_MOVE + REPLACE_EXISTING`.
   Неатомарного fallback нет. Только после успеха автомат публикует
   `currentState`, а worker завершает future. Ошибка не останавливает worker.

```mermaid
sequenceDiagram
    participant UI as Браузер
    participant HTTP as Javalin / Controller
    participant E as WorkflowEngine
    participant Q as Очередь объекта / worker
    participant R as FileRepository
    UI->>HTTP: POST action / PATCH parameter
    HTTP->>E: executeAction / updateParameter
    E->>Q: send(EventEnvelope)
    E-->>HTTP: CompletableFuture (ещё не завершён)
    Note over HTTP: ctx.future связывает HTTP-ответ с future
    Q->>Q: prepare, роли, типы, ДНФ, handler
    Q->>R: commit(ObjectChange)
    R->>R: объект + история, force, atomic move
    R-->>Q: фиксация успешна
    Q->>Q: публикация состояния, complete(future)
    HTTP-->>UI: 200 после commit; при отказе ApiError
    UI->>HTTP: обновление объекта, access, process, history
```

Ограничения: очередь в памяти и без ограничения длины; worker на каждый активный
объект не выгружается; общий JSON сериализует запись. Отмена future и HTTP-таймаут
не отменяют принятую команду. Commit может сохраниться при потере ответа клиенту.
Не делать автоматический повтор POST/PATCH и не обещать exactly-once для HTTP.
При неизвестном результате UI сообщает об этом и перечитывает объект/историю.

Callbacks `thenAccept` могут исполняться на worker; они должны быть короткими,
не ждать следующую команду этого же объекта и не вызывать `engine.close()`.
Получение всех данных карточки вынести в последующие GET, а не в тяжёлый callback.

## 5. Данные Б.3 и восстановление

Единственный production-seed: [`initial-data.json`](../src/main/resources/seed/initial-data.json).
В нём schemaVersion=1, четыре класса, шесть ролей, семь пользователей,
один шаблон, пять объектов и пять CREATE-записей.

Иерархия: `order → standard_order → office_order / consumables_order`.
`quantity` у order: 1..10000, у office_order переопределён: 1..500.
Шаблон `standard_order_supply` принадлежит standard_order; оба подкласса
его наследуют. Тестовые JSON из `src/test/resources/templates/` не являются
действующими производственными шаблонами.

| stateId | Название | Роль | Редактирование |
| --- | --- | --- | --- |
| registration | Регистрация | order_manager | Все параметры |
| composition_approval | Согласование состава | order_manager | Все параметры |
| price_approval | Согласование цены | procurement_specialist | Нет |
| delivery_approval | Согласование графика поставки | logistics_specialist | Нет |
| payment_approval | Согласование графика оплаты | finance_specialist | Нет |
| postponed_review | Отложенное рассмотрение | order_manager | Нет |
| execution_decision | Решение о выполнении | procurement_head | Нет |
| execution | Выполнение | executor | Нет |
| postponed_execution | Отложенное выполнение | procurement_head | Нет |
| completed | Выполнено | Нет | Нет, конечное состояние |

В шаблоне 10 определений действий, 14 предикатов и 19 переходов, включая
возвраты и отложения. Граф UI получает их из сохранённого шаблона, без своего
списка состояний, enum, переходов или switch по стадиям Б.3.

Реальные userId: `user_manager`, `user_procurement`, `user_logistics`,
`user_finance`, `user_head`, `user_executor`, `user_coordinator`.
Последний имеет все шесть ролей. `ivanov` и `petrov` из примеров ТЗ в seed нет.

Примеры объектов: `demo-standard-valid`, `demo-office-valid`,
`demo-office-quantity-700`, `demo-missing-product`, `demo-invalid-prepayment`.
Они уже имеют номера, параметры и CREATE; все находятся в registration.
Число объектов нового store по умолчанию равно пяти, а не нулю.

### Перезапуск

1. FileRepository с тем же Path вызывает `loadOrInitialize()`: seed применяется
   **только если файла нет**. Существующий store читается без перезаписи и миграции.
   Повреждённый JSON/неподдерживаемая схема вызывают ошибку, не сброс в seed.
2. Из сохранённых classes создаётся CatalogService; TemplateCatalogService
   работает с templates из этого же Repository.
3. `getObject`/`getHistory` доступны сразу из JSON, без запуска всех автоматов.
4. Первая команда по объекту лениво создаёт runtime. Берутся сохранённые
   `stateId`, `classId`, `templateId`; при null templateId применяется наследуемое
   разрешение. Автомат стартует в **сохранённом**, а не в начальном состоянии.
5. Пользовательские handlers, зарегистрированные Java-кодом, в JSON не
   сохраняются; если расширение их использует, WebApplication регистрирует
   их заново при старте. Для обычных переходов seed отдельные handlers не нужны.

Незавершённые сообщения после аварийного завершения не восстанавливаются.
Текущий DemoApplication создаёт новый `demo/run-*/store.json` на каждый запуск
и отвергает аргументы. Поэтому web имеет отдельную точку входа и постоянный путь.

## 6. Выбор способа интеграции

| Подход | Польза | Цена / соответствие задаче |
| --- | --- | --- |
| Пакет web в текущем Maven-артефакте | Прямое использование сервисов, общий classpath и тесты, отдельный main | Рекомендуется для текущего размера проекта; HTTP-зависимости появятся в общем pom |
| Отдельный Maven-подмодуль | Отдельный артефакт UI и его зависимости | Потребует parent POM и выделения ядра; сейчас необязательная реорганизация |

Рекомендация автора плана — первый вариант. В этом документе «web-модуль»
означает пакет/адаптер, не Maven multi-module. Это предложение, а не уже
реализованный или подтверждённый пользователем выбор.

### Сборка и локальный запуск

В будущем добавить `io.javalin:javalin:7.2.4`,
`org.webjars.npm:cytoscape:3.34.1` и один logging provider SLF4J 2.x.
Предпочтителен core Javalin без bundle: Jackson и JUnit уже определены проектом.
Сохранить Jackson BOM 2.20.1, явно настроить HTTP mapper с JavaTimeModule,
ISO-8601, точными decimal/целыми и отклонением лишнего JSON после документа.
Не переходить на Jackson 3 ради UI. Фактическую бинарную совместимость и
единственный SLF4J provider проверить через `mvn dependency:tree` и HTTP-тесты
после добавления зависимостей; сейчас они в проект не добавлены.

Javalin 7 поддерживает Java 17+, следовательно целевой JDK 21 подходит;
маршруты и exception handlers регистрируются внутри `config.routes`
до запуска. Для futures используется `ctx.future(Supplier<CompletableFuture<?>>)`.
WebJars включаются через `config.staticFiles.enableWebjars()`.
Это проверено по [документации Javalin](https://javalin.io/documentation)
и [руководству миграции на 7](https://javalin.io/migration-guide-javalin-6-to-7).
Jackson 2 указан как optional-зависимость в
[POM Javalin 7.2.4](https://github.com/javalin/javalin/blob/javalin-parent-7.2.4/javalin/pom.xml).

Для аудита скачан, но не подключён к проекту
[WebJar 3.34.1](https://repo.maven.apache.org/maven2/org/webjars/npm/cytoscape/3.34.1/cytoscape-3.34.1.jar).
Внутри реально есть `META-INF/resources/webjars/cytoscape/3.34.1/dist/cytoscape.min.js`
(435503 байта) и LICENSE. Планируемый URL —
`/webjars/cytoscape/3.34.1/dist/cytoscape.min.js`; HTTP 200 проверяется на этапе
реализации. Наличие JAR не доказывает работу ещё не созданного сервера.

WebApplication явно собирает один Repository, CatalogService,
TemplateCatalogService, DefaultConditionEvaluator и DefaultWorkflowEngine через
его полный существующий конструктор. Эти же экземпляры передаются адаптерам.
Сервер слушает только `127.0.0.1:7070`; порт может настраиваться.
Произвольный CORS не включается. userId — выбор учебного пользователя,
а не аутентификация. Данные/роли не принимаются от клиента как достоверные.

Путь хранилища: `--store` → `WORKFLOW_WEB_STORE` → `demo/web/store.json`.
Путь по умолчанию постоянен между запусками и уже попадает под `/demo/`
в .gitignore. Не использовать FileRepository() без аргументов и не добавлять
автоматический reset. Существующий пользовательский `data/store.json` не затрагивать.

Существующий Maven-запуск demo сохранить. Для web предложен отдельный профиль
`web` с main `ru.moipis.workflow.web.WebApplication` и командой
`mvn -Pweb compile exec:java`. Профиль меняет точку входа, не изолирует зависимости.
Эта команда пока **проектная**, её работоспособность должна быть проверена после
реализации. Обычный JAR остаётся без зависимостей; fat JAR сейчас не требуется.

Остановка: прекратить приём новых HTTP-команд, дать завершиться принятым
HTTP-futures, остановить Javalin и закрыть engine из внешнего lifecycle-потока.
Настроить и проверить graceful stop; не терять подтверждения уже принятых команд.
Repository отдельного закрытия не требует. Ошибка запуска сервера также должна
закрывать созданный engine. Незавершающийся пользовательский handler способен
задержать shutdown — существующее ограничение ядра.

## 7. Недостающие компоненты и минимальное уточнение ядра

### Чтение списков и представлений

Новый `web/WorkflowQueryAdapter` получает Repository **как интерфейс**, CatalogService,
TemplateCatalogService и WorkflowEngine. Использует `load().objects()/users()/classes()/roles()`
для списков; эффективные параметры получает из CatalogService, историю — из engine.
Контроллеры не открывают JSON-файл и не зависят от FileRepository.
Адаптер не хранит вторую копию текущих заказов и не вызывает методы записи Repository.

Шаблон для `/process` и подписей выбирается по object.templateId; только при null
используется `resolveTemplate`. Проверяются применимость шаблона классу и наличие
сохранённого stateId; несовместимый store не должен превращаться в выдуманный граф.
Live-изменение активных шаблонов и публикация динамических регистраций автоматов
через HTTP в задачу не входят.

Список объектов сортируется по последней реальной записи истории (timestamp
по убыванию, стабильный порядок при равенстве); без истории — детерминированно
по id после объектов с временем. Не добавлять фиктивные timestamps в BusinessObject.
`displayTitle`: непустой product_name → номер заказа → название класса и id.
`orderNumber` отсутствует/null, если его нет в parameters. Это форматирование,
не новая обязательность или бизнес-валидация параметров.

### Доступ к редактированию: существенный пробел публичного API

`ParameterEditPolicy` уже умеет `enabled()` и `allows(parameterId)` и поддерживает
как boolean, так и набор id параметров. Но проверка роли и конечного состояния
находится внутри package-private ObjectRuntime; публичного запроса доступа нет.
Нельзя считать `!getAvailableActions(...).isEmpty()` признаком права редактирования:
автомат допускает состояние без исходящих переходов с разрешённым редактированием.
Нельзя пробовать PATCH ради проверки прав: это реальная операция с историей.

Полностью неизменяемое ядро позволило бы адаптеру заново вычислить пересечение
ролей и политику состояния, но это дублировало бы проверку полномочий. При строгом
требовании «не дублировать бизнес-правила» рекомендовано одно обоснованное,
добавочное расширение ядра на следующем этапе:

```java
// ПРЕДЛОЖЕНИЕ: такого метода и record пока нет.
WorkflowAccess getAccess(String userId, String objectId);
// WorkflowAccess: stateId, availableActionIds, canEditParameters, editableParameterIds
```

Внутренний `WorkflowAccessPolicy` выделяет существующие общие проверки роли,
terminal и ParameterEditPolicy; его используют новый запрос,
`getAvailableActions` и `ObjectRuntime.prepare`. Запрос берёт один снимок объекта
и пользователя, возвращает уникальные actionId и конкретные разрешённые
parameterId из эффективных определений. Предикаты не вычисляет.
Не менять смысл правил, очереди, commit и существующие сигнатуры.
Порядок ошибок команд (terminal → роль → запрет редактирования → проверка типа
→ allows) сохранить. Повторная авторитетная проверка остаётся внутри worker.
`/access` — снимок для отображения, не разрешение на будущую команду.

Это единственное предлагаемое изменение поведения API ядра: новый read-only
метод с общей политикой; сам движок не переписывается. Если интерфейс ядра должен
остаться абсолютно неизменным, это ограничение требует отдельного решения:
точный `/access` нельзя честно получить из имеющихся публичных методов без
повторения части политики. В этом этапе ни один из вариантов не реализован.

В HTTP `canEditParameters` дополняется `editableParameterIds`: один boolean
не описывает выборочные права, уже поддержанные моделью. Для существующего Б.3
это все эффективные параметры в двух состояниях либо пустой список.
`editParameters` в DTO графа остаётся `true`, `false` или массивом id по модели.

## 8. Предлагаемая структура файлов

Все перечисленные здесь файлы — будущие, кроме явно отмеченных существующих.

```text
pom.xml                                      # изменить: HTTP, WebJar, logger, профиль web
src/main/java/ru/moipis/workflow/
  web/
    WebApplication.java                      # композиция и lifecycle
    WebConfiguration.java                    # host, port, store; без правил Б.3
    WebServerFactory.java                    # конфигурация Javalin 7 и static files
    WorkflowApiController.java               # запросы, DTO, вызовы сервисов/futures
    WorkflowQueryAdapter.java                # чтение недостающих списков и графа
    WorkflowDtoMapper.java                   # форматирование представлений
    WebExceptionMapper.java                  # единая схема ошибок
    dto/
      CreateObjectRequest.java
      PerformActionRequest.java
      UpdateParameterRequest.java
      UserDto.java
      ClassDto.java
      ParameterDto.java
      ObjectSummaryDto.java
      ObjectDetailsDto.java
      ObjectAccessDto.java
      ProcessGraphDto.java                   # вложенные StateDto/TransitionDto
      HistoryEntryDto.java
      ActionResultDto.java
      ApiError.java
  engine/
    WorkflowEngine.java                      # существующий: добавить getAccess
    DefaultWorkflowEngine.java               # существующий: общий запрос доступа
    ObjectRuntime.java                       # существующий: использовать общую политику
    WorkflowAccessPolicy.java                # выделить текущую политику, без новых правил
    model/WorkflowAccess.java                # immutable read model доступа
src/main/resources/public/
  index.html
  css/app.css
  js/api.js
  js/app.js
  js/graph.js
src/test/java/ru/moipis/workflow/
  engine/WorkflowAccessTest.java
  web/
    WebTestFixture.java                       # сервер port 0, @TempDir, закрытие ресурсов
    WorkflowApiIntegrationTest.java
    WebErrorHandlingTest.java
    WebPersistenceTest.java
    WebConcurrencyTest.java
    WebResourceTest.java
docs/ui-integration-plan.md                   # создан в текущем этапе
README.md                                    # будущие команды, API, демо, ограничения
docs/report.md                               # будущий раздел про необязательный UI
```

Новый HistoryService, второй engine, web-репозиторий заказов, собственный worker,
бизнес-классы OfficeOrder и enum статусов не нужны. Зависимости направлены
`web → engine/catalog/template/Repository`; ядро не импортирует Javalin или web DTO.

## 9. REST-контракт

Локальный внутренний API `/api`, без отдельной версии на первом этапе.
Content-Type запросов с телом — `application/json`; ответы ошибок также JSON.
На границе проверяются форма JSON, наличие/непустота идентификаторов и тип полей
запроса. Типы бизнес-параметров проверяет движок. Не добавлять обязательность,
min/max или пересчёт total_amount, которых нет в его контракте.

| Метод и путь | Источник / команда | Успешный ответ |
| --- | --- | --- |
| GET `/api/users` | QueryAdapter: `Repository.load().users()/roles()` | 200: `{id,name,roles[],roleTitles}`; названия ролей из каталога |
| GET `/api/classes` | QueryAdapter: `load().classes()` | 200: `{id,title,parentId,orderClass}`; orderClass вычисляет backend через `isInstanceOf`, если standard_order есть |
| GET `/api/classes/{classId}/parameters` | `CatalogService.getEffectiveParameters` | 200: `{id,title,type,min,max}` |
| GET `/api/objects` | QueryAdapter: снимок objects + history, подписи классов/шаблонов | 200: массив ObjectSummaryDto с фактическим последним временем, если оно есть |
| POST `/api/objects` | `engine.createObject(userId,classId,parameters)` | 201 + `Location: /api/objects/{id}` + ObjectDetailsDto после commit |
| GET `/api/objects/{id}` | `engine.getObject`, каталог эффективных параметров, mapper | 200: объект, сохранённые значения, class/state title и определения параметров |
| GET `/api/objects/{id}/process` | Объект + `getTemplate(templateId)`; fallback `resolveTemplate` только при null | 200: templateId/title, currentStateId, states, transitions с подписями действий |
| GET `/api/objects/{id}/access?userId=...` | Предлагаемый `engine.getAccess`; подписи действий из шаблона | 200: stateId, canEditParameters, editableParameterIds, availableActions `{id,title}` |
| GET `/api/objects/{id}/history` | `engine.getHistory`; пользовательские имена и подписи из каталогов | 200: хронологические записи CREATE/ACTION/UPDATE_PARAMETER |
| PATCH `/api/objects/{id}/parameters/{parameterId}` | `engine.updateParameter(userId,id,parameterId,value)` | 200: ActionResultDto из EventResult после commit |
| POST `/api/objects/{id}/actions` | `engine.executeAction(userId,id,actionId)` | 200: ActionResultDto из EventResult после commit |

В истории сохранять порядок commit для равных timestamps; для отображения
сортировать по timestamp стабильно, не по случайному UUID. Не переименовывать
поля `oldState/newState` в данные, которых в истории нет. ActionResultDto содержит
`previousStateId/currentStateId/handlerResult`, а не выдуманный BusinessObject.
Состояние результата конкретной команды может отличаться от последующего GET,
если к тому времени выполнена следующая команда; это нормальная конкуренция.

Тела запросов с реальными id:

```json
{"userId":"user_manager","classId":"office_order","parameters":{"order_number":"ORD-001","quantity":700}}
```

```json
{"userId":"user_manager","actionId":"approve"}
```

```json
{"userId":"user_manager","value":100}
```

Поля userId/classId/actionId и объект parameters обязательны в соответствующих
request DTO. Пустая карта parameters допустима. Для PATCH поле value должно
присутствовать: явный null очищает значение; пропущенное value — ошибка запроса.
Отсутствующие параметры создания остаются отсутствующими, без пустых строк
или произвольных default. Пример минимального создания выше допустим, но для
переходов потребуются остальные значения, предусмотренные предикатами.

В `ctx.future` передаётся цепочка от существующего future движка, успех
записывается в thenAccept. Не вызывать get/join/sleep, не оборачивать команды
в новый supplyAsync и не возвращать 202 вместо результата. Синхронные ошибки
и исключения async-цепочки приводятся к одному WebExceptionMapper.
Перед сопоставлением раскрываются CompletionException/ExecutionException.

### Ошибки

Единый формат по UI-ТЗ:

```json
{"code":"CONDITION_NOT_MET","message":"Условия перехода не выполнены","details":{}}
```

| Источник | HTTP | Обработка |
| --- | --- | --- |
| Некорректный JSON/отсутствующие поля | 400 | Код web-границы `INVALID_REQUEST` |
| INVALID_PARAMETER_TYPE, UNKNOWN_PARAMETER | 400 | Сохранить domain code |
| ACCESS_DENIED | 403 | И для роли, и для запрета изменения параметра |
| NOT_FOUND | 404 | Объект, пользователь, класс; неизвестный route `/api` также JSON |
| INVALID_ACTION, CONDITION_NOT_MET, FINAL_STATE, CONCURRENT_MODIFICATION | 409 | Сохранить domain code; UI перечитывает карточку |
| TEMPLATE_ERROR с причиной AmbiguousTransitionException | 409 | Конфликт переходов, code остаётся TEMPLATE_ERROR |
| Прочие TEMPLATE_ERROR / неверный сохранённый шаблон | 500 | Ошибка конфигурации сервера, не входного JSON |
| PERSISTENCE_ERROR, HANDLER_ERROR, неожиданные сбои | 500 | Без внутренних путей/stack trace в ответе |
| ENGINE_CLOSED | 503 | Явное недоступное состояние сервиса при остановке |

Для прямых обращений QueryAdapter неизвестный classId преобразуется в NOT_FOUND,
но не любой IllegalArgumentException автоматически в 400: ошибка валидности
сохранённого шаблона должна остаться серверной. IOException адаптера →
PERSISTENCE_ERROR. Полный cause логируется на сервере, клиенту — безопасное сообщение.

Текущий CONDITION_NOT_MET сообщает, что подходящий переход не найден, но не
возвращает список ложных предикатов. Не обещать точную причину «quantity вне
диапазона» как уже существующее поле API. Можно показать описание условия
шаблона без его повторного вычисления; подробная диагностика потребовала бы
отдельного изменения evaluator и в этот план не включена.

## 10. Поведение будущего UI

Одна статическая страница с представлениями списка, создания и карточки.
Список показывает фактические названия, поиск по номеру/продукции и фильтр
состояния. Форма создания строится по эффективным параметрам. Потомки
standard_order определяются по backend-признаку из реальной иерархии,
не по суффиксу id. Пользователь выбирается явно; его роли только отображаются.

Типы полей: string → text, integer → number с целым шагом, decimal → number
с step=any. Диапазоны — подсказки, а не HTML min/max, блокирующие quantity=700.
Сохранить различие unset/null/пустая строка; очистка block_reason посылает null.
Не вводить required для бизнес-полей. Для больших чисел предусмотреть сохранение
точного JSON-значения: не перегонять все значения через JS Number. Изменённый
numeric token валидировать синтаксически и передавать как JSON-число без потери
разрядов; неизменённые значения не пересылать. Проверки ролей и ДНФ в JS нет.

Карточка получает объект, access, process и историю. Граф Cytoscape строится
из states/transitions, имеет отдельные id рёбер (например, индекс в шаблоне),
поддерживает параллельные дуги, циклы, возвраты, zoom/pan/fit и легенду.
Текущий узел — из currentStateId, пройденные узлы/дуги — из фактической истории.
Выбор или перемещение узла не выполняет переход. При недоступности Cytoscape
показывается текстовый список состояний и переходов.

Кнопки приходят из backend access; ложный предикат не убирает действие.
Разрешённость редактирования каждого поля определяется editableParameterIds.
На время команды блокировать повторную отправку этого действия в данной
карточке. После успеха и domain-отказа заново загружать данные; при смене
пользователя/объекта игнорировать устаревшие ответы предыдущей загрузки.
Не делать оптимистическую смену состояния до ответа движка.

Нейтральная светлая тема, адаптивность 1366px/мобильная ширина, labels,
focus-visible, aria-live, состояния загрузки/пустого списка/ошибок.
Для данных использовать textContent и создание DOM. Все JS/CSS/шрифты
локальны, внешних CDN и Node.js нет. Backend повторно проверяет любую команду,
даже если браузер обошёл скрытые или disabled элементы.

## 11. План тестирования следующего этапа

HTTP-тесты используют настоящий engine и FileRepository с @TempDir,
Javalin на `127.0.0.1` и порту 0, Java 21 HttpClient с ограниченными таймаутами.
Порт узнаётся у запущенного сервера. JSON сравнивается структурно.
Синхронизация — futures/latches, без Thread.sleep. Все серверы/engine закрываются
в finally/fixture; тесты не используют рабочий или постоянный demo store.

| Набор | Сценарии и существенные утверждения |
| --- | --- |
| WorkflowAccessTest | Новый запрос совпадает с существующими запретами команд; terminal, чужая роль, boolean/выборочная политика; редактируемое состояние без исходящих действий; предикаты не вызываются; сам запрос не создаёт историю |
| WorkflowApiIntegrationTest | Списки пользователей/классов/объектов, 5 seed-заказов, эффективные параметры; создание quantity=700 через 201; исправление 100 и действие; история ровно +1 на принятую операцию |
| Process API | 10 узлов и 19 рёбер по seed; отдельный тестовый шаблон с другим числом узлов/дуг; альтернативные дуги одного действия не дают дублей кнопок; старый templateId сохраняет граф после создания собственной копии подкласса |
| WebErrorHandlingTest | 400/403/404/409/500/503 и единый ApiError; пропущенный value против null; неизвестные id/параметры; неверный тип, роль, конечное состояние, ложное условие, неоднозначность, ошибка handler, отказ атомарной записи |
| Неизменность при отказе | После каждого domain-отказа сравнить весь объект, историю и содержимое JSON с предыдущим снимком, а не только HTTP-статус |
| WebPersistenceTest | Создать, изменить, перевести в payment_approval, остановить сервер/engine, собрать новые экземпляры с тем же Path, проверить объект/history/templateId и продолжить до completed; повреждённый store не перезаписывается |
| WebConcurrencyTest | Две команды одного объекта проходят через один worker; PATCH перед approve влияет на его условия; другой объект не блокируется handler первого; удержание AtomicFileMover доказывает отсутствие раннего 2xx; после ошибки worker принимает следующую команду |
| WebResourceTest | `/`, CSS/JS и точный URL Cytoscape возвращают 200 с правильным содержимым/типами; в ресурсах нет CDN; сетевой интерфейс только loopback, произвольный CORS не включён; `/api`-404 не превращается в index.html |
| Числа и nullable | Точные decimal/большие integer в HTTP mapper, отсутствие/null/пустая строка, block_reason=null; родительский диапазон не подменяет office_order 1..500 |
| Lifecycle | Shutdown завершает принятые команды и workers; отказ старта сервера не оставляет engine; callback не вызывает close; разрыв HTTP-соединения не заявляется отменой commit |

Полный ручной сценарий: создание office_order с quantity=700 и достаточными
остальными параметрами, complete, отказ approve, исправление 100, согласования
с переключением ролей, correction и повторное прохождение, postponed_review,
postponed_execution, завершение и запрет дальнейших изменений. Отдельно проверить
standard_order с предоплатой 20% и office_order с 40% (обе OR-ветви оплаты),
кнопку approve при ложном условии и неизменную историю при отказе.

Перед сдачей: `mvn clean test`, `mvn dependency:tree`, проверка проектной команды
web-запуска, HTTP smoke и браузерная проверка. Наличие jar и успешные тесты ядра
не считать проверкой визуального интерфейса.

## 12. Фактический результат текущих тестов

В PATH отсутствует mvn, Java по умолчанию — 25. Найдены уже имеющиеся Maven 3.9.9
в `.tools` и JDK 21.0.10 в JetBrains Rider. Установка инструментов не понадобилась.
Выполнена команда из корня lab1:

```powershell
$env:JAVA_HOME='C:\Program Files\JetBrains\JetBrains Rider 2025.3.3\jbr'
& .\.tools\apache-maven-3.9.9\bin\mvn.cmd '-Dmaven.repo.local=.tools/m2' -o clean test
```

Это `clean test` с существующим локальным Maven-кэшем в offline-режиме.
Первый запуск в песочнице получил AccessDeniedException при очистке JUnit TempDir
в Windows Temp. Та же команда с разрешённым выполнением вне песочницы прошла
без изменений исходников. Итог: **BUILD SUCCESS, 349 случаев, Failures=0,
Errors=0, Skipped=0**, 14 тестовых классов, 8.604 секунды по Maven.

| Тестовый класс | Случаев |
| --- | ---: |
| CatalogServiceTest | 44 |
| DefaultConditionEvaluatorTest | 71 |
| DemoApplicationTest | 4 |
| WorkflowEngineTest | 22 |
| FileRepositoryTest | 14 |
| StandardOrderProcessTest | 35 |
| WorkflowAccessAndParametersTest | 47 |
| WorkflowLifecycleRequirementsTest | 22 |
| WorkflowQueueAndPersistenceTest | 12 |
| ConditionalStateMachineTest | 12 |
| DefaultStateMachineTest | 18 |
| ExecutionHooksStateMachineTest | 4 |
| QueuedStateMachineTest | 15 |
| TemplateCatalogServiceTest | 29 |

Лог — `.tools/ui-audit-tests.log`; XML-отчёты — `target/surefire-reports/`.
В частности, уже проходят проверки `restartRetainsUpdatedValuesAuditAndTheSavedStateAndExecutionContinues`,
`machinePublishesTheStateOnlyAfterAtomicReplacementFinishes`,
`failedAtomicWritePreservesTheFileObjectHistoryAndMachineAndCanBeRetried`,
`twoMatchingTransitionsAreATemplateErrorRatherThanAFirstMatch`.
Сервер и браузерный UI на этом этапе не запускались, HTTP-тестов ещё нет.

## 13. Проблемы интеграции и необходимые изменения

| Риск / отличие от предполагаемого ТЗ | Решение следующего этапа |
| --- | --- |
| Нет HistoryService, listObjects/listUsers/listClasses | Использовать getHistory и читающий WorkflowQueryAdapter; не создавать альтернативный путь записи |
| Нет getAccess/canEditParameters | Минимальный новый read-only запрос и общая существующая политика в ядре; не угадывать доступ по действиям |
| Политика редактирования может быть набором параметров | editableParameterIds в HTTP, ParameterEditPolicy в ядре |
| Методы команды возвращают EventResult, не объект | ActionResultDto и явное обновление карточки через GET |
| Сохранённый templateId важнее текущего наследования | Рисовать граф объекта по его привязке, fallback только для null |
| У объекта нет updatedAt/названия отдельными полями | Время из истории, заголовок из parameters/каталогов, без миграции store |
| JSON заменяется только атомарно; блокировка действует в JVM | Один web-процесс/engine на store, локальная файловая система с ATOMIC_MOVE; не обещать поддержку двух процессов |
| Очередь не сохраняется и отмена future не отменяет команду | Честное сообщение о неизвестном результате, перечитывание, без автоматических повторов |
| Несколько GET могут увидеть разные завершённые операции | /access возвращает stateId, клиент отслеживает актуальность загрузок; команды проверяются в очереди |
| Точный список ложных предикатов отсутствует | Общее CONDITION_NOT_MET и описание условия, без повторной проверки в JS или web |
| Проект уже содержит пользовательские изменения README и untracked-файлы | Не затрагивать их на этапе аудита; будущие дополнения README вносить поверх имеющегося содержимого |
| Нет исходной Word-методички | Отметить ограничение, сверить оригинал при его появлении; не исправлять seed на основании предположений |

Рекомендуемая последовательность дальнейшей работы:

1. Уточнить и добавить общий запрос доступа, сохранив текущие проверки команд;
   покрыть редактируемое состояние без исходящих действий и выборочную политику.
2. Добавить Javalin/WebJar/logger, композицию и профиль запуска; проверить
   dependency tree и совместимость Jackson без изменения JSON-схемы.
3. Реализовать DTO, query adapter, endpoints, async-ответы и error mapping;
   проверить их на реальном движке и временном store.
4. Добавить статический UI и динамический граф, затем проверить Б.3, локальные
   ресурсы, перезапуск, конкурентные команды и отказ хранения.
5. Дополнить README и report реальными командами/результатами, снова выполнить
   все тесты и браузерную проверку.

В этом этапе перечисленные изменения только спроектированы. Реализация UI
не начата; рабочие интерфейсы и правила WorkflowEngine сохранены.
