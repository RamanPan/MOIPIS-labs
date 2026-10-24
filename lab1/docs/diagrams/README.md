# Диаграммы проекта

Все схемы подготовлены в PlantUML. Исходники — редактируемая версия;
SVG в `rendered/` можно открыть в браузере и позднее перенести в Word.
Word и PDF в проекте не создаются.

Для быстрого знакомства с системой подготовлены [упрощённые версии](simple/README.md):
меньше деталей, крупные подписи и основные связи. Все исходные подробные схемы
остаются здесь; упрощения находятся в подпапке `simple/` вместе с отдельными SVG.

| № | Содержание | Исходник | Готовый SVG |
| --- | --- | --- | --- |
| 1 | Основные классы, атрибуты, методы и связи | [classes.puml](classes.puml) | [classes.svg](rendered/classes.svg) |
| 2 | Все 10 состояний и 19 переходов Б.3 | [b3-states.puml](b3-states.puml) | [b3-states.svg](rendered/b3-states.svg) |
| 3 | Компоненты и зависимости | [components.puml](components.puml) | [components.svg](rendered/components.svg) |
| 4 | executeAction: очередь, worker, проверки, commit и future | [execute-action.puml](execute-action.puml) | [execute-action.svg](rendered/execute-action.svg) |
| 5 | JSON, ссылки, наследование, ДНФ и история | [storage.puml](storage.puml) | [storage.svg](rendered/storage.svg) |
| 6 | Предметная иерархия как данные, без Java-подклассов | [domain-hierarchy.puml](domain-hierarchy.puml) | [domain-hierarchy.svg](rendered/domain-hierarchy.svg) |

На диаграмме состояний [*] обозначает начало/завершение и не увеличивает
количество бизнес-состояний или переходов. G1..G5 — сокращения условий для
рисунка; полные ДНФ приведены в легенде и соответствуют seed.

Диаграмма классов показывает основные типы по компонентам; точные подпакеты
model/event/handler/factory/exception перечислены в [packages.md](../packages.md).
Большую схему классов удобно просматривать с масштабированием SVG; при переносе
в записку можно использовать альбомную страницу или вынести её в приложение.

## Проверка и повторная генерация

Проверено PlantUML 1.2025.2: все шесть исходников проходят -checkonly и
экспортируются в SVG. Для графовых схем включён встроенный Smetana, поэтому
отдельная установка Graphviz для этих файлов не нужна.

Получите PlantUML JAR отдельно; он не включён в зависимости WorkflowEngine.
Из корня проекта, заменив `path/to/plantuml.jar` своим путём:

```shell
java -Djava.awt.headless=true -jar path/to/plantuml.jar -charset UTF-8 -checkonly "docs/diagrams/*.puml"
java -Djava.awt.headless=true -jar path/to/plantuml.jar -charset UTF-8 -tsvg -o rendered "docs/diagrams/*.puml"
```

В PowerShell аргумент JVM следует заключить в кавычки: `'-Djava.awt.headless=true'`.
Путь rendered разрешается относительно каталога исходных .puml.
Для проверки исходников и экспорта Maven, сервер PlantUML и сеть не требуются,
если JAR уже загружен. Возможен просмотр через PlantUML-расширение IDE.

Официальные справочники: [CLI](https://plantuml.com/command-line),
[состояния](https://plantuml.com/state-diagram).
