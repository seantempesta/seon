# resources/

Declarative platform data synced between Datahike `:main` and Git (`seon.code.files`), plus static web assets:

- `resources/seon/schemas.edn` — All co-located multi-segment Malli schemas (248 schemas) from which Datahike attributes are derived.
- `resources/seon/tasks.edn` — Declarative `:seon.task/*` backlog (imported on fresh install, written back on green `seon_merge`).
- `resources/seon/agents/{root,examples}.edn` — Seed `:agent/root` definition and few-shot example sessions.
- `resources/seon/config/default.edn` — System default configuration (Aero EDN).
- `resources/public/` — Vendored frontend assets (`datastar.js`, `highlight.js`).
