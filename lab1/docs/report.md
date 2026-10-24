# Пояснительная записка

Дисциплина: **«Модели объектов и процессов информационных систем»**.
Работа **№ 3-1-1 «Приёмы моделирования бизнес-логики»**.
Вариант **Б.3 «Управление заказом на поставку стандартной продукции»**.

Документ содержит материал для последующего оформления в Word.
Word и PDF на данном этапе не создаются. ФИО участников и оформление титульного
листа следует добавить отдельно; фактическое авторство не предполагается.

## 1. Цель работы и постановка задачи

Цель — освоить приёмы представления бизнес-логики как универсальной модели
и реализовать движок исполнения процессов по шаблонам. Классы предметной области,
параметры, роли, действия, предикаты, состояния и переходы должны определяться
данными, а не Java-ветвлениями для конкретного заказа.

Предметный пример — управление поставкой стандартной продукции. Требуется
поддержать наследование параметров и шаблонов, согласование состава, цены,
поставки и оплаты, отложения и возвраты, решение о выполнении и завершение.
Дополнительное требование — событийный конечный автомат с собственной очередью,
состоянием, handlers и динамической регистрацией конфигурации.

Реализация использует Java 21, Maven, JUnit 5, Jackson Databind/JavaTimeModule,
java.util.concurrent и файловый JSON. Spring, внешние СУБД, брокеры, GUI,
REST API и готовые BPM-движки не используются.

Критерии результата: точное воспроизведение 19 переходов Б.3, проверка прав
и условий, отсутствие частичной фиксации, восстановление сохранённых объектов,
автоматическое тестирование и воспроизводимая демонстрация.

## 2. Архитектура системы

Система разделена по ответственностям:

- CatalogService хранит проверенный снимок иерархии и вычисляет эффективные параметры.
- TemplateCatalogService разрешает ближайший шаблон и сохраняет его независимые копии.
- WorkflowEngine принимает команды над объектами, проверяет права и координирует commit.
- ObjectRuntime связывает конкретный объект с его собственным автоматом.
- StateMachine исполняет события без знания правил заказа и файлового хранилища.
- ConditionEvaluator вычисляет предикаты и ДНФ.
- Repository отделяет контракт хранения от реализации FileRepository.
- DemoApplication собирает настоящие компоненты и выполняет сценарий защиты.

У каждого активного объекта — отдельные очередь, worker и текущее состояние.
Объекты не используют один глобальный автомат. Создание экземпляров автомата
ленивое: до первой команды достаточно сохранённого BusinessObject.
Сервис создания объектов использует отдельный однопоточный executor движка.

[Диаграмма компонентов](diagrams/rendered/components.svg),
[исходник PlantUML](diagrams/components.puml).
Подробные решения: [architecture.md](architecture.md).

## 3. Основные Java-классы и интерфейсы

| Тип | Назначение |
| --- | --- |
| ObjectClassDefinition / ParameterDefinition | Строковый id класса, родитель, собственные параметры и их определения. |
| UserDefinition / RoleDefinition | Пользователь с множеством строковых id ролей; определение роли. |
| ProcessTemplate | classId, createRoles, actions, predicates, states, transitions. |
| StateDefinition / TransitionDefinition | Состояние с ролями и политикой редактирования; дуга с необязательной ДНФ. |
| BusinessObject | Универсальный экземпляр: id, classId, stateId, параметры, templateId. |
| HistoryEntry | Кто, когда и какую принятую операцию выполнил; details. |
| Event / EventEnvelope / EventResult | Событие с инициатором и контекстом; сообщение с future; результат. |
| StateMachine / DefaultStateMachine | API регистрации и очереди; реализация универсального автомата. |
| EventHandler / EventPreparer / EventCommitter | Функциональные точки выполнения, подготовки и фиксации операции. |
| StateMachineFactory / StateMachineConfiguration | Создание автомата из данных шаблона и сохранённого stateId. |
| ConditionEvaluator / DefaultConditionEvaluator | Контракт и реализация типизированной логики условий. |
| WorkflowEngine / DefaultWorkflowEngine | Асинхронные createObject, executeAction, updateParameter; синхронное чтение. |
| Repository / FileRepository | Загрузка и атомарная фиксация StoreData. |
| ObjectChange / StoreData | Подготовленное изменение и единый корневой снимок хранения. |
| WorkflowException / WorkflowError | Категория отказа, понятное сообщение и исходная причина. |
| DemoApplication | Единственная консольная точка входа; только сценарий демонстрации. |

