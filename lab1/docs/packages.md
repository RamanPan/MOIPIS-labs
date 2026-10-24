# Организация пакетов

Базовый пакет — `ru.moipis.workflow`. Структура организована по компонентам,
а не по общим категориям `service`, `impl` или `util`. Внутри компонентов
модели, ошибки и инфраструктурные реализации отделены от сервисов.

```text
ru.moipis.workflow
├── demo
│   └── DemoApplication
├── catalog
│   ├── Catalog
│   ├── CatalogService
│   └── model
│       ├── ObjectClassDefinition
│       ├── ParameterDefinition
│       ├── RoleDefinition
│       └── UserDefinition
├── template
│   ├── TemplateCatalogService
│   └── model
│       ├── ActionDefinition
│       ├── ParameterEditPolicy
│       ├── ProcessTemplate
│       ├── StateDefinition
│       └── TransitionDefinition
├── condition
│   ├── ConditionEvaluator
│   ├── DefaultConditionEvaluator
│   └── model
│       ├── ConditionContext
│       ├── ConditionLiteral
│       ├── DnfCondition
│       └── PredicateDefinition
├── statemachine
│   ├── StateMachine
│   ├── DefaultStateMachine
│   ├── StateMachineLifecycle
│   ├── StateEventKey
│   ├── event
│   │   ├── Event
│   │   ├── EventEnvelope
│   │   ├── EventResult
│   │   ├── EventProcessingResult
│   │   └── InitiatorContext
│   ├── handler
│   │   ├── EventHandler
│   │   ├── EventPreparer
│   │   ├── EventCommitter
│   │   └── TransitionGuard
│   ├── factory
│   │   ├── StateMachineFactory
│   │   ├── DefaultStateMachineFactory
│   │   └── StateMachineConfiguration
│   └── exception
│       ├── AmbiguousTransitionException
│       └── NoApplicableTransitionException
├── engine
│   ├── WorkflowEngine
│   ├── DefaultWorkflowEngine
│   ├── ObjectRuntime
│   ├── ParameterValues
│   ├── model
│   │   ├── BusinessObject
│   │   └── HistoryEntry
│   └── exception
│       ├── WorkflowException
│       └── WorkflowError
└── repository
    ├── Repository
    ├── model
    │   ├── StoreData
    │   └── ObjectChange
    └── file
        ├── FileRepository
        ├── AtomicFileMover
        └── ObjectChangeApplier
```

## Правила размещения

- Определения классов, параметров, пользователей и ролей принадлежат `catalog.model`.
- Определения процесса принадлежат `template.model`; логические выражения и
  предикаты — `condition.model`. Модели не зависят от реализаций сервисов.
- События, результаты и инициатор принадлежат `statemachine.event`;
  функциональные обработчики и hooks — `statemachine.handler`.
- Фабрика связывает шаблон и вычислитель условий с универсальным автоматом;
  этот адаптирующий код размещён отдельно в `statemachine.factory`.
- Объект процесса и его история принадлежат `engine.model`, а не снимку хранилища.
- `Repository` — публичная граница хранения. Только `repository.file` отвечает
  за Jackson, файловую замену и подготовку файлового снимка. Движок зависит от
  интерфейса Repository, а не от FileRepository.
- `ObjectRuntime`, `ParameterValues`, `ObjectChangeApplier` остаются package-private
  рядом с использующими их реализациями. Ради подпакетов доступ к ним не расширяется.
- Новые ошибки размещаются в `exception` своего компонента, а не в общем пакете ошибок.
- Тесты повторяют пакет тестируемого компонента: тесты шаблонов — `template`,
  файлового адаптера — `repository.file`, автомата — `statemachine`.
- Межкомпонентные сценарии расположены в тестовом пакете `scenario`;
  `WorkflowFixture` объединяет реальные компоненты и проверки объекта с историей.

Общий пакет `ru.moipis.workflow.model` удалён. Перенос меняет Java-импорты,
но не имена классов, алгоритмы, состав параметров методов или структуру JSON.
Seed, существующие store.json и тестовые шаблоны не требуют миграции.
