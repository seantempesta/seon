---
type: issue
status: open
severity: cleanup
tags: [issue, reference]
---
# Partially-Stale Reference Docs Need Updates

## Problem

A few `docs/seon/reference/` files still mix stale references (XTDB, ml-options namespaces, dead file paths, Datalevin-era assumptions) with still-valid content. The bulk was cleaned in the doc-scrub waves; what's left is below.

## Remaining Work

| File | Issue |
|------|-------|
| `separate-jvm-exploration.md` | `src/seon/experimental/ns_instance.clj` dead; PRD path needs archive prefix; surviving Datalevin/XTDB mentions need rewording for the embedded Datahike of the JVM track `[JVM track — paused]` |
| `durable-ctx-design.md` | Broken internal link `docs/reference/` → `docs/seon/reference/`; some Datalevin assumptions linger in the design narrative |

## Already Fixed (prior waves)

- `async-ui-patterns.md` → status: abandoned (entirely ml-options)
- `hyperlith-patterns.md` → status: abandoned (entirely ml-options)
- `datastar-quick-reference.md` → fixed CONVENTIONS.md refs
- `gemini-native-integration.md` → status: completed, added staleness preamble
- `linting-setup.md` → fixed PRD path to archive
- `flow-foundation.md` → no datalevin/xtdb/datahike refs remain
- `datastar-extended-patterns.md` → no db-engine refs remain

## Severity

cleanup
