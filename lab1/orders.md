# Демонстрационные заказы Б.3

Источник всех данных — `src/main/resources/seed/initial-data.json`.
Этот файл поясняет конфигурацию, но не заменяет её.

Шаблон `standard_order_supply` принадлежит `standard_order`. Офисные заказы
наследуют его, используя собственное определение quantity с диапазоном 1..500.
Все пять заказов находятся в начальном состоянии registration. У каждого есть
одна запись CREATE от `user_manager` на `2026-10-22T00:00:00Z` с начальными
параметрами и состоянием. Последующие операции добавляет WorkflowEngine.

## Б3-001 — корректный стандартный заказ

Идентификатор: `demo-standard-valid`. Класс: `standard_order`.

Заказ на **800 металлических стеллажей** у ООО «Стандарт» общей стоимостью
**1 200 000**. Поставка через **20 дней**, предоплата **20%**.
Причина блокировки не задана: `block_reason = null`.

Все обязательные параметры заполнены. Количество допустимо для standard_order:
его диапазон — 1..10000. Заказ проходит весь основной маршрут. Оплата согласуется
через первую OR-ветвь: сумма допустима, предоплата не больше 30%, блокировки нет.
Предикат is_office_order ложен.

## Б3-002 — корректный офисный заказ

Идентификатор: `demo-office-valid`. Класс: `office_order`.

Заказ на **120 офисных кресел** у ООО «Офис» общей стоимостью **360 000**.
Поставка через **14 дней**, предоплата **40%**, блокировки нет.
`office_location` — «Москва, центральный офис».

Количество входит в переопределённый диапазон 1..500. Заказ проходит весь
основной маршрут. Для оплаты первая OR-ветвь ложна: 40% больше 30%.
Вторая ветвь истинна: это офисный заказ, сумма допустима, предоплата не больше
50%, блокировки нет. Это отдельный успешный сценарий, а не копия Б3-001.

## Б3-003 — превышен диапазон офисного заказа

Идентификатор: `demo-office-quantity-700`. Класс: `office_order`.

Параметры соответствуют Б3-002, но **quantity = 700**. Сумма остаётся 360 000:
модель не задаёт стоимость единицы или формулу расчёта суммы, total_amount
хранится как самостоятельный параметр.

complete переводит заказ из registration в composition_approval.
approve менеджера отклоняется: количество не входит в 1..500. Применение
родительского диапазона 1..10000 ошибочно пропустило бы этот заказ.
При отказе состояние и история остаются прежними.

Исправление: менеджер меняет quantity на 500 или меньше в composition_approval
и повторяет approve. Само сохранение значения 700 разрешено: диапазоны проверяют
предикаты переходов, а не создание или редактирование объекта.

## Б3-004 — не задано наименование продукции

Идентификатор: `demo-missing-product`. Класс: `standard_order`.

Заказ у ООО «Стандарт»: количество **100**, сумма **1 200 000**, срок **20 дней**,
предоплата **20%**, блокировки нет. **product_name = null**.

После complete заказ находится в composition_approval. approve отклоняется:
product_name_filled ложно. Остальные истинные проверки AND-группы не могут
компенсировать отсутствие продукции.

Исправление: менеджер заполняет product_name и повторяет approve.

## Б3-005 — недопустимая предоплата

Идентификатор: `demo-invalid-prepayment`. Класс: `standard_order`.

Заказ на **100 металлических стеллажей** у ООО «Стандарт» общей стоимостью
**1 200 000**. Поставка через **20 дней**, предоплата **60%**, блокировки нет.

Состав, цена и поставка согласуются. В payment_approval approve финансового
специалиста отклоняется: 60% больше 30%, а офисное исключение не применимо.
60 находится в диапазоне определения параметра 0..100, но не удовлетворяет
условию согласования оплаты.

На этапе оплаты редактирование запрещено. Финансовый специалист выполняет
correction, менеджер меняет предоплату на 30% или меньше в composition_approval,
после чего повторяются согласования состава, цены, поставки и оплаты.

