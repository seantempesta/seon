---
type: research
status: complete
date: 2026-09-07
tags: [research, repl, render, run-loop, context, prompt]
---

# The prompt joined the one generator; the entity merge did not — 2026-09-07

Landing note for the `prompt-and-entity-merge` lane, against
[the agent record and the REPL response](../plan/agent-record-and-repl-response-prd-2026-09-07.md)
§4/§5/§7 step 2 and the frictions in
[the REPL and record audit](audit-repl-and-record-2026-09-07.md).

**Read §4 first.** One of the three assigned pieces is not landed, and the
reason is a measurement, not a preference.

## 1. The provider prompt is on the one generator (audit B3)

`seon.render.walk/generic-history-entries` and the `:seon.render/form`
neighbourhood pass in `walk/history` are deleted. The prompt now reads each
unit's own `:seon.render/ai` bytes — for a run, `seon.render.transcript`'s
history, every evaluation of which comes from `seon.repl/text`.

What the old shape did, measured on the live dev cluster `juniper-context`
(root `tmp/juniper-context-live`, read-only probe through `eval_clj`): the
`/form` and `/ai` neighbourhoods return the **same 56 units at the same 56
paths**. `generic-history-entries` joined them by `[lookup path]`, `pr-str`'d
the `/form` output into a `ns=> ` line of its own, and stapled the `/ai`
render underneath as a "printed value". So the model read a prompt line that
the page never showed and the history unit never produced — including, for a
run, a second prompt line wrapped around the transcript's own `#:seon.repl{…}`
bytes.

Two consequences worth naming:

- **The admission gate had to change with it.** The old gate was
  `(and (seq? form) (string? printed-value))` — a fact question ("does this
  unit belong in the prompt?") answered by the SHAPE of a rendered form. It
  is now "this unit's own projection succeeded and produced text". The
  success half matters: `neighborhood` substitutes the literal sentence
  `"Renderer unavailable."` for a producer that failed and records the
  failure on the unit, and the old gate let that sentence into the model's
  context — 28 copies of it in one fixture — telling the agent nothing it
  could act on and hiding which renderer broke. The failure already reaches
  the owning agent as a message, which is where a broken renderer belongs.
- **`:seon.render.history/form` became `:seon.render.history/subject`,** the
  unit's own lookup. `seon.render.web/append-history` uses it to decide which
  observation a new current task supersedes; it was comparing rendered forms
  to answer an identity question.

`seon.cluster.prompt` needed no change — it carries the string
`seon.render.web/context-pass` builds — and the debug page's prospective
prompt (`web.clj`, `render.walk/history` → joined bytes) follows for free.

`transcript/input-text` and `transcript/receipt-text` — the last two named
formatters still standing — were two arms calling `repl/text` with the same
argument, and collapse to one `evaluation-text`. `repl/text` already emits a
prompt line and no response when nothing has settled, so the frozen-form /
settled-evaluation distinction the two arms encoded is one the generator
derives. Every one of the 98 declared `:seon.render/ai|html|form` producers
still resolves (checked by resolving each symbol from
`schema/canonical-schema-rows`), so nothing points at a deleted function.

## 2. The facts the response advertises are now written

- **`:seon.eval/duration-ms`** (audit F3). The kernel measures it on every
  armed and unarmed path (`seon.sci.kernel/unarmed-record`, the armed
  record), the evaluation carried it as far as
  `seon.cluster.run/evaluation-facts`, and settlement dropped it there. Live
  before: **0 of 9** evaluations carried it, so `:ms` was in the documented
  grammar and in no response ever emitted.
- **`:seon.print/length` / `:seon.print/level`** (audit C3, two halves).
  Settlement now projects them out of the evaluation's `:seon.print/options`,
  captured while sci's binding is still installed; and `seon.repl`'s emitter
  reads the STORED keys instead of `:seon.print/options`, a key no stored
  evaluation carries. Both halves were needed: either alone still discards
  every per-form `set!` of `*print-length*` / `*print-level*`.
- **`:seon.cluster.eval/author`** (audit C1). Declared this range and written
  by nobody, while all three sites that start an evaluation already know the
  answer: a model-reply freeze is `:agent`, a system run and a generated
  append are `:system`. Authorship has to exist before
  `:seon.cluster.work/situation` can be deleted on the grounds that it says
  the same thing.

`seon.cluster.loop/committed-attributes` derives from the declared entity
map, so the boot-installability proof covers all four with no list to
maintain.

## 3. The frictions

| audit item | disposition |
|---|---|
| **C3** `entity-emission` read `:seon.print/options` | **fixed**, both halves (§2) |
| **F5** `render-ai` threw instead of refusing | **fixed**: `:seon.cluster.eval/source` is optional in `:seon.repl/emission`, so the existing `(when (seq source) …)` guard can run. A `:seon.error/value` arriving where a unit was expected now renders nil instead of raising a contract violation that took a whole page derivation with it |
| **C4** a windowed result binds a truncated collection | **fixed**, in ONE place. `seon.sci.admit/restorable-node` gains a second arity taking the evaluation's storage facts and refusing a result whose `result-blob` is present or whose `result-size` exceeds the stored node. `bind-stored-results!`, `seon.repl/entity-emission` and `transcript/entry-handle` all consult it, so the binder and the emitter cannot disagree — a handle emitted for a value nothing binds is a symbol that resolves to nothing |
| **F2** `Execution error () at (REPL:1).` | **fixed**: recorded triage still names the throwable's class (verified on a real `ArithmeticException` and a real sci compile error); the fallback says `Execution error.` plus the message and invents no location |
| **F1** the clipped value's requery text | **CANNOT be fixed from here** — see §3.1 |
| **F6** render-fault dedup and the unchecked `offer!` | landed by the `reply-order-and-faults` lane in `11176e6db`, not touched here |
| **F4** trailing prose | landed by the same lane in `be2a4ae46`, not touched here |

### 3.1 Why F1 is an issue and not a fix

The audit proposed handing `value-text` the unit's elision root and render
profile. That cannot work, and the reason is worth recording because it is
the recurring class:

`seon.sci.admit` mints a cut as a BARE scalar — `{:seon.print/face
:seon.print/elided}`, no omitted count, no path, no requery field. `emit
::elided` reads the NODE and nothing else, and `seon.print/fit` — the one
function whose `elision-node`/`preserve-requery` would attach a profile's
`::requery-id` — is a no-op today because presentation-size limits are
disabled. Proven both ways, identical bytes:

```clojure
(print/emit-text node (assoc (print/default-options)
                             :seon.print/requery-id [:seon.cluster.eval/id "x"]))
(print/emit-text (print/fit node {:seon.render.profile/id :seon.render.profile/agent
                                  :seon.print/requery-id [:seon.cluster.eval/id "x"]})
                 (print/default-options))
;; both => "[0 … 1 more subtree; requery refused: no stable identity was supplied …]"
```

So every caller above `seon.print` already computes the right identity and
hands it to a seam that discards it. The identity belongs where the cut is
MADE. Filed as
[an admission-minted elision node cannot name its requery identity](../../../seon/issues/admission-elision-cannot-name-its-requery-identity.md);
`seon.sci.admit` and `seon.print.cljc` are neither owned nor protected by
this lane's assignment.

## 4. What is NOT landed: one evaluation entity per form

**The merge of `:seon.cluster.run.form/*` into `:seon.cluster.eval` is not
done, and it should not be one lane's slice.** The measurement, taken at
`1e330e133`:

```
rg 'run\.form' src/ resources/ test/  →  791 references across 74 files
```

Broken down:

- **15 `src/` files.** Eight of them are outside this lane's owned paths and
  each one queries or writes `:seon.cluster.run.form/*` directly:
  `src/seon/context.clj` (10), `src/seon/problems.clj` (14),
  `src/seon/effect.clj` (2), `src/seon/fn.clj` (10),
  `src/seon/cluster/message.clj` (1), `src/seon/eval/drive.clj` (5),
  `src/seon/bootstrap_drive.clj` (6), and `src/seon/cluster/wake.clj` (1,
  a docstring) — which is a PROTECTED path.
- **11 schema resources** beyond the family's own file, including
  `seon.effect.edn`, `seon.problems.edn`, `seon.test.accretion.edn`,
  `seon.env.edn` and `seon.cluster.curate.edn`.
- **~55 test namespaces.**

`:seon.cluster.work/situation` is a second, separate blocker: it is the
`:dispatch` key of the `:seon.cluster.work/next` multi-schema
(`resources/seon/schemas/seon.cluster.work.edn`), so deleting it is a
redesign of the work protocol's return shape, the loop's whole `case`, and
the 91 references across 14 files that read it. That is the right change —
PRD §6 and note 3 §5.4 argue it correctly, and this lane landed the
`:seon.cluster.eval/author` fact the deletion depends on — but it is a
program step, not a slice.

**What this lane did land toward it** is the accretion half: the evaluation
entity now carries `author`, `duration-ms`, `print/length` and `print/level`,
which are four of the facts the merged entity needs and none of which the
form entity ever held. The remaining work is the deletion half.

### 4.1 The datoms, measured

Counted per entity on the live dev cluster `juniper-context`, read-only
(`db/datoms :eavt` per entity id), 9 evaluations and their 9 twin forms:

| family | entities | datoms | mean |
|---|---|---|---|
| `:seon.cluster.eval` | 9 | 206 | 22.9 |
| `:seon.cluster.run.form` | 9 | 54 | 6.0 |

**Every form entity is exactly 6 datoms**, and four of them —
`ordinal`, `source`, `ns`, `run` — are already on the evaluation. The other
two are `:seon.cluster.run.form/id` (the second `:db.unique/identity`, and
with it the ambiguity class documented at `run.clj:694-718`) and
`:seon.cluster.run.form/author`, which THIS LANE just made an evaluation
fact. So the merge removes **6 datoms per (run, ordinal) and adds none** —
a sharper number than note 3 §5.3's "~10", and it means the evaluation entity
is now a strict superset of the form entity's content.

The accretion this lane landed costs +1 datom at the freeze
(`:seon.cluster.eval/author`) and +1 to +3 at settlement (`duration-ms`, plus
`print/length` / `print/level` when the form set them). Per pair: **≈28.9
before → ≈30.9 now → ≈24.9 after the merge.** No "after" number for the merge
itself is claimed, because the merge did not happen.

**The schema needs no data reset.** All four attributes
(`:seon.cluster.eval/author`, `:seon.eval/duration-ms`, `:seon.print/length`,
`:seon.print/level`) are already installed on the dev cluster — verified live
against `(:schema db)` — because they were declared in the
`:seon.cluster.eval/receipt` entity map earlier in this range. Every change
here is accretion onto an installed schema.

## 4b. Four more defects this proof found

The end-to-end regression is `seon.render.transcript-test/`
`one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`. It
found four things no unit test could, all of the same shape — a fact that
exists on one path and is dropped on another:

1. **`:seon.eval/duration-ms` had two spellings.** Storage read it out of
   `[:seon.sci.admit/record :seon.eval/duration-ms]`; the page's in-memory
   render read `:seon.eval/duration-ms` on the evaluation, which nothing set.
   So a stored evaluation carried `:ms` and its own in-memory record did not.
   The evaluation now carries the one spelling (`seon.sci.eval`'s success and
   failure projections hoist it out of the record) and both readers use it.
2. **The transcript ignored the evaluation's print options.** `bounded-result`
   merged only the RENDER UNIT's `:seon.print/options`, so a form that
   `set!`s `*print-length*` had its choice recorded and then printed under
   the shipped default anyway. The entry now carries `:seon.print/length` /
   `/level` and the emitter uses them.
3. **The in-memory render dropped the agent's comment.** It rides the
   admitted form until settlement moves it to the evaluation, and
   `candidate-history`'s in-memory arm read only the evaluation — so the
   page's preview and the stored history differed by a line.
4. **`run/record-evaluated-tx` dropped the comment too**, so an evaluation
   saved from the page lost prose a settled one keeps.
5. **The loop named a handle for a value the node never held.**
   `evaluate-sources` assoc'd `:seon.repl/handle` whenever the frozen
   evaluation's entity id resolved, with no check that the admitted node was
   restorable. `(def x 1)` admits to a `:seon.print/var` face and
   `(in-ns …)` to `:seon.print/object` — names, not values — so
   `bind-stored-results!` refuses them on the NEXT turn. The page therefore
   rendered `:result result/eN` for a symbol that would resolve to nothing by
   the time the agent read it, and the page's bytes differed from the stored
   bytes they are supposed to equal. It is ruling 59c's own failure mode
   (`a handle that resolves to a description of a value is worse than no
   handle`) one step further along: a handle that resolves to nothing. The
   loop now consults the same `restorable-node` predicate as the binder and
   both emitters, so the four cannot disagree.

## 5. Gate tallies

Every red below is attributed against a measured baseline in a detached
worktree at `a3efaa1d1` (`tmp/merge-baseline`, `reference-code` symlinked),
not asserted.

### 5.1 The baseline that changes the attribution

`bin/test seon.cluster.prompt-test seon.render.history-test
seon.render.walk-test` at `a3efaa1d1`, BEFORE the walk change: **20 tests /
162 assertions / 18 failures, 0 errors**, nine red tests —

- `seon.cluster.prompt-test/` `a-second-run-replaces-the-opening-task-and-puts-current-task-last`,
  `basis-only-transactions-do-not-append-history`,
  `every-call-derives-the-current-basis`,
  `identical-context-reuses-retained-ai-render-bytes`,
  `one-new-message-appends-exactly-one-entry`,
  `prompt-is-derived-append-only-repl-history`,
  `unchanged-acquisition-performs-zero-database-door-reads` (7)
- `seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain` (1)
- `seon.render.walk-test/one-basis-projection-covers-the-complete-walk` (1)

**All nine were already red.** After the change the same selection leaves
**six** of the prompt-test seven —
`basis-only-transactions-do-not-append-history` now passes — plus the two
others, unchanged. The walk rewrite therefore introduced no red in that
selection and closed one.

### 5.2 The second baseline

`bin/test seon.render.transcript-test seon.cluster.reply-test
seon.cluster.evaluate-sources-test seon.repl-test seon.sci.eval-test
seon.bootstrap-test` at `a3efaa1d1`: **119 tests / 751 assertions / 31
failures, 1 error**, twelve red tests —

- `seon.cluster.reply-test/` `every-refusal-is-a-value`,
  `every-refusal-matches-its-declared-error-class`,
  `forms-run-and-prose-becomes-source-comments` (3)
- `seon.render.transcript-test/` `a-tight-budget-degrades-then-elides-loudly`,
  `every-generated-history-is-ordered-total-and-token-bounded`,
  `malformed-receipt-bytes-and-any-unique-about-stay-replayable`,
  `populated-history-restores-the-repl-fidelity-checklist`,
  `receipt-content-enters-the-shared-capped-floor`,
  `same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order`,
  `supersession-chains-vanish-before-token-accounting`,
  `tight-budgets-pull-only-a-budget-derived-newest-candidate-set` (8)
- `seon.sci.eval-test/runtime-function-rows-carry-parsed-contract-facts` (1)

Exactly the disabled-rendering-limits and message-sentence families the
`repl-grammar-greens` note §1.2 recorded, plus the program-row absence its §5
recorded. `seon.repl-test`, `seon.cluster.evaluate-sources-test` and
`seon.bootstrap-test` are green at the baseline and stay green.

### 5.3 The assigned gate, after

`bin/test seon.cluster.reply-test seon.cluster.run-test seon.cluster.loop-test
seon.cluster.turn-test seon.render.transcript-test seon.render.walk-test
seon.cluster.prompt-test seon.bootstrap-test seon.repl-test
seon.sci.eval-test seon.cluster.evaluate-sources-test` —
**237 tests / 1469 assertions / 50 failures, 3 errors**, 25 red tests.

**Twenty-four of the twenty-five are inherited**, each matched name-for-name
against one of the two baselines above:

| namespace | red | attribution |
|---|---|---|
| `seon.cluster.prompt-test` | 6 | §5.1 (7 at baseline; one now passes) |
| `seon.cluster.reply-test` | 3 | §5.2 |
| `seon.cluster.run-test` | 1 | §5.2 / result-handles §3 |
| `seon.cluster.turn-test` | 3 | repl-grammar-greens §5 |
| `seon.render.transcript-test` | 8 | §5.2 |
| `seon.render.walk-test` | 1 | §5.1 |
| `seon.sci.eval-test` | 2 | §5.2 / greens §5 |
| `seon.bootstrap-test`, `seon.repl-test`, `seon.cluster.evaluate-sources-test`, `seon.cluster.loop-test` | 0 | — |

The twenty-fifth was this lane's own new regression, and the defect it named
(§4b item 5) is fixed: re-run of `bin/test seon.render.transcript-test` gives
**19 tests / 288 assertions / 18 failures**, 8 red tests — exactly the eight
inherited ones — with
`one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`
**GREEN**.

`bin/test seon.repl-test` alone: **12 tests / 35 assertions / 0 failures,
0 errors.**

### 5.4 Why this matters

The six remaining prompt-test reds LOOK like they were
caused here (`"inspect this walk"` missing from the prompt, retained-render
counts at zero) and were not. They are the message-sentence family the
`repl-grammar-greens` note §1.2 already recorded as pre-existing: a message's
`:seon.render/ai` producer emits SOURCE (`(seon.cluster.message/format-ai
(seon.db/pull …))`), not the message's content, and the prompt text is never
executed — so the content reaches no model context on either grammar. That
is a real open defect and it is not this lane's; it is the reason PRD §7
step 4 exists.

## 6. The live dev cluster

`bin/seon --root tmp/juniper-context-live init --dev juniper-context` ran
through `development schema declarations`, `program reconciliation`, `loaded
definitions`, `development reload seon.render.transcript`, `SCI acquisition`
and `JVM instrumentation`, then withheld only its final commit-id fact
(`Source changed during development adoption`) because other lanes were
editing the shared tree during the ~40 s adoption. That refusal is on the
DIGEST, not the adoption: the running JVM serves this code. No data reset was
needed (§4.1).

**The prompt now carries the history unit's bytes verbatim.** Read-only probe
in the live JVM, with the cluster's own projection state bound (`jvm` mode
binds no custody, so an unbound probe gets 33
`:seon.render/missing-projection` refusals — which the new gate correctly
excludes and the old gate would have spliced into the model's context as 33
copies of `"Renderer unavailable."`):

