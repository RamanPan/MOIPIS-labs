# Проверка WorkflowEngine по разделу 8 задания № 3-1-1

## Результат

Проверка на Java 21 и JUnit 5: `mvn clean test` — **BUILD SUCCESS**.
Выполнено **349 случаев**, ошибок, отказов assertions и пропусков нет.
Это **181 тестовый метод в 14 классах**; параметризованные методы создают
несколько независимых случаев. Исходные 264 случая сохранены, добавлен 81
случай комплексной проверки и четыре проверки демонстрации.
Набор из 12 асинхронных и файловых сценариев дополнительно успешно прогнан
три раза подряд.

В универсальных компонентах новых дефектов при этих проверках не выявлено.
При подготовке DemoApplication воспроизведён и исправлен вывод не в UTF-8
под Windows; добавлен регрессионный запуск main в отдельной JVM.
Основной seed и правила универсального движка не изменены.
Проверяется выполнение перечисленных требований, а не процент покрытия строк.

## Матрица требований

| Группа | Основные проверки |
| --- | --- |
| 1. Жизненный цикл | `WorkflowLifecycleRequirementsTest`: 19 отдельных переходов и два полных маршрута. Для каждого шага проверяются source, target, объект и история. Возвраты, отложения и возобновления входят в эти 19 случаев. |
| 2. Наследование | `CatalogServiceTest`, `TemplateCatalogServiceTest`, `WorkflowAccessAndParametersTest`: параметры предков, добавление и переопределение, запрет смены типа, ближайший шаблон, глубокая копия и отсутствие влияния на оригинал. |
| 3. Предикаты и ДНФ | `DefaultConditionEvaluatorTest`: filled, =, !=, <, <=, >, >=, in_range, AND/OR/NOT, отсутствие значений и классы. `StandardOrderProcessTest`: обе OR-ветви Б.3. `WorkflowQueueAndPersistenceTest`: неоднозначность через реальный движок. |
| 4. Права | `WorkflowAccessAndParametersTest`: пять ролей без права создания, правильные роли каждого перехода, чужая роль во всех девяти неконечных состояниях и пользователь с несколькими ролями. В final действия отсутствуют. |
| 5. Параметры | `WorkflowAccessAndParametersTest`: два разрешённых и восемь запрещённых состояний, неверные типы, неизвестные и чужие поля, создание вне диапазона, отказ и исправление. |
| 6. История | `WorkflowFixture` проверяет каждую операцию новых сценариев: ровно одна запись, id объекта и пользователя, уникальный id записи, фиксированное время, operation и точные details. Отказ оставляет неизменными весь StoreData и байты файла. |
| 7. Очередь | `WorkflowQueueAndPersistenceTest` и `QueuedStateMachineTest`: FIFO, отдельные workers, динамическая регистрация, исключение handler, graceful stop/close и завершение futures. |
| 8. Хранение | `WorkflowQueueAndPersistenceTest` и `FileRepositoryTest`: успешная запись, перезапуск, история и копии, ошибки записи трёх операций, атомарная замена и совпадение состояния автомата с объектом. |

## Методика и качество

- Все файловые проверки работают в `@TempDir`. Реальный `data/store.json` не создаётся и не изменяется.
- Используются настоящие CatalogService, TemplateCatalogService, DefaultConditionEvaluator,
  DefaultWorkflowEngine, DefaultStateMachine и FileRepository с Jackson.
- Запись ломается только во внедрённом AtomicFileMover: Repository, сериализация и
  файловое чтение остаются реальными. RecordingFactory лишь сохраняет ссылки на
  настоящие автоматы для наблюдения их состояния, а не подменяет их поведение.
- Время аудита задаётся Clock.fixed. Новые сценарии очищают только объекты и историю
  своего временного хранилища; классы, роли, пользователи и шаблон берутся из seed.
- Успешная CREATE проверяет начальные параметры и состояние; ACTION — действие и
  оба состояния; UPDATE_PARAMETER — id параметра, старое и новое значения.
  Проверяется неизменность чужих объектов, прежней истории и метаданных.
- При отказе проверяются код и понятное сообщение ошибки, весь объект, его параметры,
  история, весь JSON-снимок и соответствующее состояние автомата.
- Очередь контролируется CountDownLatch. Следующий handler можно удержать, чтобы
  проверить объект и историю после предыдущего сообщения до следующего commit.
- Нет Thread.sleep(), busy waiting или неограниченного ожидания CompletableFuture:
  используется get(5, TimeUnit.SECONDS). Новые классы имеют дополнительный @Timeout(20).
