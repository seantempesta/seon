---
date: 2026-09-16
lane: dir-elision-floor
surface: render-value / print
---

# A cut that showed nothing: the AI projection's missing floor

Issues:
[dir-of-a-namespace-returns-an-elision-with-nothing-shown](../../../seon/issues/dir-of-a-namespace-returns-an-elision-with-nothing-shown.md)
and
[a-value-larger-than-the-budget-is-elided-to-nothing](../../../seon/issues/a-value-larger-than-the-budget-is-elided-to-nothing.md)
— one class, two sightings.

## Reproduced first — `default` pid 45917, SCI evaluation mode, read-only

```clojure
(dir seon.turn)
;; shown text, verbatim, 718 ms, 208 bytes:
;; {:seon.print/bound-by :seon.render.profile/token-budget,
;;  :seon.print/elision-unit :characters, :seon.print/omitted 41040,
;;  :seon.render.data/next-offset 0, :seon.render.data/path [],
;;  :seon.render.data/total 41040}
```

The second sighting reproduced identically:

```clojure
[(apply str (repeat 3000 "ab"))]
;; [{:seon.print/bound-by :seon.render.profile/token-budget,
;;   :seon.print/elision-unit :characters, :seon.print/omitted 6000,
;;   :seon.render.data/next-offset 0, :seon.render.data/path [0],
;;   :seon.render.data/total 6000}]
```

A plain 300-member vector was NOT affected (32 members shown, elision with
`next-offset 32`), so the defect is not "collections are cut badly" — it is
every cut that lands on a STRING or a projected producer's text.

## Cause — three seams, one disease

1. **`seon.print/fit-text` omitted its whole subject.** It minted
   `(elision-node … path 0 original original :characters nil)`: offset zero,
   omitted equal to total, no prefix. The characters that fit were computed
   and thrown away.
2. **`seon.print/fit`'s search had no floor.** The loop drove `child-limit`,
   then `depth-limit`, then `string-limit` to ZERO — a zero string limit is
   "show no characters", a zero child limit "show no members". Absence read
   as a bound.
3. **`render-elision-ai` dropped `::prefix` and `::requery-refusal`** from
   the AI text and reported CHARACTER counts, which AGENTS.md §2.4 rules are
   storage projections, never the agent-facing size.

Why `dir` in particular: `:seon.repl/directory` declares the AI pair
`seon.repl/render-directory-ai` (`resources/seon/schemas/seon.repl.edn:2`),
which `pr-str`s the whole directory with `*print-length* nil`. The floor
therefore meets ONE 41,040-character projected node, not 150 members. The
selection is correct — measured live, `matching-shapes-in` answers `[]` for a
fabricated `{:schemas … :functions …}`, so the pair is chosen on the real
directory's shape and nothing else hijacks it.

## Fix — `src/seon/print.cljc` only, the one clipping spot

- `fit-text` keeps `string-limit` characters as the cut's declared
  `::prefix`, sets `next-offset` to how many were shown, and counts only the
  remainder.
- `enrich-node`'s `::truncated-string` branch does the same with the text
  admission already kept.
- `fit`'s search floors STRUCTURALLY at one child, one level, one character.
  Below the floor the candidate may exceed the budget; that is the honest
  answer and its cut names the bound. The string step now HALVES rather than
  jumping to zero, so the retained prefix is as large as the budget allows.
- `render-elision-ai` surfaces `::prefix` and `::requery-refusal`, and
  reports a character cut's sizes through
  `seon.ai.tokens/estimate-of-characters`, saying `:tokens`. The stored node
  keeps characters.

## Live proof — `default` pid 95853 (post-refork), SCI evaluation mode

```clojure
(dir seon.turn)
;; shown text, 494 ms, 1,893 bytes (was 208):
;; {:seon.print/bound-by :seon.render.profile/token-budget,
;;  :seon.print/elision-unit :tokens,
;;  :seon.print/omitted 12386,
;;  :seon.print/prefix "#:seon.repl{:columns [:sym :arglists :doc :in :out :supplied], :rows [[seon.turn/append-generated-call ([db request]) \"Append exactly one system-authored form to an open generated turn.\" [:cat :seon.db/database-value :seon.turn/generated-form-request] :seon.store/transaction-data nil] … [seon.turn/disp",
;;  :seon.print/requery-refusal "the value has no result handle",
;;  :seon.render.data/next-offset 511,
;;  :seon.render.data/path [],
;;  :seon.render.data/total 12898}
```

Eight complete function rows — symbol, arglists, docstring, `:in`, `:out`,
`:supplied` — where the agent previously received a count and nothing else.
The string sighting shows the same shape: a 1,638-character prefix,
`:elision-unit :tokens`, `omitted 1363` of `total 1875`, `next-offset 511`.

The `requery-refusal` is honest, not a residual: the MCP SCI path creates no
evaluation, so the value has no `result/e…` handle. An agent's own evaluation
carries one, and the regressions assert the `seon.print/value-at` form
appears when a handle is supplied.

## Verification boundary

- Reproduction, cause and the live after-bytes are on `default`, read-only
  SCI evaluations.
- The first probe round redefined `seon.print` Vars through the MCP with
  `::`-aliased keywords. **The MCP `jvm` reader resolves `::` in `user`, not
  in the `namespace` argument**, so those definitions installed
  `:user/…` keys and briefly broke rendering in the shared JVM. Restored with
  `(require 'seon.print :reload)` within the same minute, and the orchestrator
  reforked `default` afterwards. Do not send `::` in an MCP form.
- Adoption of this edit was REFUSED on the pre-refork `default`
  (`:seon.issue/agent` incompatible schema change, a foreign lane's). The
  post-refork JVM carries the edit; the live bytes above are from it.
- **No in-process regression run: the shared fixture base REFUSES to
  construct in this JVM.** Followed the base construction rule exactly — a
  daemon-thread `future`, no bound, reached through `seon.test`'s own loader
  because `test/` is not on `default`'s classpath — and it answered
  `#:seon.error{:kind :seon.test-support/database-base-unavailable, :message
  "Canonical fixture base construction failed: Schema declaration resolution
  requires the projection handed to the operation."}`. Nothing was rebuilt
  (fixture-base poison rule); recorded in
  [in-process-test-runs-poison-the-shared-fixture-base](../../../seon/issues/in-process-test-runs-poison-the-shared-fixture-base.md).
  The five test files need the cold gate; requested in
  `tmp/orchestrator/gate-requests/dir-elision.txt`.
- clj-kondo: 0 errors on all changed files; warnings are pre-existing.

## Residual, filed not fixed

`seon.repl/render-directory-ai` is an AI render function that applies NO
profile — it is `pr-str` of the whole directory. AGENTS.md §2.4 says the AI
render functions apply the profile's limits; this one cannot, because its
contract returns a bare string. The floor can only cut its output as TEXT, so
`dir` pages by character offset rather than by member. Removing or bounding
that pair is a separate slice: `:seon.repl/directory`'s pair is asserted by
`seon.data-shapes-test`, `seon.render.value-test` and
`seon.render.web-debug-test`.
