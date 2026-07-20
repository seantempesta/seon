---
type: issue
status: resolved
tags:
  - database
  - api
  - runtime
severity: friction
tags: [issue]
---

# Public pull map used transport field names

## Failure

The remote database refactor exposed `:seon.db/selector`, `:seon.db/eid`, and
`:seon.db/eids` in the pod API. Maintained callers and the established
Datomic-style Seon interface use `:seon.db/pull-pattern`, `:seon.db/ref`, and
`:seon.db/refs`. Runtime initialization failed when an instrumented `pull`
received the established map form.

## Resolution

`seon.db/pull`, `pull-many`, and `entity` again expose only the established
public field names. The implementation translates them directly to
`seon.db.protocol/selector` and protocol entity ids when it constructs the JVM
request. Current callers and focused contract tests use the one public form;
the transport names no longer leak through `seon.db`.

## Acceptance

- Map and positional pull forms return ordinary data from the JVM writer.
- The canonical Bun pod completes committed-program admission.
- No `:seon.db/selector`, `:seon.db/eid`, or `:seon.db/eids` public call site
  remains under `src/` or `test/`.
