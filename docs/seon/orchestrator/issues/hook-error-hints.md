---
type: issue
status: open
tags: [issue, agent]
---
# Improve 6 hook feedback messages

## Problem

The dev hook's error/feedback messages for 6 specific scenarios are generic. A complete spec with copy-pasteable improvements exists in the archive.

## Design (from archive)

`docs/archive/unified-dev-hook/research/error-hints-spec.md` specifies improvements for:

1. nREPL unavailable
2. Test error
3. Generative test error
4. Reload timeout
5. Compliance violation
6. Review timeout

Each has a current message, proposed message, and rationale.

## File Refs

- `src/seon/dev/hook.clj` — hook feedback messages
- `docs/archive/unified-dev-hook/research/error-hints-spec.md` — complete spec

## Severity

cleanup
