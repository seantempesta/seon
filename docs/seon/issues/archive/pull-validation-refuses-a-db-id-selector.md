---
type: issue
status: resolved
severity: blocker
created: 2026-09-18
tags: [issue, database-read, pull, schema, boot, wave/core]
---

# Pulled-form validation refuses a `[:db/id]` pull on an entity no row schema claims

The in-flight `src/seon/db.clj` pulled-form validation (uncommitted at
`859c557b3`; `validate-pulled-result`, `pulled-entity-schema-key`,
`unknown-pull-schema-error`) validates EVERY pulled map. When the caller
supplies no `:schema-key`, the entity schema is derived from the
`:seon.program/row-schema` property of the attributes present on the entity;
a set of size other than one refuses the read.

The config provider descriptor claims no row schema at all. Measured on the
canonical fixture base (an instrumented readiness loop printing each attribute's declared row schema, 2026-09-18):

    ENTITY 35917 ATTRS #{:seon.config.ai/api-key-variable
                         :seon.ai.model/provider-id
                         :seon.config.ai/endpoint
                         :seon.ai.model/openai-chat-completions
                         :seon.ai.model/output-token-wire-key}
       :seon.config.ai/api-key-variable      row-schema= nil
       :seon.ai.model/provider-id            row-schema= nil
       :seon.config.ai/endpoint              row-schema= nil
       :seon.ai.model/openai-chat-completions row-schema= nil
       :seon.ai.model/output-token-wire-key  row-schema= nil

`keep` drops every nil, the derived set is empty, and
`(seon.db/pull database [:db/id] [:seon.ai.model/provider-id "openrouter"])`
returns `:seon.db/unknown-pull-schema` instead of `{:db/id 35917}`.

Blast radius measured 2026-09-18: the CANONICAL FIXTURE BASE cannot be built
(`:seon.test-support/database-base-unavailable`), so every test JVM in the
tree is red, and `bin/seon init` / `bin/seon reset --force` refuse in
republish — the default cluster is down.

Two observations for the owning change:

1. A `[:db/id]`-only selector declares its own result. `{:db/id N}` needs no
   entity schema, and refusing it asks the caller to name a schema the read
   does not consult.
2. "No attribute on this entity carries a row schema" is not the same
   question as "the attributes disagree". The first is an UNDECLARED entity,
   which the codebase has many of; the second is a genuine ambiguity. Today
   both take the refusal branch.

Until this is decided, boot is blocked. `seon.cluster` no longer hides it:
the readiness probe surfaces this refusal verbatim
([the absence-as-health defect it used to hide](initialization-readiness-read-absence-as-health.md)).

## Resolution — 2026-09-19

`seon.db/validate-pulled-result` returns `[:db/id]`-only results unchanged,
preserves absent entities as nil, and leaves an undecided row schema readable.
Evidence: `pull-validates-its-result-against-the-derived-pulled-form` in
`test/seon/db_test.clj`; a read-only JVM probe on default with an explicitly
supplied projection returned `{:db/id 35835}` for the provider selector.
The finishing fast tally and cold verification boundary are recorded in
[the landing note](../../prds/steward-platform/research/db-contracts-finish-2026-09-19.md).