Общий широкий интерфейс Catalog сохранён как контракт первоначального каркаса.
Работающее приложение использует CatalogService и TemplateCatalogService;
CatalogService не объявляется реализацией методов сохранения всего каталога.

[Диаграмма классов](diagrams/rendered/classes.svg),
[исходник](diagrams/classes.puml), [полная структура пакетов](packages.md).
В Java нет Order, StandardOrder и OfficeOrder как подклассов друг друга.

## 4. Модель хранения данных

Хранилище — один JSON-документ StoreData:

| Поле | Содержание |
| --- | --- |
| schemaVersion | Версия формата; сейчас 1. |
| classes | Иерархия классов и собственные определения параметров. |
| roles, users | Роли, пользователи и связи через id. |
| templates | Исходный процесс и созданные копии шаблонов. |
| objects | classId, stateId, параметры и привязка templateId каждого экземпляра. |
| history | Записи только успешно принятых операций. |

Исходный ресурс — src/main/resources/seed/initial-data.json.
FileRepository.loadOrInitialize копирует его только при отсутствии рабочего
файла. Существующий документ не перезаписывается и автоматически не мигрируется.
Повреждённое хранилище вызывает IOException, а не замену исходными данными.

Пользовательское хранилище по умолчанию — data/store.json. Демо использует новый
demo/run-.../store.json при каждом запуске. В тестах применяются @TempDir.

Числовые границы представлены BigDecimal; произвольные JSON-значения — JsonNode.
Файловый mapper поддерживает JavaTimeModule и точные decimal. Даты истории —
Instant в UTC ISO-8601. Контекст, входные значения и снимки копируются,
чтобы вызывающий код не менял сохранённое состояние через ссылку.

Объект и история фиксируются одной операцией commit(ObjectChange).
Под блокировкой нормализованного пути читается актуальный JSON, сверяется before,
готовится новый снимок и одна запись истории. Это не последовательность
независимых saveObject/saveHistory.

Фрагмент FileRepository.persist — запись и атомарная замена:

```java
Files.write(temporary, json);
try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
    channel.force(true);
}
mover.move(temporary, storePath);
```

Только после успешного mover.move присваивается current = prepared.
Mover по умолчанию вызывает Files.move с ATOMIC_MOVE и REPLACE_EXISTING;
обычного копирования в качестве fallback нет. Если атомарная замена недоступна,
операция отклоняется. Неудача не публикует новый объект или историю.
Блокировка общая для экземпляров FileRepository одного пути внутри JVM;
межпроцессная координация не реализована.

[Модель JSON и связей](diagrams/rendered/storage.svg),
[исходник](diagrams/storage.puml). Очередь и незавершённые futures на диск не пишутся.

## 5. Наследование параметров и шаблонов

В данных определена иерархия:

```text
order
└── standard_order
    ├── office_order
    └── consumables_order
```

order определяет номер, продукцию, quantity [1..10000] и total_amount [1..2000000].
standard_order добавляет поставщика, delivery_days [1..60],
prepayment_percent [0..100] и block_reason.
office_order добавляет office_location и полностью переопределяет quantity
диапазоном [1..500]. consumables_order добавляет storage_conditions.

Эффективные параметры собираются от корня к потомку. Совпавший id заменяет
всё определение: допускается менять название и границы, но не тип.
Проверяются неизвестный родитель, циклы и повторяющиеся определения.

Фрагмент CatalogService.getEffectiveParameters:

```java
Map<String, ParameterDefinition> effective = new LinkedHashMap<>();
for (ObjectClassDefinition definition : getLineage(classId).reversed()) {
    for (ParameterDefinition parameter : definition.parameters()) {
        ParameterDefinition inherited = effective.get(parameter.id());
        if (inherited != null && !inherited.type().equals(parameter.type())) {
            throw new IllegalArgumentException(
                    "Class '%s' cannot change type of parameter '%s' from '%s' to '%s'"
                            .formatted(definition.id(), parameter.id(), inherited.type(), parameter.type()));
        }
        effective.put(parameter.id(), parameter);
    }
}
return Collections.unmodifiableMap(effective);
```

Шаблон standard_order_supply принадлежит standard_order. Если собственного
шаблона нет, поиск идёт к ближайшему предку. Без шаблона создание запрещено.
copyTemplate создаёт новый id, меняет classId, глубоко копирует элементы и
сохраняет копию через Repository.update. Второй собственный шаблон запрещён.

