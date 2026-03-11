---
type: prd
status: completed
tags: [prd, archive]
---

# Seon Transform - Notes

Capture gotchas, learnings, and things that surprised you here.

---

## Gotchas

### Clojure Namespace → Directory Mapping

- `seon.trading.signals` → `src/seon/trading/signals.clj`
- Hyphens in namespace → underscores in filesystem
- `ml-options` → `ml_options/` (directory)
- `seon` → `seon/` (directory)

### Files That Reference Namespaces

Don't forget to update:
- `deps.edn` - `:main-opts`, paths
- `resources/system.edn` - Integrant component keys
- `tests.edn` - Test source paths
- `dev/user.clj` - Requires
- `env/*/clj/user.clj` - Profile-specific user namespaces
- `.clj-kondo/config.edn` - Linter config (if any namespace references)

### XTDB Data Directory

The `data/xtdb/` directory (73GB in original) was NOT copied. New Seon instance starts with empty database. This is intentional - trading data stays in original project.

---

## Learnings

(Add learnings as work progresses)

---

## Surprises

(Add surprises as work progresses)