- Проверки регистрации нового состояния и перехода выполняются непосредственно на
  универсальном StateMachine: изменение сохранённого бизнес-шаблона во время работы
  объектов не является частью поддерживаемого API.
- Тесты не зависят от порядка запуска. Каждый набор параметров новых интеграционных
  сценариев создаёт собственное хранилище, движок и workers, освобождаемые в конце.

## Тестовые шаблоны

- `src/test/resources/templates/engine-template.json` — независимый универсальный процесс.
- `src/test/resources/templates/ambiguous-template.json` — два истинных условных
  перехода для проверки самого автомата.
- `src/test/resources/templates/ambiguous-workflow-template.json` — отдельный
  шаблон с ролями для проверки неоднозначности через WorkflowEngine. При quantity=120
  одновременно истинны quantity>=1 и quantity<=500; ожидается TEMPLATE_ERROR
  с причиной AmbiguousTransitionException, handler не вызывается, объект остаётся в start.
  Основной шаблон Б.3 этим тестом не изменяется.

## Полный перечень методов

Обозначение **P** означает `@ParameterizedTest`; каждая строка входных данных
запускается независимо. Обычные методы используют `@Test`.

### WorkflowLifecycleRequirementsTest

Исходник: [WorkflowLifecycleRequirementsTest](../src/test/java/ru/moipis/workflow/scenario/WorkflowLifecycleRequirementsTest.java).

| Метод | Проверка |
| --- | --- |
| `eachTransitionHasTheCorrectSourceTargetAndExactlyOneCompleteAuditRecord` **P** | 19 независимых случаев: source, target, пользователь, время и ровно одна ACTION. |
| `fullLifecycleChecksTheObjectAndHistoryAfterEveryOperation` **P** | Полные стандартный и офисный маршруты проверяют объект и аудит после каждого шага. |
| `completedObjectsRejectActionsWithoutChangingAnyData` | Действия из completed отклоняются с сохранением всего снимка и байтов файла. |

### WorkflowAccessAndParametersTest

Исходник: [WorkflowAccessAndParametersTest](../src/test/java/ru/moipis/workflow/scenario/WorkflowAccessAndParametersTest.java).

| Метод | Проверка |
| --- | --- |
| `creationWithoutACreationRoleLeavesObjectsAndHistoryUnchanged` **P** | Для пяти ролей без createRoles проверяет отказ и полную неизменность JSON. |
| `userWithSeveralRolesCanCreateAndCompleteTheProcess` | Пользователь с несколькими ролями создаёт объект и проходит весь процесс. |
| `wrongRoleCannotPerformActionsInAnyNonterminalState` **P** | Для девяти неконечных состояний проверяет отказ чужой роли и пустые действия. |
| `allowedEditingWritesOnlyTheNamedParameterAndOneAuditRecord` **P** | В двух разрешённых состояниях проверяет изменение только заданного параметра и аудит. |
| `forbiddenEditingDoesNotChangeTheObjectOrHistory` **P** | В восьми запрещённых состояниях, включая final, проверяет неизменность объекта и истории. |
| `invalidParameterTypesAreRejectedDuringCreationAndEditing` **P** | Проверяет шесть неверных значений типов при создании и редактировании. |
| `unknownAndSiblingOnlyParametersAreRejectedAtBothEntryPoints` **P** | Проверяет неизвестный параметр и поля другого подкласса через оба API. |
| `outOfRangeCreationIsAllowedButTransitionRequiresRepair` | Создание quantity=700 разрешено; переход отклоняется до исправления на 500. |
| `failedPredicatesRejectApprovalsWithoutAnyPartialOperation` **P** | Восемь неверных предикатов Б.3 отклоняют действия без частичной операции. |
| `inheritedAndSubclassParametersAreUsableWithoutASeparateJavaClass` | Проверяет редактирование наследуемых и офисных параметров и диапазон подкласса. |
| `copyingToASubclassLeavesItsSiblingAndExistingObjectsBoundToTheOriginal` | Копия переживает перезапуск, не меняет соседний класс и привязку существующего объекта. |
| `missingTemplateAndUnknownActionsHaveExplicitErrorsWithoutHistory` | Нет шаблона, неизвестен класс или действие — понятный отказ без истории. |

### WorkflowQueueAndPersistenceTest

Исходник: [WorkflowQueueAndPersistenceTest](../src/test/java/ru/moipis/workflow/scenario/WorkflowQueueAndPersistenceTest.java).

