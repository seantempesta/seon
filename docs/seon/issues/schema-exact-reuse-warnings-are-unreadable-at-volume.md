---
type: issue
status: open
severity: friction
tags: [issue, schema, tooling, render]
---

# Exact-reuse warnings bury their own signal

## Problem

Adding ONE key to `resources/seon/schemas/seon.sci.kernel.edn` produced
hundreds of `schema-exact-reuse` warnings, all of this shape:

```text
[warning/schema-exact-reuse] Schema :seon.sci.kernel/already-armed has the
same shape as existing :my.background/invalid-call. If this duplicates
:my.background/invalid-call, delete :seon.sci.kernel/already-armed and reuse
:my.background/invalid-call. Create a parallel schema only when the user
explicitly chooses a separate system.
```

The check (`exact-reuse-findings`, `src/seon/schema/admission.clj`) compares
each candidate declaration against every registry entry by structural
equality. Marker schemas — `[:= true]`, and a bare keyword reference — are
structurally identical to every other marker in the system by design, so one
new marker key emits a finding for EVERY existing marker: N warnings for one
edit, each one advising a merge that would be wrong (`:seon.sci.kernel/
already-armed` and `:my.background/invalid-call` mean different things and
must stay separate keys).

The cost is not the noise itself, it is that a genuine finding — a real
composite shape declared twice — is now indistinguishable from the flood and
will be skipped along with it. The reader's only rational response to a
several-hundred-line advisory block is to stop reading it.

## Evidence

2026-08-07, adding `:seon.sci.kernel/arm` to
`resources/seon/schemas/seon.sci.kernel.edn`. The edit-hook findings block
and a direct `seon.schema.admission/admit` call both reproduce it; the
findings are all `:warning`, so nothing is blocked — only unreadable.

## Owner

`exact-reuse-findings` in `src/seon/schema/admission.clj`.

## Acceptance criteria

- A declaration whose shape carries no structure — a `[:= x]` marker, a bare
  keyword reference — does not produce exact-reuse findings at all, because
  sharing such a declaration is never the right advice.
- A genuinely duplicated composite shape produces ONE finding naming the
  candidate and the existing keys it matches, not one finding per existing
  key.
- The rule that decides "carries no structure" is computed from the form, not
  a maintained list of exempt keys.
