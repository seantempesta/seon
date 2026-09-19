---
type: prd
status: binding for the kind-retirement sweep lanes (owner rulings D3, D12, D13 of 2026-09-19; PRD 1o/1q/1r)
created: 2026-09-20
tags: [prd, error-model, contracts, instrumentation, sweep]
---

# Error conversion PRD: precise errors, exact contracts, one rule per site

Owner (2026-09-19): "the point of this is to have more precise errors and
schemas that name the specific errors or union of possible errors, so it's
explicit that this function may return these errors. We don't want a general
predicate. The goal is to improve the schemas and to know what we are calling
and what can happen, and we are guaranteed those values will be present and
valid with the instrumentation validating it." And: "I want proper error
messages done correctly."

This document is written so that a low-effort lane can convert any file
without judgment calls. Every rule below is literal, has a before/after
taken from landed commits, and has an acceptance check a script can run.
Where a site does not fit a rule, the lane STOPS and lists it; it never
improvises.

## 0. What is already landed (read these, do not re-derive them)

| Thing | Where | Landed |
|---|---|---|
| The base: an error IS a map with `:seon.error/at` (inst), `:seon.error/layer` (qualified keyword naming the boundary, e.g. `:seon.db/read`), `:seon.error/operation` (qualified symbol of the function that observed it, e.g. `seon.db/pull`), optional `:seon.error/message` | `resources/seon/schemas/seon.error.edn` (`:seon.error/base`) | 1a, `431093b97`… |
| Facets: a domain error extends the base with `[:and :seon.error/base [:map …]]` declared in the OWNING resource (e.g. `:seon.db.read/error` in `seon.db.read.edn`, `:seon.instrument/contract-error` in `seon.instrument.edn`) | the owner's `resources/seon/schemas/<owner>.edn` | error manifest 2026-09-18 + 1a |
| Storage: an occurrence row owned by its signature root through `:seon.error/occurrences` (component); facets persist on the occurrence; no identity per facet | `src/seon/error.clj` `recording`, `seon.error.edn:61` | 1a step 1 (refuted audit B's C1) |
| Identity (D13): `seon.id/id` of [layer, operation, sorted satisfied facet keys, throwable class + top frame when present, violated expected key/shape when present, location path]; never at/basis/process/message/offending bytes | `src/seon/error.clj` `signature` | `b50bb67fc` |
| Constructor: `seon.error/diagnostic` takes at/layer/operation/message + the seven `diagnostic-*` evidence members and optional `:seon.error/data`; returns a base value | `src/seon/error.clj:315` | 1a |
| Facet recognition from a complete value: `seon.error/facets` (projection, value) → set of satisfied facet keys; `facet-keys` (projection) | `src/seon/error.clj:1794`, `:1813` | 1a |
| The wrapper guarantee: a contracted function whose declared output names facets refuses a returned error that satisfies none of them ("returned undeclared error facets #{…}") and admits a declared, complete one | `src/seon/instrument.clj` (`796a76314`), regressions in `test/seon/instrument_test.clj` | 1a step 6 |
| Sentence grammar for a contract miss: `<operation> refused <member> at [<path>]: expected <expected-description>, got <actual-description>. Fix: <fix>. Contract: <key>.` | `seon.error/problem-sentence` `src/seon/error.clj:998`, `explain-problem` `:946` | existing; D1/D2 fixes `a7f013251` |
| The general predicate `seon.error/error?` and every private copy: DELETED; `:seon.error/kind`, `{:seon.error/class true}` and the `[:= true]` class markers: deleted from the owners' schemas | `bc8152438`, `c0a99068f` | 1a, kind-schema lane |

## 1. The four shapes a correct error has

### 1.1 A producer builds the base plus its facet, and nothing else

```clojure
;; WRONG (retired): a kind stamp classifies the map
{:seon.error/kind :seon.db/invalid-read
 :seon.error/message "the read refused"}

;; RIGHT: the base says when/where/who; the facet's own members say what
(error/diagnostic
 {:seon.error/at (java.util.Date.)
  :seon.error/layer :seon.db/read
  :seon.error/operation `pull                     ; the OBSERVING function's qualified symbol
  :seon.error/message "pull refused: selector names an uninstalled attribute"
  :seon.error/diagnostic-layer :seon.db/read
  :seon.error/diagnostic-operation `pull
  :seon.error/diagnostic-member :seon.db/selector
  :seon.error/diagnostic-expected "an installed attribute in the projection"
  :seon.error/diagnostic-offending selector
  :seon.error/diagnostic-cause :seon.db/uninstalled-attribute
  :seon.error/diagnostic-evidence {:seon.db/attribute attribute}
  ;; facet members the owner's schema requires (here :seon.db.read/error):
  :seon.db/read-operation :pull
  :seon.db.read/target {…}})
```

Rules: `operation` is the function that OBSERVED the failure (the one whose
body constructs the value), not a guessed namespace and not a string.

`layer` is the boundary the owner's resource declares for that facet. The
facet's required members come from the owner's `.edn`; if the resource does
not yet declare a facet for this failure, the lane declares one there
(section 3) before using it. No `:seon.error/kind`, no `:type`, no
`:seon.error/class`, no boolean marker.

**Where the constructor lives (amended 2026-09-20 after the turn-cluster
lane found the cycle `seon.ai → seon.repl → seon.error → seon.cluster.wake →
seon.render.value → seon.ai`).** `seon.error` is the recorder and renderer
and requires the render and REPL stack; a producer that sits below those
(`seon.cluster.wake`, `seon.render.value`, `seon.ai`, `seon.repl`, anything
they require) cannot require it. The pure constructor is therefore owned by
the leaf namespace `seon.error.refusal` (no heavy requires; it already owns
the pure cause-chain reader): `seon.error.refusal/diagnostic` builds the base
value; `seon.error/diagnostic` delegates to it unchanged, so no caller
breaks. A producer requires the leaf when requiring `seon.error` would
create a cycle, and the leaf otherwise only when it needs nothing else from
`seon.error`. Never a second constructor, never `requiring-resolve` at call
time, never a private copy. (Landing: lane error-family-1a, next stop.)

### 1.2 A contract names the exact errors the function returns

```clojure
;; WRONG (retired): the generic union says nothing
{:malli/schema [:=> [:cat :seon.db/database-value :seon.db/pull-selector :seon.db/entity-ref]
                [:or :seon.db/pulled-entity :seon.error/value]]}

;; RIGHT: every facet this body can return, and only those
{:malli/schema [:=> [:cat :seon.db/database-value :seon.db/pull-selector :seon.db/entity-ref]
                [:or :seon.db/pulled-entity :seon.db.read/error :seon.db.availability/error]]}
```

The armed wrapper is the guarantee (landed): returning a value that
satisfies none of the declared facets is a contract violation at the
wrapper; a declared, complete facet passes. A function that merely passes a
callee's error through declares that callee's facets in its own output.
`:seon.error/value` (an alias of the base) is admissible ONLY as an input
type at a genuinely polymorphic inspection boundary (a renderer, a recorder),
never as an output of a domain function.

### 1.3 A consumer branches on the facet's distinguishing member

```clojure
;; WRONG (retired): ask "is it an error?"
(if (error/error? result) result (continue result))

;; TRANSITIONAL (the urgent sweep did this at 74 sites; it is the general
;; predicate copied by hand and is to be REPLACED as callees become exact):
(if (and (map? result) (contains? result :seon.error/at)
         (contains? result :seon.error/layer) (contains? result :seon.error/operation))
  result (continue result))

;; RIGHT: the callee's contract says it returns :seon.db.read/error or a row;
;; the read facet requires :seon.db/read-operation, so that member is the branch
(if (contains? result :seon.db/read-operation) result (continue result))

;; RIGHT when the caller handles a union of facets differently:
(cond (contains? result :seon.db/read-operation)      (retry-read result)
      (contains? result :seon.db.availability/connection) (refuse-boot result)
      :else (continue result))
```

The distinguishing member is a REQUIRED member of that facet's `[:map …]`
that no other facet in the callee's declared union shares. If two facets in
one union share every required member, the schemas are wrong: the lane
stops and lists it (section 6). The base-three check is admissible only
while the callee's contract still says `:seon.error/value`; every such site
is listed as a step-6 debt with the callee's name, and the lane converting
that callee removes the debt.

### 1.4 A message says what was expected, what was observed, where, and the fix

Stored `:seon.error/message` is one sentence of prose the producer wrote
or the dependency raised; it is optional and never used for recognition.
The RENDERED message is built from data by the error render pair
(`seon.error/render-ai`, `render-html`, `problem-sentence`,
`instrumentation-prose`) in this grammar, one line per fact:

```
<operation> refused <member> at [<path>]: expected <expected-description>, got <actual-description>.
Fix: <fix>.
Contract: <schema key>.        ; when a contract was violated
Facets: <sorted facet keys>.    ; always
Seen: <at> in <layer>; <n> occurrences since <first at>.   ; when recorded
```

Never in a message: a stack trace (that is `:seon.error/frame` data), a
`kind`, a class name as classification, a value dump (offending values are
rendered through the value renderer under the AI profile, never inlined),
or prose that restates the schema key. A test asserts the facts a message
renders (member, path, expected, fix) never the exact string.

## 2. Conversion rules per site (literal; the inventory's R1–R8 made concrete)

The inventory `docs/prds/steward-platform/research/error-kind-retirement-inventory-2026-09-19.md`
lists every remaining site by file and line with one of these actions.
Apply exactly; do not rewrite surrounding code.

| Action | Site shape | Do this |
|---|---|---|
| R1 producer stamp | `{:seon.error/kind ::x …}` or `(refuse! ::x …)` building a map | Replace with `error/diagnostic` per 1.1: at/layer/operation from THIS function; the facet the owner's resource declares for this failure (declare it if absent, section 3); keep `:seon.error/data`/evidence; update THIS function's output contract per 1.2. |
| R2 consumer branch | `(error/error? v)`, `(:seon.error/kind v)`, `(= ::x (:seon.error/kind v))`, `(case (:seon.error/kind v) …)` | Branch on the facet's distinguishing member per 1.3, using the callee's declared union; when the callee still declares `:seon.error/value`, use the base-three check AND list the callee as debt. |
| R3 class marker | `{:seon.error/class true}` on a schema; `[:= true]` boolean members; a `-error` schema that only stamps | Delete the marker; make the schema `[:and :seon.error/base [:map …]]` with its substantive members; update references. |
| R4 kind member in a schema | `[:seon.error/kind …]` inside a `[:map …]` | Delete the member; the map's other required members are the facet; if the map has NO other required member, it was a kind-only class: replace by the owning facet. |
| R5 kind projection | pull selectors, `select-keys`, renders that carry `:seon.error/kind` | Carry the base three + the facet's members instead; consumers updated per R2. |
| R6 explanation text | prose that says "kind X" or builds a message from the kind | Rebuild from `problem-sentence`/facet keys per 1.4. |
| R7 kind destructuring | `{kind :seon.error/kind}` in a `let`/fn args | Remove; route by facet member or concrete field; update every use in the function. |
| R8 test | `(is (= ::x (:seon.error/kind data)))`, `(is (error/error? v))`, `(is (not (:seon.error/kind v)))` | Assert the facet (`(is (contains? (error/facets projection v) :owner/facet))` on the canonical fixture, or the distinguishing member) PLUS the concrete evidence that distinguished the old case (the member, the path, the expected description, the operation). Never assert only the disappearance of a marker. |

Before/after, taken from the landed sweep, for the three commonest shapes:

```clojure
;; R2, from 9d36367fa (src/seon/test.clj) — transitional form the lane REPLACES
;; once seon.test/select declares :seon.test/selection-error (see 1.3):
-  (if (error/error? seeds) seeds
+  (if (contains? seeds :seon.test.selection/refusal) seeds

;; R1, from 1b732b28e (src/seon/instrument.clj): the refusal carries its
;; exact declared key and the wrapper validates it
-  (reject! {:seon.error/kind ::contract-violated …})
+  (reject! (error/diagnostic {:seon.error/at … :seon.error/layer :seon.instrument/contract
+                              :seon.error/operation `compiled-wrapper … :seon.instrument/check :input …}))