| Метод | Проверка |
| --- | --- |
| `fifoUsesOneWorkerAndALaterRegisteredHandlerForTheNextMessage` | Два принятых сообщения исполняются FIFO на одном worker; новый handler доступен следующему. |
| `handlerFailureLeavesNoAuditAndTheSameWorkerProcessesTheNextEvent` | Ошибка первого handler не пишет историю; следующий запрос обрабатывает тот же worker. |
| `blockedObjectDoesNotBlockAnotherObjectsWorkerOrState` | Блокировка одного объекта не блокирует другой worker и его сохранённое состояние. |
| `closingDrainsAcceptedEventsStopsWorkersAndLeavesNoPendingFutures` | close завершает очередь, останавливает workers, завершает futures и запрещает новый send. |
| `runningMachineAcceptsANewStateHandlerAndTransitionWithoutLockingTheHandler` | Во время работающего handler регистрируются новое состояние, handler и переход. |
| `failedAtomicWritePreservesTheFileObjectHistoryAndMachineAndCanBeRetried` **P** | Для CREATE, ACTION и UPDATE_PARAMETER ошибка записи сохраняет JSON и автомат; retry успешен. |
| `machinePublishesTheStateOnlyAfterAtomicReplacementFinishes` | До завершения атомарной замены ни future, ни состояние автомата не публикуются. |
| `copiedTemplateSaveFailureIsAtomicAndSuccessfulCopySurvivesRestart` | Неуспешная копия шаблона ничего не меняет; успешная сохраняется после перезапуска. |
| `restartRetainsUpdatedValuesAuditAndTheSavedStateAndExecutionContinues` | Параметры, история и состояние восстанавливаются, процесс продолжается до completed. |
| `twoMatchingTransitionsAreATemplateErrorRatherThanAFirstMatch` | Отдельный JSON-шаблон с двумя истинными дугами даёт TEMPLATE_ERROR без выбора первой. |

### CatalogServiceTest

Исходник: [CatalogServiceTest](../src/test/java/ru/moipis/workflow/catalog/CatalogServiceTest.java).

| Метод | Проверка |
| --- | --- |
| `returnsClassWithOnlyItsOwnParameters` | Возвращает только собственные определения параметров класса. |
| `inheritsParametersAcrossMultipleLevelsAndAddsOwnParameters` | Проверяет многоуровневое наследование и добавление собственных параметров. |
| `overridesRangeWithoutChangingParentOrSibling` | Переопределяет диапазон без изменения родителя и соседнего подкласса. |
| `replacesEntireDefinitionIncludingTitleAndAbsentBounds` | Полностью заменяет определение, включая название и отсутствующие границы. |
| `resolvesHierarchyRegardlessOfDefinitionOrder` | Разрешает иерархию независимо от порядка классов во входных данных. |
| `returnsAncestorsFromParentToRootWithoutTheClassItself` | Возвращает предков от ближайшего родителя к корню. |
| `checksClassMembershipInTheCorrectDirection` **P** | Проверяет принадлежность классу и направление наследования. |
| `rejectsUnknownClassInAllQueries` | Отклоняет неизвестный класс во всех запросах. |
| `rejectsUnknownParentAtConstruction` | Отклоняет неизвестного родителя при построении каталога. |
| `rejectsCyclesEvenInAnUnrelatedBranch` **P** | Обнаруживает циклы, включая циклы в другой ветви. |
| `rejectsTypeChangesAcrossAnIntermediateClass` **P** | Запрещает изменение типа при переопределении через промежуточный класс. |
| `rejectsDuplicateClassIds` | Отклоняет повторяющиеся id классов. |
| `rejectsDuplicateParametersWithinOneClass` | Отклоняет одинаковые id параметров внутри одного класса. |
| `allowsTheSameParameterIdWithDifferentTypesInUnrelatedClasses` | Разрешает разные типы одноимённых параметров в независимых классах. |
| `supportsOptionalNumericBounds` **P** | Поддерживает необязательные числовые границы. |
| `rejectsUnsupportedTypesAndInvalidBounds` **P** | Отклоняет неизвестные типы и некорректные диапазоны. |
| `acceptsAnEmptyCatalogAndRejectsLookup` | Разрешает пустой каталог, но отклоняет поиск в нём. |
| `isolatesCatalogFromChangesToInputCollections` | Изолирует каталог от изменения входных коллекций. |
| `doesNotAllowReturnedCollectionsToCorruptTheHierarchy` | Не позволяет изменять иерархию через возвращённые коллекции. |

