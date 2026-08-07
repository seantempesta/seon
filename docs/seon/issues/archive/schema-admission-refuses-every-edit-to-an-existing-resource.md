---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, tooling]
---

# Schema admission refused every edit to an existing schema resource

## Resolution (2026-08-07, `de31c5316`)

`default-registry-excluding` (`src/seon/schema/admission.clj`) excluded the
candidate file from its disk walk but seeded the registry from the LIVE
`(schema/registered-schemas)`, which already holds that same file's published
declarations. The exclusion therefore removed nothing, and `collision-findings`
reported every key in the file as "already registered; exact-key redefinition
is refused" — an ERROR finding, so the edit hook blocked the edit.

This refused pure accretion: adding ONE new key to an existing schema
resource was impossible, and so was any correction. It blocked all four
seon.env Phase 1 lanes.

Falsified before, from a fresh `clojure -M:dev` JVM, admitting each file's own
UNCHANGED on-disk content:

```clojure
(adm/admit {::adm/path p ::adm/sources {p (slurp p)}})
;; seon.sci.kernel.edn -> 15 collision errors (every key in the file)
;; seon.flow.edn       -> collision errors from :seon.flow/active-work on
;; seon.env.edn        -> collision errors from :seon.env/environment on
```

Verified after: the same three admissions report zero collision errors, and
adding `:seon.sci.kernel/arm` to `seon.sci.kernel.edn` was admitted (advisory
`schema-exact-reuse` warnings only).

The fix subtracts the candidate file's own on-disk keys from the
live-registry seed, so a collision now means what it says: a DIFFERENT file
already owns that key.

## Follow-on, not fixed here

The same admission run emits hundreds of `schema-exact-reuse` warnings
because every `[:= true]` marker schema matches every other one. Filed
separately as
[schema-exact-reuse-warnings-are-unreadable-at-volume](../schema-exact-reuse-warnings-are-unreadable-at-volume.md).