consumables_order может получить собственный шаблон копированием; office_order
при этом продолжает наследовать оригинал. Существующий BusinessObject хранит
templateId: последующее копирование для его класса не перепривязывает объект.

[Диаграмма иерархии](diagrams/rendered/domain-hierarchy.svg),
[исходник](diagrams/domain-hierarchy.puml).
Стрелки здесь выражают parentId данных, а не Java extends.

## 6. Описание процесса варианта Б.3

Создание требует order_manager. registration — единственное начальное состояние,
completed — единственное конечное. Параметры можно редактировать только
в registration и composition_approval.

| id | Название | Роли | editParameters | Признак |
| --- | --- | --- | --- | --- |
| `registration` | Регистрация | order_manager | true | initial |
| `composition_approval` | Согласование состава | order_manager | true | — |
| `price_approval` | Согласование цены | procurement_specialist | false | — |
| `delivery_approval` | Согласование графика поставки | logistics_specialist | false | — |
| `payment_approval` | Согласование графика оплаты | finance_specialist | false | — |
| `postponed_review` | Отложенное рассмотрение | order_manager | false | — |
| `execution_decision` | Решение о выполнении | procurement_head | false | — |
| `execution` | Выполнение | executor | false | — |
| `postponed_execution` | Отложенное выполнение | procurement_head | false | — |
| `completed` | Выполнено | — | false | final |

В шаблоне ровно следующие 19 переходов; псевдопереходы начала/завершения
на рисунке не являются дополнительными бизнес-дугами:

| № | Откуда | Действие | Куда |
| --- | --- | --- | --- |
| 1 | `registration` | `complete` | `composition_approval` |
| 2 | `composition_approval` | `approve` | `price_approval` |
| 3 | `composition_approval` | `postpone` | `postponed_review` |
| 4 | `price_approval` | `approve` | `delivery_approval` |
| 5 | `price_approval` | `correction` | `composition_approval` |
| 6 | `price_approval` | `postpone` | `postponed_review` |
| 7 | `delivery_approval` | `approve` | `payment_approval` |
| 8 | `delivery_approval` | `correction` | `composition_approval` |
| 9 | `delivery_approval` | `postpone` | `postponed_review` |
| 10 | `payment_approval` | `approve` | `execution_decision` |
| 11 | `payment_approval` | `correction` | `composition_approval` |
| 12 | `payment_approval` | `postpone` | `postponed_review` |
| 13 | `postponed_review` | `resume_review` | `composition_approval` |
| 14 | `execution_decision` | `execute` | `execution` |
| 15 | `execution_decision` | `return_to_approval` | `composition_approval` |
| 16 | `execution_decision` | `postpone_review` | `postponed_execution` |
| 17 | `execution` | `complete` | `completed` |
| 18 | `execution` | `postpone_execution` | `postponed_execution` |
| 19 | `postponed_execution` | `reconsider` | `execution_decision` |

Условия согласования:

- Состав: заполнены номер заказа, продукция и поставщик; quantity_in_range.
- Цена: amount_filled AND amount_in_range.
- Поставка: delivery_days_filled AND delivery_days_in_range.
- Оплата:
  (amount_in_range AND prepayment_le_30 AND NOT block_reason_filled)
  OR (is_office_order AND amount_in_range AND prepayment_le_50 AND NOT block_reason_filled).
- Завершение выполнения: заполнены номер, продукция, количество, сумма,
  поставщик, срок и процент предоплаты.

Для standard_order с предоплатой 20% истинна первая OR-ветвь; для office_order
с 40% — только вторая. Это две группы одного условия одной дуги, а не две
параллельные подходящие дуги.

[Диаграмма состояний](diagrams/rendered/b3-states.svg),
[исходник](diagrams/b3-states.puml).
Значения пяти исходных демонстраций и способы исправления: [orders.md](../orders.md).

## 7. Реализация очереди и событийного автомата

DefaultStateMachine имеет BlockingQueue<EventEnvelope>, один platform worker,
volatile currentState, реестры состояний и handlers, а также
Map<StateEventKey, List<TransitionDefinition>>. Список, а не одна дуга,
сохраняет все условные переходы одной пары состояние/событие.