### DefaultConditionEvaluatorTest

Исходник: [DefaultConditionEvaluatorTest](../src/test/java/ru/moipis/workflow/condition/DefaultConditionEvaluatorTest.java).

| Метод | Проверка |
| --- | --- |
| `filledMeansAssignedIncludingEmptyStringAndZero` | filled различает отсутствие и заданные значения, включая ноль и пустую строку. |
| `comparesStrings` **P** | Проверяет строковые операторы = и !=. |
| `comparesIntegerValuesExactly` **P** | Проверяет все числовые сравнения целых значений. |
| `comparesDecimalsUsingBigDecimal` **P** | Проверяет сравнения decimal через BigDecimal без строкового сравнения. |
| `doesNotLoseIntegerPrecisionBeyondLongOrDoubleRange` | Сохраняет точность целых вне диапазона long и точности double. |
| `inRangeUsesInclusiveBounds` **P** | in_range включает обе граничные точки. |
| `inRangeUsesOverriddenBoundsOfTheActualSubclass` | in_range использует переопределение реального подкласса объекта. |
| `inRangeAllowsEitherBoundOrBothToBeAbsent` **P** | in_range работает с одной границей и без границ. |
| `absentOrNullValuesAreFalseForEveryParameterPredicate` **P** | Отсутствие и null дают false для предикатов параметра. |
| `classPredicateIncludesTheClassAndItsDescendants` **P** | Предикат класса учитывает сам класс и его потомков. |
| `computesAnd` **P** | Проверяет все комбинации AND. |
| `computesOrUsingEitherBranch` **P** | Проверяет независимый успех каждой OR-ветви. |
| `computesNotIncludingAnAbsentValueAndAFullDnfExpression` | Проверяет NOT, отрицание отсутствующего значения и составную ДНФ. |
| `supportsUnconditionalAndEmptyBooleanExpressions` | Проверяет отсутствие условия и пустые логические выражения. |
| `rejectsInvalidTypesOperatorsOperandsAndReferences` | Отклоняет неверные типы, операторы, операнды и ссылки. |

### WorkflowEngineTest

Исходник: [WorkflowEngineTest](../src/test/java/ru/moipis/workflow/engine/WorkflowEngineTest.java).

| Метод | Проверка |
| --- | --- |
| `createsInheritedObjectWithoutCheckingRangesAndWritesOneHistoryEntry` | Создаёт объект по наследуемому шаблону без проверки диапазонов и с одной CREATE. |
| `creationRejectsUnknownClassTemplateUserRoleParameterAndTypeWithoutWriting` | Проверяет ошибки класса, шаблона, пользователя, роли, параметра и типа без записи. |
| `actionsCheckRightsConditionsAndCallDynamicHandlerOnlyAfterValidation` | Проверяет права и условия до вызова динамического обработчика. |
| `editingChecksRightsTypeAndPolicyButNotRanges` | Проверяет права, типы и политику редактирования без проверки min/max. |
| `queuedActionUsesParametersFromPrecedingUpdate` | Событие действия видит параметры, изменённые предыдущим сообщением очереди. |
| `finalStateForbidsActionsAndEditing` | Конечное состояние запрещает действия и редактирование. |
| `handlerFailureDoesNotCommitAndWorkerAcceptsReplacementHandler` | Ошибка handler не фиксирует операцию; заменённый handler позволяет продолжить. |
| `failedSaveLeavesBothMachineAndRepositoryUnchangedAndAllowsRetry` | Ошибка записи сохраняет файл и состояние автомата; повтор операции успешен. |
| `restartRestoresPersistedStateRatherThanInitialState` | Перезапуск восстанавливает сохранённое, а не начальное состояние. |
| `objectsHaveIndependentWorkersAndCloseDrainsAcceptedEvents` | Разные объекты имеют независимые workers; close завершает принятую очередь. |
| `ambiguousTemplateRejectsOperationBeforeCallingHandler` | Неоднозначность шаблона отклоняется до вызова handler. |
| `validatesExactNumericTypesAndAcceptsNullAndOutOfRangeValues` | Проверяет точные числовые типы, null и значения вне диапазона. |
| `ownCopiedTemplateControlsCreationAndExistingObjectsKeepTheirTemplate` | Копия шаблона задаёт собственные createRoles и не меняет привязку старых объектов. |
| `selectiveLegacyEditingPolicyRemainsSupported` | Сохраняет поддержку списка разрешённых параметров вместо boolean-политики. |
| `handlerRegistrationDuringExecutionAppliesToFollowingMessage` | Замена handler во время операции влияет только на следующие сообщения. |
| `failedCreateAndUpdateLeaveNoPartialObjectOrHistory` | Ошибка создания и редактирования не оставляет частичного объекта или истории. |
| `parallelObjectsDoNotLoseUpdatesAndEveryOperationHasOneHistoryEntry` | Параллельные объекты не теряют изменения и получают одну историю на операцию. |
| `changedSavedStateIsReportedRatherThanOverwrittenByStaleMachine` | Обнаруживает рассогласованное сохранённое состояние вместо его перезаписи. |
| `unknownObjectsAndUsersHaveExplicitErrors` | Возвращает явные ошибки для неизвестных объектов и пользователей. |
| `closingFromHandlerIsRejectedWithoutDeadlock` | Закрытие из handler отклоняется без взаимной блокировки. |
| `defaultWiringInitializesRepositoryAndExecutesProcess` | Проверяет стандартную сборку компонентов через конструктор с Repository. |
| `closeDrainsAcceptedCreationsAndRejectsFurtherCreations` | close завершает принятые создания и запрещает последующие. |

