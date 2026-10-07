# Review of batch A (runtime fixes), reviewer opus, 2026-10-07

Commits: c08e2c766 (value never stored), 649829332 (commit-validation; wrong message, intended text in 433214bc5 and
`waste/commit-validation-notes.md`), 69e7b56fe (reader recovery). Default runs a8e3bde5b: none of the three is
installed (`git merge-base --is-ancestor` false for each), so the changed code was reasoned from source; probes ran
on default (jvm mode, read-only) against the installed dependencies and projection.

## Findings, ranked

### 1. MEDIUM (fixed) — 69e7b56fe drops valid forms after a malformed collection

`seon.sci.reader/recovery-point` (reader.cljc, the `:else` branch reached by every nested failure) resumes only at a
column-zero `(` or `;`. Before 69e7b56fe a nested failure leaked phantom forms; after it, every valid form between
the malformed collection and the next column-zero `(` is swallowed into the error event's source and never
evaluated. Failing inputs (reasoned from source; the reader is not installed on default):

- `"[1 2 3x 4]\nmy-var"`: `my-var` is not read (anchor set excludes symbols).
- `"[1 2 3x 4] (+ 1 2)"`: `(+ 1 2)` is not read (not at column zero).
- `"{:a {:a 1 :b}}\n[5 6]"`: `[5 6]` is not read.

Fix: a failure inside a closed collection (`:localized`, not `:stray-closer`/`:unclosed`) ends where the outermost
container's delimiters balance (`balanced-end`: strings, character literals and comments skipped), falling back to
the anchor when they never balance (an unclosed outer form, the 200-deep odd-map case). The outermost container is
the LAST of `:edamame/containers` (probe on default: `"[[1 3x]]"` gives `[["[" 1 2] ["[" 1 1]]`, inner first).
Delimiter errors keep the anchor rule, so `recovery-never-anchors-on-inner-maps-or-vectors` and
`an-unmatched-closer-is-a-reader-error-after-the-forms-before-it` (`"(let [x 1]\n(f x)\n}"` -> error, form, error)
are unchanged. Regression: `seon.sci.reader-test/a-failed-collection-ends-where-its-delimiters-balance` (the three
inputs above plus a string `"]"`, a `\)` literal and a `; ]` comment inside the failed form; the error source is
the malformed form alone).

### 2. LOW (fixed) — c08e2c766's regression never proves its task

`seon.sci.eval-test/showing-a-value-realizes-and-stores-only-what-is-shown` carried no `:seon/tasks`, so task
`seon.sci.eval/value-storage-walks-serializes-and-stores-every-value-up-to-eight-megabytes` (handle `8874c8ec8093`,
`seon.task/task-handle` on default) could never close; its `:must-read` named the deleted
`seon.sci.eval/value-storage` and its evidence pointed into gitignored `tmp/` (`.gitignore:28`). Fixed: the test
names the handle; must-read drops the deleted var; evidence points to the committed copies under
`docs/research/agent-platform/2026-10-07/waste/`. Same evidence fix for the reader task added by 69e7b56fe
(`tmp/model-trial/review-deep-opus.md` does not exist; committed copy is `spec-reviews/review-deep-opus.md`).

### 3. LOW (handed to root) — stale skill example

`.agents/skills/data-oriented-clojure/SKILL.md` §6.3 rewrites `seon.turn/settlement-projection` around
`:seon.eval/value-edn` and `blob/stage!`, both deleted by c08e2c766 (`settlement-projection` is now a three-element
split). The file carries uncommitted, unclaimed edits, so this lane cannot claim it. Fix: replace the example with
another rebinding case or delete it.

### 4. LOW (handed to root, file held by printers-rest) — `vector-attributes-in` docstring

`seon.schema.datahike/vector-attributes-in` (649829332) carries dates, measurements and a `# Costs` heading in a
non-`my.*` docstring (skill §9.1/§9.2). Proposed: "The members of `attributes` that `projection` stores in bridge
cells." The measurement belongs in `waste/commit-validation-notes.md`, where it already is.

### 5. LOW (not fixed, noted) — nested duplicate-key maps classified `:odd-map`

69e7b56fe widened `:odd-map` to `(and (map? data) (= \{ at-offset))`. Edamame locates a duplicate-key error at the
map's `{` too (probe: `"[1 {:a 1 :a 2}]"` -> row 1 col 4, same as `"[1 {:a 1 :b}]"`), so a nested duplicate key now
reports `::error-kind :odd-map` (top-level ones already did). Only the kind is wrong; the message is Edamame's and
recovery is identical. Edamame gives no structured fact separating the two and recovery never reads prose; left.

### 6. LOW (noted) — no regression for the restart marker

`seon.sci.eval/bind-settled-results!` now binds every settled handle to `{:seon.eval/unstored :live-only ...}`; no
test exercises it (none did before for the stored path either). A fork from a database holding an evaluation with
`:seon.eval/value-class` should bind `result/eN` to the marker naming that class.

## Verified, no finding

- c08e2c766 consumers: no reader of `:seon.eval/value-edn`, `value-storage`, `bind-handles!` or `unstored-notes`
  remains in src, test, resources or docs/seon. `:seon.eval/value-digest`/`:unstored` stay optional on the receipt
  schema (`seon.cluster.eval.edn:78-79`) for evaluations settled before; correct, nothing writes them. The
  architecture docs already say handles are unavailable after restart (`agent-runtime.md:167`). The allocation
  regression evaluates its argument before reading the counter, so the 1e6 vector's construction is not counted.
- 649829332 equivalence: the old set was `forms ∩ vector-shaped`; the new one is `held attributes ∩ vector-shaped`
  over the registry. On default the registry's qualified keys are exactly the 3,389 forms plus `:malli.core/schema`
  (never a datom attribute), and every installed `:db/ident` is a form, so every caller (`write-entity-value`,
  `write-owned-values-error`'s `swept`, `logical-datoms`) sees the same membership; no validation rule dropped.
  Cost probed on default (armed, fresh compiled-cache projection): all forms 223 ms, seven held attributes 0.14 ms.
  Minor: `logical-datoms`' `element` builds a set per moved cell where `some` over `vector-attribute-in?` would do;
  a few cache lookups per cell, not worth a change.
- 69e7b56fe `read-artifact`: any error event now reports the reader's message; contract of `parse-classification`
  uses the registered `:seon.error/throwable`.