```clojure
{:prompt-entries 28
 :prompt-bytes 67754
 :history-entries 4
 :every-history-byte-in-prompt? true     ; ← PRD §8's "page = history = prompt"
 :renderer-unavailable? false
 :retired-prompt-lines 0}                ; ← no `=> (seon.cluster.message/format-ai`
```

The four history entries are the run's stored evaluations, and their exact
bytes — comment above the prompt, one form per prompt line, one
`#:seon.repl{…}` response — appear in the provider prompt unchanged. Before
this change the prompt wrapped each of them in a second `ns=> (pr-str form)`
line the page never showed.

The debug page (`/feed/juniper?debug=true&output=:seon.render/ai`) renders
with no derivation failure, and the fresh in-memory previews now carry `:ms`:

```
#:seon.repl{:value "Agent     juniper\nNamespace my.agents.juniper\nCluster   juniper-context", :ms 5}
#:seon.repl{:value "Plan step 1 Render this plan clearly [juniper/render-plan] — open…", :ms 4}
```

The four STORED evaluations still show no `:ms`: they were settled by the
previous code and the data predates the change. Honest, and the reason the
regression proves `:ms` on a reply it evaluates itself rather than on this
cluster's existing rows.

## 7. Out of scope, seen while here

- **`(set! *print-length* 2)` does not survive to the next form.** The
  storage and emission halves are now correct — the setting form stores
  `:seon.print/length 2` and prints under it — but `seon.sci.eval/evaluate`
  reopens `sci/binding` from the process root for every form, so the agent's
  choice dies with that form's frame. An agent's context is a REPL session;
  a session's `set!` must hold. Filed as
  [a set! of print-length does not survive the next form](../../../seon/issues/a-set-of-print-length-does-not-survive-the-next-form.md).
- **A third grammar remains**, named by the audit §1 and not by this lane's
  assignment: `seon.render.transcript/undisposed-run-text` builds
  `"system=> " (pr-str form)` for the undisposed-run family. It is the same
  shape the `/form` pass just lost, on a different subject.
- The six inherited `seon.cluster.prompt-test` reds (§5.1) are a real open
  defect: a message's `:seon.render/ai` emits SOURCE, and the prompt text is
  never executed, so message content reaches no model context. PRD §7 step 4
  owns it.