### FileRepositoryTest

Исходник: [FileRepositoryTest](../src/test/java/ru/moipis/workflow/repository/file/FileRepositoryTest.java).

| Метод | Проверка |
| --- | --- |
| `initializesMissingStoreFromClasspathSeed` | Инициализирует новый файл из полного seed, включая демонстрации и историю. |
| `existingStoreAndHistoryAreNeverReplacedWithSeed` | Существующий JSON и историю не заменяет данными seed. |
| `createAtomicallyStoresObjectAndExactlyOneHistoryEntryWithInjectedTime` | Атомарно сохраняет объект и одну CREATE с внедрённым Clock. |
| `actionAndParameterUpdateEachCreateOneCorrespondingHistoryEntry` | ACTION и UPDATE_PARAMETER формируют ровно по одной соответствующей записи. |
| `rejectedStaleAndInvalidChangesDoNotModifyObjectsOrHistory` | Устаревшие и недопустимые изменения не меняют объекты или историю. |
| `writeFailureLeavesBothDiskAndPublishedSnapshotUnchangedAndCleansTemporaryFile` | Ошибка записи сохраняет файл и снимок, временный файл удаляется. |
| `unavailableAtomicMoveIsReportedWithoutFallback` | Недоступный ATOMIC_MOVE даёт ошибку без неатомарного fallback. |
| `atomicReplacementReceivesObjectAndHistoryInTheSameTemporaryDocument` | Временный документ содержит объект и историю одной операции одновременно. |
| `parallelCommitsForDifferentObjectsDoNotLoseAnyChanges` | Параллельные commit разных объектов не теряют изменения. |
| `copiedTemplatesUsersRolesObjectsAndHistorySurviveRestart` | Копии шаблонов, метаданные, объекты и история переживают перезапуск. |
| `exactDecimalValuesAndAllNestedDataAreIsolatedFromCallers` | Точные decimal и вложенные снимки изолированы от вызывающего кода. |
| `corruptedExistingStoreIsNeverResetFromSeed` | Повреждённый существующий JSON не подменяется seed. |
| `failedPreparationDoesNotPublishMutations` | Ошибка подготовки не публикует изменения снимка. |
| `rejectsNestedWritesInsteadOfOverwritingAnInnerCommit` | Вложенная запись запрещена, чтобы внешний commit не затёр внутренний. |

### StandardOrderProcessTest

Исходник: [StandardOrderProcessTest](../src/test/java/ru/moipis/workflow/scenario/StandardOrderProcessTest.java).