## Пользователи

| userId | Роль | Где работает |
| --- | --- | --- |
| `user_manager` | `order_manager` | Создание, регистрация, состав, отложенное рассмотрение |
| `user_procurement` | `procurement_specialist` | Цена |
| `user_logistics` | `logistics_specialist` | График поставки |
| `user_finance` | `finance_specialist` | График оплаты |
| `user_head` | `procurement_head` | Решение о выполнении, отложенное выполнение |
| `user_executor` | `executor` | Выполнение |
| `user_coordinator` | Все шесть ролей | Демонстрация полного маршрута одним пользователем |

Достаточно одной подходящей роли. В completed никакая роль не разрешает действия
и редактирование. Редактировать параметры можно только в registration и
composition_approval; остальные этапы используют предусмотренные возвраты.

## Основной маршрут

| № | Исходное состояние | Действие | Пользователь | Новое состояние |
| --- | --- | --- | --- | --- |
| 1 | registration | complete | user_manager | composition_approval |
| 2 | composition_approval | approve | user_manager | price_approval |
| 3 | price_approval | approve | user_procurement | delivery_approval |
| 4 | delivery_approval | approve | user_logistics | payment_approval |
| 5 | payment_approval | approve | user_finance | execution_decision |
| 6 | execution_decision | execute | user_head | execution |
| 7 | execution | complete | user_executor | completed |

Смысл действия определяется текущим состоянием: complete завершает регистрацию
либо выполнение, approve используется на четырёх этапах согласования.
Обе успешные демонстрации заканчиваются в completed. Семь действий добавляют
семь ACTION к исходной CREATE, итого восемь записей истории на заказ.
Отклонённые действия историю не увеличивают.

## Проверки

- Состав: заполнены номер, продукция и поставщик; количество в эффективном диапазоне.
- Цена: сумма задана и входит в 1..2000000.
- Поставка: срок задан и входит в 1..60 дней.
- Оплата: `(amount_in_range AND prepayment_le_30 AND NOT block_reason_filled)`
  OR `(is_office_order AND amount_in_range AND prepayment_le_50 AND NOT block_reason_filled)`.
- Завершение: заполнены order_number, product_name, quantity, total_amount,
  supplier_name, delivery_days, prepayment_percent.

office_location и storage_conditions — дополнительные, не общеобязательные поля.
block_reason необязателен; любое заданное значение, включая пустую строку,
делает filled истинным и блокирует оплату. Для снятия блокировки нужно установить
null, а не пустую строку.

## Отложенные маршруты и возвраты

- composition_approval, price_approval, delivery_approval, payment_approval:
  postpone → postponed_review; resume_review → composition_approval.
- price_approval, delivery_approval, payment_approval: correction → composition_approval.
- execution_decision: return_to_approval → composition_approval;
  postpone_review → postponed_execution; execute → execution.
- execution: postpone_execution → postponed_execution.
- postponed_execution: reconsider → execution_decision.

Вместе с основным маршрутом это ровно 19 переходов. Дополнительных переходов,
самопереходов или Java-методов вида approvePrice() нет.

## Копирование для расходных материалов

consumables_order изначально наследует standard_order_supply. Собственный шаблон
создаётся существующим API и сохраняется репозиторием в рабочем JSON:

```java
templates.copyTemplate("standard_order_supply", "consumables_order", "consumables_supply");
```

Оригинал не меняется, office_order продолжает наследовать его. Вторая копия
для consumables_order запрещена.

## Загрузка демонстрации

Seed применяется только при отсутствии рабочего файла. Существующий
data/store.json не перезаписывается и автоматически не дополняется новыми данными.
Для отдельной демонстрации создайте FileRepository с новым путём, например
`data/b3-demo/store.json`, и передайте его движку. Тесты используют только @TempDir,
а не рабочее хранилище приложения.