send проверяет жизненный цикл под lifecycleLock и помещает сообщение в очередь.
Короткий фрагмент реального DefaultStateMachine.send:

```java
CompletableFuture<EventResult> future = new CompletableFuture<>();
queue.add(new EventEnvelope(snapshot, future));
return future;
```

Worker блокируется на take(), а не опрашивает очередь в цикле с задержками.
Он снимает согласованный снимок конфигурации, вызывает prepare, вычисляет
условия, выполняет handler и commit, затем завершает future.
Каждый объект имеет свой worker: одна операция объекта не меняет чужой автомат.

Жизненный цикл: STARTED → STOPPING → STOPPED. start не создаёт второй worker;
после полной остановки перезапуск того же экземпляра не поддерживается.
stop прекращает приём и ставит служебный маркер после принятых сообщений,
а внешний вызывающий поток дожидается завершения worker. send/stop синхронизированы.

Регистрация addState, registerHandler и addTransition доступна после start.
Реестр не удерживается на время handler, evaluator и I/O. Изменения применяются
к следующим сообщениям. Событие может выполнить только handler без перехода.

Исключение операции не останавливает worker и не меняет состояние.
Handlers и callbacks должны завершаться и не ждать событие своего worker.
WorkflowEngine.close вызывается вне принадлежащих ему workers; отмена future
сама по себе не отменяет уже принятую операцию.

В демо после start отдельного автомата добавляются configured, handler configure
и дуга idle → configured. Эта конфигурация не сохраняется в шаблоне Б.3.

## 8. Реализация вычисления предикатов и ДНФ

PredicateDefinition описывает либо параметр (parameterId, operator, value),
либо принадлежность классу (classId с учётом потомков).

Поддерживаются filled, =, !=; для чисел также <, <=, >, >=, in_range.
integer сравнивается через точное целочисленное представление BigInteger,
decimal — через BigDecimal. Строковое сравнение чисел не используется.
in_range читает необязательные min/max эффективного определения реального класса.

Отсутствующее/null-значение даёт false всем предикатам параметра; filled
истинно для любого заданного значения, включая ноль и пустую строку.
Следовательно, пустой block_reason не снимает блокировку: нужно установить null.

DnfCondition содержит список OR-групп, каждая — список AND-литералов.
ConditionLiteral хранит predicateId и negated. При NOT инвертируется результат
предиката, в том числе false для отсутствующего значения.

Фрагмент DefaultConditionEvaluator.evaluate:

```java
boolean value = evaluatePredicate(predicate, context, definitions);
if (literal.negated() ? value : !value) {
    matches = false;
    break;
}
```

Для перехода без condition результат считается истинным без вызова evaluator.
Автомат вычисляет условия всех кандидатов: ноль — отказ,
один — выполнить, больше одного — ошибка конфигурации.
Выбор первого совпадения и приоритет безусловной дуги не используются.

## 9. Реализация создания объекта и выполнения действия

createObject определяет действующий шаблон, проверяет createRoles, известность
параметров и типы, но не min/max. Выбирается initial-состояние; экземпляр и CREATE
фиксируются вместе. Фрагмент DefaultWorkflowEngine.create:

```java
values.forEach((id, value) -> ParameterValues.validate(id, value, configuration.effectiveParameters()));
BusinessObject object = new BusinessObject(UUID.randomUUID().toString(), classId,
        configuration.initialStateId(), values, configuration.template().id());
commit(ObjectChange.create(object, userId));
return object;
```

executeAction отправляет событие в очередь объекта. ObjectRuntime.prepare читает
актуальный снимок, проверяет согласованность stateId, отсутствие final, роль и
наличие дуг действия. ConditionContext строится из текущих параметров объекта,
поэтому действие видит результат предыдущего update_parameter в той же очереди.

После выбора единственной дуги вызывается универсальный handler; собственный
handler может быть зарегистрирован динамически. Он возвращает данные результата,
а не напрямую меняет внутреннее состояние или постоянное хранилище.

Фрагмент ObjectRuntime.commit:

```java
private void commit(Event event, EventResult result) {
    PreparedOperation operation = active;
    active = null;
    BusinessObject before = operation.before();
    if (UPDATE_PARAMETER.equals(event.eventType())) {
        engine.commit(ObjectChange.updateParameter(before, operation.updated(), operation.userId(), operation.parameterId()));
    } else {
        BusinessObject after = new BusinessObject(before.id(), before.classId(), result.currentStateId(),
                before.parameters(), before.templateId());
        engine.commit(ObjectChange.action(before, after, operation.userId(), event.eventType()));
    }
}
```