| Метод | Проверка |
| --- | --- |
| `loadsExactlyTenStatesAndTheNineteenSpecifiedTransitions` | Проверяет ровно 10 состояний, начальное и конечное состояния и все 19 дуг Б.3. |
| `rolesUsersAndEditingPermissionsMatchTheProcess` | Проверяет роли, пользователей, createRoles и editParameters каждого состояния. |
| `subclassesInheritAndConsumablesCanGetTheirOwnCopiedTemplate` | Проверяет наследование и возможность собственной копии для consumables_order. |
| `officeQuantityUsesOverriddenRangeAndFailedActionDoesNotChangeHistory` | Количество 700 блокирует офисный заказ; исправление на 500 разрешает согласование. |
| `bothSuccessfulPaymentScenariosCompleteThroughTheUniversalEngine` **P** | Стандартный и офисный заказы завершают процесс через разные сценарии оплаты. |
| `paymentConditionUsesBothOrBranchesClassPredicateAndNumericComparison` **P** | Проверяет обе OR-ветви оплаты, классовый предикат и пределы 30/50 процентов. |
| `officeExceptionIsOneDnfConditionNotTwoAmbiguousTransitions` | Две истинные OR-группы принадлежат одной дуге и не создают неоднозначность. |
| `compositionAndRejectsEveryInvalidConjunct` **P** | Каждый ложный конъюнкт состава отдельно блокирует AND. |
| `priceAndDeliveryRangesBlockTheirOwnApprovals` **P** | Проверяет отсутствие и превышение диапазонов суммы и срока поставки. |
| `notBlockReasonAndEveryPaymentConjunctAreEnforced` | Проверяет NOT причины блокировки и остальные конъюнкты оплаты. |
| `completionRequiresEveryMandatoryParameter` **P** | Последовательно исключает каждый обязательный параметр завершения. |
| `invalidDemoOrdersAreBlockedAtTheirExpectedCheckpoints` | Ошибочные демонстрации останавливаются на ожидаемых этапах. |
| `executesEveryCorrectionAndPostponementTransition` | Исполняет все возвраты и отложенные маршруты и сравнивает посещённые дуги. |
| `stateRolesAndEditingRulesAreEnforcedWithSeedUsers` | Проверяет права и редактирование с пользователями из seed. |
| `demoObjectsAndTheirCreationHistoryLoadAndSurviveRestart` | Проверяет начальную CREATE демонстраций и продолжение после перезапуска. |

### ConditionalStateMachineTest

Исходник: [ConditionalStateMachineTest](../src/test/java/ru/moipis/workflow/statemachine/ConditionalStateMachineTest.java).

| Метод | Проверка |
| --- | --- |
| `inheritedTemplateUsesTheActualObjectClassAndItsOverriddenRange` | Наследуемый шаблон использует реальный класс и переопределённый диапазон. |
| `classPredicateCanSelectAConcreteSubclass` | Классовый предикат выбирает конкретный подкласс. |
| `rejectsZeroMatchesBeforeHandlerAndContinuesAfterRefusal` | Ноль истинных переходов отклоняется до handler; worker продолжает работу. |
| `dedicatedAmbiguousTemplateRejectsTwoTrueTransitionsInsteadOfChoosingTheFirst` | Специальный шаблон с двумя истинными условиями даёт ошибку неоднозначности. |
| `unconditionalTransitionDoesNotRequireAnObjectContext` | Безусловный переход не требует контекста объекта. |
| `unconditionalTransitionDoesNotTakePriorityOverATrueConditionalOne` | Безусловная дуга не имеет приоритета над истинной условной дугой. |
| `conditionalTransitionRequiresObjectContextAndLeavesWorkerUsable` | Отсутствие контекста отклоняет условный переход, но не останавливает worker. |
| `queuedConditionUsesTheAcceptedObjectSnapshot` | Условие в очереди использует принятый снимок контекста. |
| `evaluatesEveryCandidateEvenAfterTheFirstTwoMatch` | Вычисляются все кандидаты, даже после двух уже найденных совпадений. |
| `configurationIsNotLockedDuringEvaluationAndNewTransitionsAffectOnlyLaterMessages` | Вычисление не блокирует реестр, новые дуги действуют только для следующих событий. |
| `templateCopyAndJsonRoundTripPreserveClassPredicates` | Копирование и JSON round-trip сохраняют классовые предикаты. |
| `templateCatalogRejectsUnknownClassPredicateReferences` | Каталог отклоняет ссылку предиката на неизвестный класс. |

### DefaultStateMachineTest

Исходник: [DefaultStateMachineTest](../src/test/java/ru/moipis/workflow/statemachine/DefaultStateMachineTest.java).

