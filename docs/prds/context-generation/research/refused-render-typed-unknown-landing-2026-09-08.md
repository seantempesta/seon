---
type: research
status: complete
date: 2026-09-08
tags: [research, render, error, print, repl]
prd: docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md
---

# A refused render producer contributes a stable typed unknown

PRD [§8 step 5](../plan/agent-record-and-turn-loop-prd-2026-09-07.md), render
half. Grounding read end to end: `AGENTS.md`, the PRD, the Opus review
[byte-identity items 1–2 and B5](prd-review-turn-loop-opus-2026-09-07.md),
`src/seon/render.clj`, and the `data-oriented-clojure` and `repl` skills.

## 1. The defect, stated exactly

Every render producer is invoked through `sci.kernel/invoke` under
`:seon.sci.eval/time-limit-ms`, and a refusal contributed **absence**: the
walk kept a unit only when its output was a non-empty string with no
`:seon.error/value`. Two consequences, one disease:

1. **Byte identity was unreachable.** A slower machine refuses a producer a
   faster one completes, and the refusing unit's bytes then vanish from the
   prompt entirely. "Same database value, same adopted commit, same profile ⇒
   same bytes" could not hold while the clock decided membership.
2. **It was a standing §2.4 violation** independent of the PRD — an
   unavailable observation must be the typed unknown, never absence. The
   substitute where one existed was the anonymous sentence `Renderer
   unavailable.`, which names neither the producer nor why it stopped.

## 2. What landed

**One new value, one constructor, two projections, all in `seon.render`.**

- `:seon.render/unknown` (`resources/seon/schemas/seon.render.edn`) — an
  error class whose marker attribute is `:seon.render.unknown/reason`
  (`:time-limit` | `:refused` | `:unselected`), carrying the producer's
  qualified symbol, the render output, the refusal's own `:seon.error/kind`,
  the throwable's class, and the `:seon.render.call/id` of the unit. It
  declares its own `:seon.render/ai` and `:seon.render/html` producers, so it
  renders through the ordinary declared-producer chain with no special case.
- `seon.render/unknown` — THE constructor. `seon.render/refused` reads any
  refusal as one (a selection refusal that never reached a producer becomes
  `:unselected` with its own kind as `:seon.render.unknown/refusal`), and
  `seon.render/unknown-output` is the total `output → bytes` entrance.
- `seon.render/unknown-ai` — ONE line of data, never comment-shaped
  (ruling 45), printed from a sorted map through `admit/canonical-edn`, the
  same rule `seon.repl/missing-text` follows:

  ```
  #:seon.render.unknown{:call [:seon.render/ai [:seon.cluster.agent/id "a"] 1], :output :seon.render/ai, :producer my.render-probe/slow, :reason :time-limit, :refusal :seon.sci.kernel/time-limit, :throwable "clojure.lang.ExceptionInfo"}
  ```

- `seon.render/unknown-html` — the same evidence as one labeled block
  (`seon-render-unknown`, label + `<code>` carrying the identical line), so
  the page and the prompt say the same thing about the same absence.

**The seam asks the invocation, not the value.** `invoke-selected` now
returns the guarded invocation's WHOLE result rather than only
`:seon.sci.admit/value`, and `invocation-unknown` reads
`:seon.sci.admit/record`'s `:seon.eval/outcome` — the kernel's stamped fact.
That is what distinguishes a producer that *did not return* from one that
legitimately RETURNED an ordinary `:seon.error` value; asking the shape of the
returned value would have confused the two forever. `invoked` is the one
wrapper every call site now uses (`raw-output`, `invoke-producer`), and
`project-node*` uses the same classifier for a NESTED producer — where a
refusal previously fell back to the unprojected print node, which is the same
absence-reads-as-health defect one level down.

**`renderer-failure` renders the typed unknown.** Its two audience values are
now `unknown-ai` / `unknown-html` of the failure it was handed, so the walk's
existing `(or (get failure-outcome output) …)` already picks them up wherever
a namespace owner exists.

## 3. Stability is by construction, and one thing is deliberately dropped

The unknown carries only the producer, the call, the reason, the refusal kind
and the throwable class. The kernel's diagnostic record —
`:seon.eval/duration-ms`, `:seon.eval/fn-entries`,
`:seon.eval/allocated-bytes` — and the synthesized message `Ran out of time
after 52ms.` are **not** carried. They are the only parts of a refusal that
differ run to run, and carrying them would both move prompt bytes and defeat
`failure-message-id`, which digests the failure: today every repeat of one
broken renderer mints a NEW owner message, because the duration is inside the
digest. Making the unknown stable fixes that idempotence claim in the same
beat. The record is not lost to the system — `invoke-selected` now returns it
— it is simply not part of what a refusal contributes to a projection.

## 4. The walk hunk — for the turn-loop lane to apply

The typed unknown reaches the page today. It reaches the PROMPT only after two
hunks in `src/seon/render/walk.clj`, which this lane does not own.

**Hunk A — `neighborhood`, the failure substitution (`walk.clj:655-664`).**
The owner-gated `failure-outcome` and the anonymous fallback compute the same
value two ways; `render/unknown-output` is total and needs no owner:

