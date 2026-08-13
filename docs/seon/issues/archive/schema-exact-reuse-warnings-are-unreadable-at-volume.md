---
type: issue
status: resolved
severity: friction
tags: [issue, schema, render, class/n1, wave/dev-tooling-face-hygiene]
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

## N1 disposition — 2026-08-12

Still open outside this lane. In `src/seon/schema/admission.clj`, derive
whether a declaration carries reusable composite structure from its parsed
form, suppress scalar/reference markers, and return one aggregate finding with
the candidate plus all matching existing keys.

## Resolution — 2026-08-13

`exact-reuse-findings` now derives reusable structure from the parsed form.
Bare keyword references and `[:= value]` markers produce zero exact-reuse
findings. A duplicated composite form produces one finding whose
`:seon.schema.admission/similar-keys` vector names every matching registry
key.

Before, one marker declaration emitted one warning per existing marker:

```text
[warning/schema-exact-reuse] Schema :seon.sci.kernel/already-armed has the same shape as existing :my.background/invalid-call.
[warning/schema-exact-reuse] Schema :seon.sci.kernel/already-armed has the same shape as existing :seon.cluster.loop/another-marker.
… hundreds more …
```

After, the focused regression observes this shape:

```clojure
{:marker-findings []
 :reference-findings []
 :composite-findings
 [{:seon.schema.admission/kind :warning/schema-exact-reuse
   :seon.schema.admission/key :candidate/composite
   :seon.schema.admission/similar-keys [:existing/a :existing/b]}]}
```

The focused gate executed both marker suppression and aggregate composite
matching.