| Метод | Проверка |
| --- | --- |
| `registersInitialAndAdditionalStatesWithoutChangingCurrentState` | Регистрация начального и дополнительных состояний не меняет текущее состояние. |
| `eachFactoryInstanceHasIndependentStateAndConfiguration` | Экземпляры фабрики имеют независимые состояние и конфигурацию. |
| `handlerReturnsAResultWithoutAStateChange` | Handler возвращает результат без перехода. |
| `runsHandlerBeforeCommittingTheConfiguredTransition` | Handler исполняется до фиксации выбранного состояния. |
| `supportsTransitionWithoutHandlerAndSelfTransition` | Разрешены дуга без handler и самопереход. |
| `rejectsInvalidEventAndKeepsState` | Недопустимое событие сохраняет состояние. |
| `handlerExceptionDoesNotChangeStateAndProcessingCanContinue` | Исключение handler сохраняет состояние и не запрещает продолжение. |
| `supportsDynamicRegistrationAfterProcessingHasStarted` | Поддерживается динамическая регистрация после начала обработки. |
| `handlersAreScopedToTheirStateAndEvent` | Handler привязан к паре состояние/событие. |
| `retainsMultipleConditionalTransitionsForOneKey` | Для одной пары сохраняется список всех условных переходов. |
| `rejectsAmbiguousUnconditionalTransitionsBeforeCallingHandler` | Несколько безусловных дуг отклоняются до handler. |
| `rejectsConditionalTransitionsWhenNoEvaluatorIsConfigured` | Условный переход без evaluator отклоняется. |
| `rejectsDuplicateStatesHandlersAndUnknownTransitionReferences` | Отклоняются повторные регистрации и ссылки на неизвестные состояния. |
| `rejectsNullAndBlankInputs` | Отклоняются null и пустые идентификаторы. |
| `snapshotsStateCollectionsAndTransitionConditions` | Конфигурация изолируется от изменения исходных коллекций и условий. |
| `doesNotHoldConfigurationLockDuringHandlerAndUsesAnEventSnapshot` | Во время handler доступна регистрация; событие использует согласованный снимок. |
| `serializesConcurrentEventsWithoutLosingStateChanges` | Конкурентные события сериализуются без потери изменений состояния. |
| `forbidsReentrantEventProcessingSoAFailedHandlerCannotChangeState` | Рекурсивная блокирующая обработка на своём worker запрещена. |

### ExecutionHooksStateMachineTest

Исходник: [ExecutionHooksStateMachineTest](../src/test/java/ru/moipis/workflow/statemachine/ExecutionHooksStateMachineTest.java).

| Метод | Проверка |
| --- | --- |
| `committerRunsAfterHandlerButBeforeStatePublication` | Порядок prepare → handler → commit → публикация состояния строго соблюдается. |
| `failedCommitDoesNotPublishStateAndNextEventCanRetry` | Неуспешный commit не меняет состояние, следующая попытка может пройти. |
| `hooksBelongToOneConfigurationSnapshotAndRegistrationDoesNotBlock` | Hooks входят в один снимок конфигурации; регистрация во время подготовки доступна. |
| `preparationCannotChangeEventTypeOrInvokeHandlerAfterRejection` | Preparer не может изменить тип события; после отказа handler не вызывается. |

### QueuedStateMachineTest

Исходник: [QueuedStateMachineTest](../src/test/java/ru/moipis/workflow/statemachine/QueuedStateMachineTest.java).

| Метод | Проверка |
| --- | --- |
| `processesMessagesInFifoOrderOnOneWorkerInsteadOfTheSenderThread` | FIFO исполняется на одном worker, а не в вызывающем потоке. |
| `processesMultipleTransitionsAndHandlerOnlyEvents` | Проверяет последовательные переходы и события только с handler. |
| `failureLeavesStateUnchangedAndWorkerProcessesLaterMessages` | Ошибка сохраняет состояние; worker обрабатывает последующие сообщения. |
| `keepsStateUnchangedWhileHandlerIsRunning` | Пока handler работает, состояние остаётся прежним. |
| `configurationChangesAffectTheNextQueuedMessageButNotTheRunningOne` | Обновлённая конфигурация влияет на следующее, но не на текущее сообщение. |
| `stopDrainsTheQueueAndCompletesEveryAcceptedFuture` | stop завершает принятую очередь и все соответствующие futures. |
| `rejectsSendsBeforeStartAndAfterStoppingWithoutPendingFutures` | До start и после stop send отклоняется завершённой ошибкой. |
| `stopBeforeStartIsSafeAndTerminal` | stop до start безопасен и запрещает последующий запуск. |
| `repeatedConcurrentStartsUseOnlyOneWorker` | Конкурентные start создают только один worker. |
| `sendRacingWithStopIsEitherProcessedOrRejectedAndNeverHangs` | Гонка send/stop всегда заканчивается обработкой или отказом, без зависания. |
| `stopFromACompletionCallbackDoesNotJoinItsOwnWorkerAndRejectsNewSends` | stop из callback не ожидает свой worker и запрещает новые события. |
| `aCancelledFutureDoesNotDiscardAnAcceptedEvent` | Отмена future не удаляет уже принятое событие. |
| `snapshotsPayloadWhenTheEventIsAccepted` | Payload копируется при принятии сообщения. |
| `unexpectedWorkerInterruptionCompletesPendingFuturesExceptionally` | Неожиданное прерывание worker завершает ожидающие futures ошибкой. |
| `stoppingFromAnInterruptedCallerStillDrainsAndRestoresTheInterruptFlag` | Прерванный вызывающий поток всё равно дожидается stop и сохраняет interrupt-флаг. |