Критический порядок в DefaultStateMachine.executeEvent:

```java
EventPlan plan = planEvent(prepared, snapshot);
JsonNode handlerResult = plan.handler() == null ? null : plan.handler().handle(prepared);
EventResult result = new EventResult(plan.source().id(), plan.target().id(), handlerResult);
snapshot.committer().commit(prepared, result);
currentState = plan.target();
return result;
```

Таким образом, отказ записи не меняет volatile-состояние автомата.
После успешного завершения future сохранённый BusinessObject и автомат согласованы.
Между атомарной записью и присваиванием состояния есть короткое внутреннее окно:
при аварии восстановление использует сохранённый stateId как источник истины.

updateParameter — событие update_parameter без перехода, в той же очереди.
Проверяются final, роль, editParameters, существование параметра по наследованию
и тип. Принятое изменение добавляет одну UPDATE_PARAMETER; min/max проверяются
не при редактировании, а условиями процесса.

[Последовательность executeAction](diagrams/rendered/execute-action.svg),
[исходник](diagrams/execute-action.puml).

## 10. Права пользователей и история операций

Пользователь может иметь несколько ролей. Достаточно пересечения с createRoles
для создания либо с ролями текущего состояния для действия. Реальный фрагмент
DefaultWorkflowEngine.hasRole:

```java
static boolean hasRole(UserDefinition user, Set<String> roles) {
    return user.roles() != null && roles != null && user.roles().stream().anyMatch(roles::contains);
}
```

getAvailableActions возвращает уникальные id исходящих действий только при
подходящей роли и неконечном состоянии. Условия здесь не вычисляются:
доступное действие может быть отклонено при исполнении.

Историю формирует Repository.commit с внедрённым Clock:

| operation | details |
| --- | --- |
| CREATE | initialState и полные начальные parameters. |
| ACTION | actionId, oldState, newState. |
| UPDATE_PARAMETER | parameterId, oldValue, newValue. |

Каждая запись также содержит уникальный id, objectId, userId и timestamp.
Одна принятая операция даёт ровно одну запись. При отказе не меняются состояние,
параметры и история. Ошибки разделены на NOT_FOUND, ACCESS_DENIED, INVALID_ACTION,
CONDITION_NOT_MET, FINAL_STATE, TEMPLATE_ERROR, UNKNOWN_PARAMETER,
INVALID_PARAMETER_TYPE, PERSISTENCE_ERROR, CONCURRENT_MODIFICATION,
HANDLER_ERROR и ENGINE_CLOSED; причина исключения сохраняется.

## 11. Тестирование и его результаты

Результаты ниже взяты из фактического запуска **mvn clean test**
на Java 21, выполненного 22.10.2026. Полный прогон завершился успешно:

```text
Tests run: 349, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Дополнительно фактически выполнено `mvn package exec:java`: собран
`target/workflow-engine-1.0-SNAPSHOT.jar`, демонстрация завершилась `DEMO OK`
и Maven сообщил `BUILD SUCCESS`. Пользовательский data/store.json не создавался.

Разбиение по реальным XML-отчётам Maven Surefire:

| Класс | Случаев | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| CatalogServiceTest | 44 | 0 | 0 | 0 |
| DefaultConditionEvaluatorTest | 71 | 0 | 0 | 0 |
| DemoApplicationTest | 4 | 0 | 0 | 0 |
| WorkflowEngineTest | 22 | 0 | 0 | 0 |
| FileRepositoryTest | 14 | 0 | 0 | 0 |
| StandardOrderProcessTest | 35 | 0 | 0 | 0 |
| WorkflowAccessAndParametersTest | 47 | 0 | 0 | 0 |
| WorkflowLifecycleRequirementsTest | 22 | 0 | 0 | 0 |
| WorkflowQueueAndPersistenceTest | 12 | 0 | 0 | 0 |
| ConditionalStateMachineTest | 12 | 0 | 0 | 0 |
| DefaultStateMachineTest | 18 | 0 | 0 | 0 |
| ExecutionHooksStateMachineTest | 4 | 0 | 0 | 0 |
| QueuedStateMachineTest | 15 | 0 | 0 | 0 |
| TemplateCatalogServiceTest | 29 | 0 | 0 | 0 |

Всего 14 тестовых классов и 181 метод; параметризованные методы разворачиваются
в независимые случаи. Полный перечень методов с описаниями и матрица восьми групп
требований находятся в [testing.md](testing.md).

Проверены каждый из 19 переходов, полные маршруты, отложения и возвраты,
наследование и копирование, все операторы и ДНФ, права, редактирование,
аудит принятых и отклонённых операций, FIFO и динамическая регистрация,
остановка, перезапуск, ошибки записи и согласованность состояния.

Применяются реальные компоненты, @TempDir, фиксированный Clock, CountDownLatch
и ограниченные ожидания futures. Thread.sleep не используется. Для отказов
сравниваются снимки и байты JSON. Фабрика наблюдения не подменяет настоящий автомат.
Ошибка записи вводится на границе атомарного Files.move, а не мокированием всего Repository.

Четыре теста DemoApplication проверяют полный сценарий, обе OR-ветви,
историю 13/8, независимость последовательных запусков, запрет аргумента с путём
хранилища и отдельный запуск main в JVM с Windows-1251. Последний тест воспроизвёл
ошибку кодировки русского вывода; main исправлен явным UTF-8 PrintStream.
После исправления тест и весь набор проходят.

Шесть PlantUML-исходников фактически проверены PlantUML 1.2025.2 через -checkonly
и экспортированы в SVG. Диаграмма Б.3 содержит ровно 10 состояний и 19 бизнес-дуг.

Запуск защиты:

```shell
mvn clean test
mvn compile exec:java
```

Демо использует реальный seed, показывает четыре ожидаемых отказа,
исправление quantity=700 на 100, correction, две OR-ветви, завершает два заказа,
печатает их историю и отдельный динамический переход. Файлы остаются только
в новом demo/run-...; пользовательский data/store.json не затрагивается.
Неожиданная ошибка приводит к неуспешному Maven-запуску.

## 12. Распределение задач между тремя участниками

ФИО и фактический состав команды не предоставлены. Ниже — **предлагаемое
распределение**, а не утверждение о выполненном конкретными людьми вкладе.
Перед сдачей участники должны согласовать таблицу и указать настоящие ФИО.

| Участник | Зона ответственности | Материалы и проверки |
| --- | --- | --- |
| Участник 1 — ФИО уточнить | Метаданные, CatalogService, шаблоны, наследование и JSON-конфигурация Б.3. | Каталог, TemplateCatalogService, seed, orders.md, проверки наследования, диаграммы состояний и предметной иерархии. |
| Участник 2 — ФИО уточнить | Универсальный StateMachine, очередь, жизненный цикл worker, динамическая регистрация и ConditionEvaluator. | Конкурентные тесты, ДНФ, проверки ошибок handler, диаграммы классов и последовательности. |
| Участник 3 — ФИО уточнить | WorkflowEngine, FileRepository, атомарный аудит, интеграция и DemoApplication. | Проверки прав, хранения и перезапуска, Maven-запуск, README, сводная записка, диаграммы компонентов и хранения. |
| Совместно | Согласование контрактов, code review, полный прогон, репетиция защиты. | Проверка 19 дуг, сохранности данных и соответствия диаграмм коду. |

На защите каждый участник должен понимать весь путь executeAction,
а не только свой пакет: очередь → актуальный объект → права → условия →
handler → атомарный commit → состояние → future.

## 13. Заключение

Создан универсальный движок с предметными классами и процессом в данных.
Процесс Б.3 реализован без отдельных Java-классов заказов и специальных методов
согласования цены или поставки. Состояния и действия описаны строковыми id.
Наследование параметров и шаблонов, условия, права и аудит подтверждены тестами.

Очередь обеспечивает последовательность команд одного объекта; атомарная запись
защищает объект и историю от частичного сохранения. Демонстрация воспроизводит
успехи, отказы, исправления и динамическую регистрацию, не меняя пользовательские данные.

Ограничения учебной реализации: очередь в памяти и без ограничения размера,
platform worker на активный объект, общий файловый снимок вместо СУБД,
отсутствие межпроцессной координации, дедупликации, автоматической миграции шаблонов
и восстановления сообщений после аварии. Отмена future не отменяет команду;
зависший handler задерживает close. ATOMIC_MOVE не равнозначен отдельной гарантии
переживания отключения питания и fsync каталога.

Содержимое записки, PlantUML-исходники и SVG готовы для дальнейшего оформления.

