# Dashboard and order creation implementation plan

**Goal:** первая часть UI поверх существующего HTTP backend, без frontend-сборки.
**Spec:** раздел 4 `docs/CODEX_UI_WORKFLOW_B3.md` и текущий запрос пользователя.
**Architecture:** index.html + CSS; api.js содержит fetch и JSON transport;
app.js строит DOM через textContent, держит только отображение и черновики формы.
**Stack:** HTML5, CSS3, browser ES modules; существующий Javalin/Java 21/Maven.

## Scope and design

Светлая тема с фиолетовым акцентом, верхняя панель с пользователем/ролями и
созданием, три информационных счётчика, поиск/фильтр, адаптивная сетка карточек.
Создание в dialog, параметры/типы/диапазоны из API. Для перехода после создания
добавляется read-only карточка по hash URL с параметрами и возвратом к списку.
Граф, действия, изменение параметров и история относятся к следующей части UI.
Это продолжение уже спроектированной интеграции; новые согласования не требуются.

## Tasks

1. [x] RED: GET /, CSS/JS должны возвращать HTML/CSS/JS, а не 404.
   Добавить тест WebResourceTest; сохранить предыдущие проверки backend.
2. [x] Создать четыре запрошенных файла. Загрузка пользователей/заказов через
   API; классы и параметры загружаются при создании. Черновик сохраняется после
   backend-отказа и при переключении класса; устаревшие fetch игнорируются.
3. [x] Сохранить точные числовые JSON tokens без JS Number-rounding;
   string/integer/decimal, unset/null/пустая строка. Диапазоны только подсказки.
   Для русских названий ролей добавить необязательное поле roleTitles в /users.
4. [x] Проверить настоящий браузер без Node/npm: API-трафик, поиск, фильтр,
   создание quantity=700, отказ прав без потери ввода, reload и сохранность,
   stale responses, точные числа, адаптивность и безопасный вывод текста.
5. [x] Выполнить Maven clean test/package, обновить README и результаты.
   Выполнить review; не изменять WorkflowEngine.

## Review focus

Параллельная смена класса; пропавший backend; выбор другого пользователя;
пустые поля против null; особые символы в параметрах; изменение данных вне
браузера; двойная отправка; точные decimal и большие integer.

Работа ведётся в текущем workspace с сохранением предыдущих изменений.

## Execution ledger

- RED: `.tools/dashboard-red.log`: главная страница возвращает 404,
  названия роли ещё нет в ответе API.
- GREEN: `.tools/dashboard-http-green.log`: семь целевых случаев прошли.
- Chrome smoke: `.tools/dashboard-browser/result.json` — PASS, 34 запроса;
  browser screenshot-файлы в том же игнорируемом каталоге. Последний прогон
  проверяет также unset/null/пустую строку и точное преобразование `.5` в JSON.
- Независимый reviewer не запустился из-за недоступной модели аккаунта.
  Выполнена самостоятельная проверка DOM, async-версий, draft lifecycle,
  JSON transport и фактических desktop/mobile снимков. Пользователь уведомлён.
- Итог: `.tools/dashboard-final-build.log`, Maven clean test package —
  BUILD SUCCESS; 374 случая, failures/errors/skipped=0. Четыре UI-ресурса
  включены в JAR. Тестовый сервер остановлен; пользовательский data/store не тронут.