### TemplateCatalogServiceTest

Исходник: [TemplateCatalogServiceTest](../src/test/java/ru/moipis/workflow/template/TemplateCatalogServiceTest.java).

| Метод | Проверка |
| --- | --- |
| `resolvesOwnTemplateAndInheritedTemplate` | Разрешает собственный и наследуемый шаблоны. |
| `choosesTheClosestAncestor` | Выбирает ближайшего предка с собственным шаблоном. |
| `rejectsResolutionWithoutAnyTemplate` | Отклоняет класс без собственного или наследуемого шаблона. |
| `rejectsUnknownTemplateAndClass` | Отклоняет неизвестные шаблон и класс. |
| `copiesTemplateAndPersistsItWithoutChangingTheOriginalOrSibling` | Сохраняет копию без изменения оригинала и соседнего подкласса. |
| `cannotCreateASecondOwnTemplate` | Запрещает второй собственный шаблон класса. |
| `rejectsDuplicateAndBlankNewTemplateIdsWithoutSaving` | Отклоняет повторный или пустой id копии без сохранения. |
| `deepCopiesMutablePredicateValuesAndNestedCollections` | Глубоко копирует изменяемые значения предикатов и вложенные коллекции. |
| `changingTheStoredSourceAfterCopyDoesNotChangeTheStoredCopy` | Изменение сохранённого источника не меняет сохранённую копию. |
| `rejectsCopyWhenTheTargetClassLacksAReferencedParameter` | Запрещает копию, если целевой класс не содержит используемый параметр. |
| `isolatesReturnedTemplatesFromRepositoryState` | Возвращённые шаблоны не позволяют изменить состояние репозитория. |
| `preservesMetadataObjectsAndHistoryWhenSavingACopy` | Копирование сохраняет прочие метаданные, объекты и историю. |
| `doesNotPublishACopyWhenRepositorySaveFails` | Не публикует копию при ошибке записи репозитория. |
| `preparesConfigurationForTheActualClassWithoutExecutingPredicates` | Готовит конфигурацию реального класса без вычисления предикатов. |
| `allowsIncompleteTemplatesAtLoadButRequiresOneInitialStateToPrepareConfiguration` | Загрузка не требует полной валидации, но конфигурации нужно одно initial-состояние. |
| `persistsCopiesThroughTheRepositoryAndSupportsJsonRoundTrip` | Проверяет сохранение копии и обратное чтение JSON через Repository. |
| `rejectsDuplicateTemplateIdsAndMultipleOwnTemplatesAtLoad` | Запрещает повторные id шаблонов и несколько собственных шаблонов класса. |
| `rejectsBrokenReferencesAndDuplicateElementIdsAtLoad` **P** | Отклоняет неверные ссылки и повторные id элементов шаблона. |

### DemoApplicationTest

Исходник: [DemoApplicationTest](../src/test/java/ru/moipis/workflow/demo/DemoApplicationTest.java).

| Метод | Проверка |
| --- | --- |
| `runsTheRealProcessWithExpectedRejectionsBranchesAndHistory` | Реальный процесс, четыре ожидаемых отказа, обе OR-ветви, final, неизменённый шаблон и история 13/8. |
| `repeatedRunsUseNewDirectoriesAndNeverOverwriteAnExistingStore` | Повторный запуск создаёт отдельный каталог; предыдущий снимок и пользовательский файл не перезаписываются. |
| `mainWritesUtf8AndKeepsDefaultFilesInsideItsOwnWorkingDirectory` | Отдельная JVM с Windows-1251: main выдаёт UTF-8, завершает workers и использует demo-каталог, не трогая data/store.json. |
| `refusesAnExplicitStoreArgumentRatherThanRiskingUserData` | Аргумент с путём существующего хранилища отклоняется до файловых операций. |

## Повторный запуск

```shell
mvn clean test
```

Подробные результаты JUnit создаются Maven Surefire в `target/surefire-reports/`.

