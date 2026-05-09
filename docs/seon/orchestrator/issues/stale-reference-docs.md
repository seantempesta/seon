---
type: issue
status: open
tags: [issue, reference]
---
# Partially-Stale Reference Docs Need Updates

## Problem

Several `docs/seon/reference/` files contain stale references (XTDB, ml-options namespaces, dead file paths) mixed with still-valid content. Quick fixes applied for high-priority items; these need deeper cleanup.

## Remaining Work

| File | Issue |
|------|-------|
| `flow-foundation.md` | 17 XTDB mentions should be Datalevin; `src/seon/web/sse/flow.clj` path wrong |
| `separate-jvm-exploration.md` | `src/seon/experimental/ns_instance.clj` dead; PRD path needs archive prefix |
| `durable-ctx-design.md` | Broken internal link `docs/reference/` → `docs/seon/reference/`; vision doc could mislead agents |
| `datastar-extended-patterns.md` | Test examples use stale namespaces |

## Already Fixed (this session)

- `async-ui-patterns.md` → status: abandoned (entirely ml-options)
- `hyperlith-patterns.md` → status: abandoned (entirely ml-options)
- `datastar-quick-reference.md` → fixed 3x CONVENTIONS.md refs
- `gemini-native-integration.md` → status: completed, added staleness preamble
- `linting-setup.md` → fixed PRD path to archive

## Severity

cleanup