```clojure
                             failure
                             (assoc :seon.error/value failure
                                    :seon.render/output
-                                   (or (get failure-outcome output)
-                                       (if (= output :seon.render/html)
-                                         [:div
-                                          {:class "seon-render-unavailable"}
-                                          "renderer unavailable"]
-                                         "Renderer unavailable.")))
+                                   (render/unknown-output output failure))
```

`failure-outcome` stays exactly as it is for its `:seon.db/tx-data` — the
owner message is unaffected.

**Hunk B — `history-entries` (`walk.clj:830-834`).**

```clojure
               (when (and (string? rendered)
-                         (seq rendered)
-                         (nil? (:seon.error/value unit)))
+                         (seq rendered))
```

Admission is now "the unit produced bytes", and a refused render's bytes ARE
the typed unknown. The two units that legitimately contribute nothing are
unaffected: the `::elided` distance-cap marker (`walk.clj:567-582`) and the
`::no-such-entity` unit (`walk.clj:607-614`) carry no `:seon.render/output` at
all, so `(string? rendered)` still excludes them.

The docstring paragraph beginning **"A REFUSED RENDER IS NOT PROMPT
CONTENT"** (`walk.clj:817-823`) is now false and must be replaced in the same
commit. Its own reasoning is the argument for the change: splicing the
sentence `Renderer unavailable.` told the agent nothing it could act on and
hid which renderer broke — the typed unknown names the producer, the call and
the reason, which is exactly what the agent needs to repair it.

## 5. Proofs

`test/seon/render_coverage_test.clj`, two regressions, both driven through the
real guarded boundary with probe producers installed into this test's own SCI
fork (`sci/eval-string*` + `kernel/mark-installed!`; the contract arm is armed
with `instrument/wrap-interpreted` under `:panic`, the same call
`seon.sci.eval/install-function-contract!` makes for a program row):

- `a-refused-render-producer-contributes-a-stable-typed-unknown` — a producer
  that runs past a 50 ms limit yields `:time-limit` naming itself, its call
  and `:seon.sci.kernel/time-limit`; one that throws yields `:refused` with the
  throwable's class; one whose declared contract refuses yields `:refused`
  with `:seon.instrument/contract-violated`; the AI projection is ONE line,
  not comment-shaped; the HTML projection is a labeled block; and a producer
  that RETURNS an error value is not a refusal at all.
- `one-refused-producer-moves-no-other-rendered-bytes` — two passes with
  different limits (50 ms, 150 ms) make the refusing producer run for
  measurably different wall-clock times; the working producer's bytes and the
  refusal's own line are identical across both.

Gates: `bin/test seon.render-coverage-test seon.render-simplification-test
seon.render.value-options-test seon.repl-test` and `bin/test --platform`.

## 6. Gate results, and the pre-existing reds this lane did NOT cause

`bin/test --platform` — **73 tests, 398 assertions, 0 failures, 0 errors.**

`bin/test seon.render-coverage-test` — 7 tests, 108 assertions, 9 failures,
all 9 in two tests. `bin/test seon.render-simplification-test
seon.render.value-options-test seon.repl-test` — 36 tests, 168 assertions,
4 failing tests.

Every one of those six failing tests is **red at clean HEAD** (`31fb0b0b4`),
proven by running the same selections in a detached worktree of that commit
with this lane's changes absent:

| test | HEAD | with this lane |
|---|---|---|
| `render-coverage/effect-receipts-render-state-from-attribute-presence` | 8 failures | 8 failures |
| `render-coverage/important-runtime-entities-declare-and-use-readable-faces` | 1 failure | 1 failure |
| `render-simplification/authored-source-invocation-reuses-one-stored-run-across-presentations` | red | red |
| `render-simplification/nested-values-render-their-declared-faces` | red | red |
| `render-simplification/non-rendering-more-specific-schema-does-not-shadow-agent-identity` | red | red |
| `render.value-options/data-response-reads-the-presentation-window-per-request` | red | red |

The coverage counts match exactly (9 and 9) at the same assertions, offset
only by the three `require` lines this lane added. `seon.repl-test` is green.

Two of those reds are worth naming because the typed unknown changed how they
READ, not whether they fail:

- `nested-values-render-their-declared-faces` now shows
  `:seon.render.unknown/producer seon.render.value/render-database-identity-ai`
  with `:refusal :seon.instrument/contract-violated` where the old code
  silently substituted the unprojected print node. The refusal is real and
  pre-existing: `render-database-identity-ai` declares
  `[:=> [:cat :seon.render/unit] :string]`, `:seon.render/unit` is
  `[:map-of :qualified-keyword …]`, and the identity unit it is actually
  handed carries Datahike's own unqualified `:db-name` and `:t`. A declared
  contract that its only real caller cannot satisfy is the defect; making the
  refusal visible is this lane's point, and fixing that contract belongs to
  whoever owns `seon.render.value`'s identity face.
- `non-rendering-more-specific-schema-does-not-shadow-agent-identity` fails on
  authored comment prose now prefixed to `seon.cluster.agent/render-identity-ai`
  — another lane's edit, unrelated to this one.

One advisory in the platform run, `persistent results NOT recorded:
:seon.instrument/contract-violated seon.schema.datahike/resolve-datahike-form-in`,
is the test runner's own persistence path (a lane is editing
`src/seon/test/runner.clj` right now). The three schemas this lane added
resolve: `:seon.render/unknown`, `/unknown-request` and `/unknown-reason` are
all present in the packaged forms, and none is a storable attribute family.