;; R8, from dc36dbcaf (test/seon/fn_test.clj)
-  (is (= :seon.fn/index-refused (:seon.error/kind refusal)))
+  (is (= `index! (:seon.error/operation refusal)))
+  (is (contains? refusal :seon.fn/index-refused))          ; the facet's distinguishing member
+  (is (= [:seon.fn/sym 'seon.fixture/dup] (:seon.error/diagnostic-offending refusal)))
```

## 3. Declaring a facet the owner's resource lacks

When a producer's failure has no facet yet, the lane adds ONE to the
owner's resource, next to that owner's other declarations:

```clojure
;; resources/seon/schemas/<owner>.edn
:<owner>/refusal-error            ; name = the failure, not "kind"
[:and {:seon.db/attributes true
       :seon.render/ai seon.error/render-ai
       :seon.render/html seon.error/render-html}
 :seon.error/base
 [:map
  [:<owner>/refusal-member <its declared scalar>]      ; at least one REQUIRED member
  [:<owner>/other-evidence {:optional true} …]]]
```

Rules: at least one required member that no sibling facet in the same
declared union shares; scalar members declared once in the owner's
resource; a ref only for a genuine entity relation, with its deletion
behaviour in the docstring (the data-modeling guide's dial); render pairs
are the base pair unless the owner already has its own; no identity
attribute on a facet (storage is by the occurrence owner). The registry
refuses duplicate or contradictory declarations at load, which is the check.

## 4. Verification a lane runs, in order, before every commit

1. `rg -n ':seon.error/kind|seon.error/class|error/error\?' <owned files>` → zero lines.
2. `rg -n 'contains\? [a-z-]+ :seon.error/at' <owned files>` → every hit has a `;; debt: <callee> declares :seon.error/value` comment on the same line, or is gone.
3. Every function in the owned files whose body can return an error declares its exact facets in its output (grep the file for `:seon.error/value]` in `:malli/schema` outputs → zero, except documented polymorphic inputs).
4. `clojure -M -e "(require '<each owned namespace>) (println :loads)"` → `:loads`.
5. `bin/test-fast --paths <owned files> -- <owned test namespaces>` → a tally; every previously green test still green; every changed R8 test asserts a facet + evidence.
6. Path-limited commit naming the files and the counts before/after.

## 5. Partition (file-disjoint lanes; `gpt-5.6-sol` low unless marked)

Counts are `:seon.error/kind` references at `bcb0ef256`. Held files are
never edited by a sweep lane; their sites go to the lane that holds them.

| Lane | Files (src) | Refs | Tests | Notes |
|---|---|---|---|---|
| render (running, astra low) | render.clj, render/transcript, web, walk, data | 143 | test/seon/render/*, render_test | already launched |
| render-2 | render/hiccup, lint, ns, test, value | ~30 | their tests | after render lands |
| turn-cluster | turn.clj 68, cluster.clj 33, cluster/message 17, agent 17, prompt 10, source 8, wake, agent.clj 13 | ~170 | turn_test, cluster_test, cluster/* tests | the largest; astra low if sol stalls |
| sci-fn-program | sci/eval 39, sci/kernel 8, sci/admit, fn.clj 25, program.cljc 11, schema/edn 12, schema/datahike | ~100 | their tests | fn.clj had 18 predicate sites converted; keep them |
| ops-effects | ai.clj 26, shell/jvm 17, operator.clj 12, operator/state 15, effect 12, bootstrap 12, schedule 10, flow 10, env 10, maintenance 8, config 8, context 9, edit 9, problems 10, issue 15, issue/detect 8, background, blob, store | ~200 | their tests | many small owners; two sol lanes if > 4 h |
| my-protocol | src/my/* | ~40 | test/my/* | agent-facing: messages matter most here (1.4) |
| test-system (A1's files) | test.clj 57, test/runner 77, selection, accretion 8, arm 7 | ~150 | | A1 converts its own after its selector work; not a sweep lane |
| error owners (1a's files) | db.clj 29, schema.clj 33, error.clj, instrument.clj, cluster/status | | | 1a lane |
| bin/script | bin/seon-hook 4, script/seon/fresh_operator.clj, dev scripts | 51 | | last; the hook and operator read stored error values |

Each lane's spec is section 7 verbatim with its file list substituted. Two
lanes may run at once beside A1 and 1a (three workers total is the cap).

## 6. Stop conditions (list, never improvise)

- Two facets in one declared union share every required member.
- A producer whose failure has no natural owner resource (a cross-cutting
  boundary): list it with the candidate owner.
- A consumer that needs to distinguish more than the facet members express
  (e.g. two different read refusals in one facet): list it; the owner's
  facet needs a member, which is a schema decision for the orchestrator.
- A test that only checked the marker and has no evidence to assert: list
  it with a proposed assertion; do not delete the test.
- Any `:seon.error/kind` that is a STORED DATOM on a live cluster: not the
  lane's problem; it is retired at the reset (RESET NEEDED in the note).

## 7. Lane spec template (copy verbatim; fill the two bracketed lists)

```
You are the bounded lane "kind sweep — [group]" in /Users/sean/src/seon (branch steward-platform). Read AGENTS.md sections 0-5 end to end, then docs/prds/steward-platform/plan/error-conversion-prd-2026-09-20.md END TO END (it is the whole assignment), then the sections of docs/prds/steward-platform/research/error-kind-retirement-inventory-2026-09-19.md for your files, then resources/seon/schemas/seon.error.edn and the owner resources of your files.
OWNED: [files]; their tests; the owner resources of your files for facet declarations (section 3); your landing note docs/prds/steward-platform/research/kind-sweep-[group]-2026-09-20.md. HELD (read only): every other file with kind references (other lanes); src/seon/error.clj, instrument.clj, schema.clj, db.clj, cluster/status.clj (lane 1a); src/seon/test.clj, test/runner.clj, test/selection.clj, test/accretion.clj, test/arm.clj (lane A1). Check git status first; preserve unrelated uncommitted edits.
DO: PRD section 2 at every inventoried site in your files; section 3 when a facet is missing; section 4 before every commit; section 6 stop conditions listed, never improvised. Zero :seon.error/kind, zero class markers, zero inline base-three checks without a debt comment, exact error outputs on every function in your files when you stop.
ITERATE: bin/test-fast --paths [files] -- [test namespaces]; HEAD must load after each commit. Never bin/test, --all, --full, or bin/seon lifecycle commands; default is the owner's window.
DELIVER: path-limited commits per file; the landing note with per-file counts before/after, facets declared, debt list (callee → sites), stop-condition list, the fast tally, the cold gate owed. Words: verify / falsify / probe.
```

## 8. Acceptance for the whole conversion (orchestrator)

1. `rg -c ':seon.error/kind' src test resources bin script` → 0 (today: 937 / 882 / 0 / 51).
2. `rg -c 'seon.error/class|error/error\?' src test resources` → 0.
3. Inline base-three checks without a debt comment → 0; debt list → 0 after the owners' lanes.
4. Every `:malli/schema` output in `src/` names exact facets; `:seon.error/value` appears only as a documented polymorphic input. Enforced later by the contract seam (wave 2a); measured now by grep.
5. `seon.error-test seon.instrument-test seon.schema-test` green; each sweep lane's namespaces green; `bin/test --paths <all touched> -- <all touched namespaces>` green; `--platform` green.
6. One reset carries the retired attributes (RESET NEEDED lists union) and Juniper is reseeded; the live debug page renders a real fault through the base pair with the 1.4 grammar (a recorded observation, not a claim).
