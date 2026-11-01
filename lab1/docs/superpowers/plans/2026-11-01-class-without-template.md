# Fix creation of a class without a process template

- Root cause: `/api/classes` includes the base `order` class, and the form
  treats every class as selectable. `order` has no template; only its child
  `standard_order` owns B.3. The engine correctly rejects creation, but web
  maps the undifferentiated TEMPLATE_ERROR to HTTP 500.
- Preserve the full class catalog and add nullable effective `templateId`.
  Resolve it with TemplateCatalogService, including closest inheritance.
- The creation form offers classes with a template. No fixed class ids or
  roles/conditions in JavaScript. Empty results show an explanatory message.
- Distinguish absent template using an IllegalArgumentException subtype in
  the template service. The engine keeps its existing behavior and cause;
  web maps that cause to 409 / NO_PROCESS_TEMPLATE. Other template failures
  remain 500, ambiguity remains 409. No controller business rules.
- RED/GREEN: actual class metadata and inherited overrides; configured root
  class can still create; absent template rejects without JSON changes;
  malformed configured template stays a server error; Chrome selection.
- Run full Maven tests and dashboard browser regression on a separate fixture
  and ephemeral loopback port. Never mutate demo/web/store.json.

## Verification

- RED: 17 HTTP cases, 3 expected failures: missing metadata and root creation
  returning 500 instead of 409 (`.tools/no-template-red.log`).
- GREEN: 46 template/HTTP cases pass (`.tools/no-template-green.log`).
- Full Java 21 Maven clean test package: 379 cases, no failures/errors/skips,
  BUILD SUCCESS (`.tools/no-template-final-build.log`).
- Real isolated HTTP POST: 409 / NO_PROCESS_TEMPLATE with a Russian message;
  bytes of the fixture store are unchanged. Error body saved in
  `.tools/no-template-browser/error.json`.
- Dashboard Chrome regression PASS, including the exact selectable class ids
  from real /api/classes metadata and inherited/independent template ids.
  Fixed an existing test wait: after offline recovery it previously accepted
  old DOM rows before loading completed, then clicked a disabled refresh button.
- SHA-256 of demo/web/store.json before/after checks matched; no tests used
  the user server on 7070. WorkflowEngine and controller are unchanged.

## API contract review

- Local internal API; no OpenAPI document. Reviewed controller, DTO, adapter,
  mapper and HTTP tests; collected context in .tools/no-template-api-context.md.
- Additive nullable templateId field; all previous class ids and fields remain.
  No new required request field, path or method change. Permission checks and
  commands continue through the existing engine.
- Known class with no process: corrected 500 to 409 / NO_PROCESS_TEMPLATE;
  other invalid template configuration remains 500 / TEMPLATE_ERROR.
  Ambiguous transitions still return 409. Error JSON shape is unchanged.
- README, UI workflow contract and report updated for the behavior.
