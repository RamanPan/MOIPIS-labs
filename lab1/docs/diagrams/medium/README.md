# Диаграммы 3-1-1 и 3-1-2 средней детализации

Схемы показывают классы и методы, которые участвуют в актуальных сценариях защиты. Порядок показа идёт по строкам таблиц: модель, состояния, выполнение операции. Готовые SVG можно открыть в браузере. Исходники PlantUML доступны рядом.

## 3-1-1. Исполнение процесса Б.3

| Схема | Для чего показать | Файлы |
| --- | --- | --- |
| Классы исполнителя | Различить готовый объект, шаблон по классу и снимок по ID заказа. Показать, где проверяются роли, условия и журналирование. | [SVG](task-3-1-1/rendered/classes.svg) · [PlantUML](task-3-1-1/classes.puml) |
| Состояния заказа Б.3 | Проследить основной маршрут, возвраты, отложения, роли этапов и условия G1–G5. | [SVG](task-3-1-1/rendered/states.svg) · [PlantUML](task-3-1-1/states.puml) |
| Обработка команды | Показать, как JSON доходит до сеанса, как различаются старт и действие и когда публикуется снимок. | [SVG](task-3-1-1/rendered/command.svg) · [PlantUML](task-3-1-1/command.puml) |

Код для сверки — [конфигурация Б.3](../../../src/main/resources/seed/task-3-1-1.json), [Task311Commands.submit](../../../src/main/java/ru/moipis/workflow/engine/Task311Commands.java#L52), [CoordinationSession.process](../../../src/main/java/ru/moipis/workflow/engine/CoordinationSession.java#L116).

## 3-1-2. Взаимодействие заказа и заказчика

| Схема | Для чего показать | Файлы |
| --- | --- | --- |
| Классы координации | Связать `RelatedProcess`, снимки, определения отправки и приёма события с очередью и журналом. | [SVG](task-3-1-2/rendered/classes.svg) · [PlantUML](task-3-1-2/classes.puml) |
| Состояния заказа | Объяснить общий guard для `submit` и `confirm`, затем две причины возврата из `ready`: действие и событие. | [SVG](task-3-1-2/rendered/order-states.svg) · [PlantUML](task-3-1-2/order-states.puml) |
| Состояния проверки заказчика | Показать, как `approve` разрешает повторный `submit`, а `revise` выдаёт событие адресатам из `orders`. | [SVG](task-3-1-2/rendered/company-states.svg) · [PlantUML](task-3-1-2/company-states.puml) |
| Доставка события | Различить отказ исходной команды и отказ доставки, показать порядок записей `ACTION` и `EVENT`. | [SVG](task-3-1-2/rendered/event-delivery.svg) · [PlantUML](task-3-1-2/event-delivery.puml) |

Код для сверки — [конфигурация 3-1-2](../../../src/main/resources/seed/coordination-3-1-2.json), [CoordinationSession.relatedMatches](../../../src/main/java/ru/moipis/workflow/engine/CoordinationSession.java#L219), [CoordinationSession.execute](../../../src/main/java/ru/moipis/workflow/engine/CoordinationSession.java#L106).

## Повторная генерация SVG

Команды для PowerShell из корня `lab1`, если JAR PlantUML находится в `.tools`.

```powershell
java '-Djava.awt.headless=true' -jar .tools/plantuml-1.2025.2.jar -charset UTF-8 -failfast2 -tsvg -o rendered 'docs/diagrams/medium/task-3-1-1/*.puml'
java '-Djava.awt.headless=true' -jar .tools/plantuml-1.2025.2.jar -charset UTF-8 -failfast2 -tsvg -o rendered 'docs/diagrams/medium/task-3-1-2/*.puml'
```
