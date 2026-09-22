---
type: issue
status: open
created: 2026-09-22
tags: [boot, configuration, retirement]
---

# Boot initialization still names the deleted search supplier

`config/default.edn:540–544` retains the `:seon.search/handle` supplier row
whose function `seon.search/supplied-handle` was deleted by `434c01f4c`.
After repairing standalone storage-attribute selection, an empty-root reset
passed schema and program population, then refused initialization because this
function has no program row. Exact command:
`bin/seon --root tmp/boot-process-root reset --force`.

Evidence: `tmp/boot-process-reset.log`, PID 30076, failure at
2026-09-22T17:10:18.344Z. [Landing evidence](../../prds/agent-platform/landing/lane-boot-process-attribute-2026-09-22.md)
distinguishes the successful provenance boundary from unavailable healthy boot.

Owner: shipped initialization configuration and search retirement. Remove the
obsolete supplier row and prove an unmodified shipped-config from-zero scratch
reset reaches healthy readiness. The schema-bridge repair does not restore search.
