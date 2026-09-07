---
type: research
status: partial
date: 2026-09-07
tags: [research, repl, render, run-loop, context]
---

# The REPL response landed; the evaluation entity has not — 2026-09-07

What step 2 of
[the agent record and the REPL response](../plan/agent-record-and-repl-response-prd-2026-09-07.md)
asked for, split into what is in the tree and what is still ahead, with the
exact bytes and the measured numbers. Written by the `evaluation-entity`
lane against the spec in `tmp/lane-specs/evaluation-entity-0907.md`.

## 1. The exact bytes

`seon.repl/text` (`src/seon/repl.clj`) is the one generator. For an
evaluation whose agent wrote a comment above a form:

```
; the agent's comment, verbatim, above the prompt
my.agents.juniper=> (+ 1 1)
#:seon.repl{:value 2, :result result/e0, :ms 3}
```

Printed output is its own key, never folded into the value:

```
my.agents.juniper=> (do (println "hi") 41)
#:seon.repl{:value 41, :result result/e2, :out "hi\n", :ms 1}
```

A form that moved the session says where it landed, and only then:

```
my.agents.juniper=> (in-ns 'my.agents.probe)
#:seon.repl{:value #object[Namespace my.agents.probe], :result result/e1, :ns my.agents.probe, :ms 0}
```

A failure carries `:error` and no `:value`:

```
my.agents.juniper=> (/ 1 0)
#:seon.repl{:error "Execution error (ArithmeticException) at seon.repl-test (repl_test.clj:1).\nDivide by zero", :result result/e4, :ms 0}
```

A value bounded by the form's own print options carries the emitter's
elision inside `:value`, with no flag beside it:

```
my.agents.juniper=> (dir my.run)
#:seon.repl{:value [my.run/complete ...], :result result/e4, :ms 3}
```

A form that has settled nothing gets a prompt and no map, because absence of
a terminal fact still means running:

```
my.agents.probe=> (range)
```

Rules the emitter enforces rather than hopes for:

- key order is the vector `[:value :error :result :out :ns :ms]` that
  `response-entries` walks; no map is ever printed to produce it;
- `:value` and `:error` are mutually exclusive;
- the comment sits ABOVE the prompt, so a prompt line holds exactly one
  form — that is the whole reason the comment is now stored apart from the
  source (§2);
- the value is read from the stored admitted print node and emitted through
  `seon.print`; it is never stored a second time;
- ruling 45 holds: `seon.repl-test/nothing-emitted-is-comment-shaped`
  asserts no emitted line begins with `;` except the agent's own comment,
  and that `";; result/"` appears nowhere.

The HTML projection is the same evaluation with the comment as its own
element rather than a line of the prompt — which is the whole reason the
comment is stored apart from the source. Probed on the shape a pull
produces:

```clojure
[:article {:class "seon-family-entry seon-eval-entry"}
 [:p {:class "seon-eval-comment"} "; Works. But without a :malli/schema it stays my scratch."]
 [:pre [:code {:class "seon-eval-prompt"} "my.agents.juniper=> (dir my.run)"]]
 [:pre [:code {:class "seon-eval-response"}
        "#:seon.repl{:value [my.run/complete my.run/wait], :result result/e4, :out \"complete\\nwait\\n\", :ms 7}"]]]
```

`entity-emission` accepts both the evaluation's and the frozen form's
spelling of source, ordinal and namespace, so the two families render
through one grammar for as long as they remain two entities — the merge in
§4.1 removes the second spelling rather than a second renderer.

## 2. The comment is a fact, not a prefix

`seon.cluster.reply/plan-sources` used to concatenate the prose above a form
onto that form's source (`src/seon/cluster/reply.clj`, the `plan-source`
`str`). Live evidence from `juniper-context` before the change, one stored
`:seon.cluster.eval/source` verbatim:

```
"; Works. But without a :malli/schema it stays my scratch — nobody else can rely on it.\n(dir my.run)"
```

That string is why a prompt line could not hold one form. The reader now
returns the prose as `:seon.cluster.eval/comment` beside
`:seon.cluster.run.form/source`, and the freeze carries it onto the
evaluation row (`loop.clj` freeze, `run/receipt-row`, `run/system-run-tx`,
`run/append-generated-call`). `seon.bootstrap/entry-source` is deleted; its
caller supplies the two fields, and `bootstrap` keeps two private helpers —
`entry-form-source` for the stored source and prefix comparison,
`entry-cost-source` for the token estimate, which still costs the comment.

## 3. What is wired through the one generator

- the evaluation family's `:seon.render/ai` / `:seon.render/html` properties
  (`resources/seon/schemas/seon.cluster.eval.edn`) now name
  `seon.repl/render-ai` and `seon.repl/render-html`; the form family points
  at the same two, since one entity per (run, ordinal) is what they are
  becoming;
- `seon.render.transcript` produces every `:seon.render.history/bytes`
  through `seon.repl/text`. The transcript keeps the BOUNDING — the render
  unit's floor, elision root and print options — and hands the already
  bounded value on as the node; `seon.repl` keeps the GRAMMAR.
- deleted with their callers rewired: `run/render-receipt-ai|html`,
  `run/render-form-ai|html`, `transcript/prompted-source`,
  `transcript/input-text`'s own formatting, `transcript/receipt-text`'s own
  formatting, `transcript/receipt-printed-value`, `transcript/entry-bytes`,
  `transcript/execution-error-face`, `bootstrap/entry-source`.

## 4. What is NOT done

Named exactly, so nobody has to rediscover it:

1. **The evaluation entity is not merged.** `:seon.cluster.run.form/*` still
   mints a second entity per (run, ordinal), `:seon.cluster.run/forms` still
   exists, and with them `fold-namespace`, `form-data`,
   `generation-complete-call`/`-tx` and `:seon.cluster.work/situation`. The
   twin-identity ambiguity class (note 3 §5.3) is therefore still open, and
   the ~10 datoms per freeze it costs are still paid. **The datoms-per-freeze
   before/after measurement the spec asked for was not taken**, because
   there is no "after" yet; note 3 §2's measured 38/18/4 split stands as the
   before.
2. **The provider prompt does not use the generator.**
   `seon.render.walk/generic-history-entries` and the `:seon.render/form`
   neighbourhood pass in `walk/history` still build their own bytes, and
   `seon.render.web/history-text` joins those. The design that finishes it,
   from reading both: with the evaluation family's `:seon.render/ai` already
   pointing at `seon.repl/render-ai`, the `:seon.render/ai` pass ALONE
   already carries the right bytes, so the `/form` pass and the pairing can
   go. The care needed is the entry gate: `generic-history-entries` today
   admits a unit only when its `/form` producer emitted a `seq?`, and
   dropping the `/form` pass drops that gate. The replacement gate must be a
   fact — the unit's lookup naming `:seon.cluster.eval/id` (plus the message
   arm) — not the shape of a rendered value.
3. **Result rehydration landed but its gate had not reported.**
   `sci.eval/fork-for-turn` now binds `result/eN` for the run's already
   settled evaluations from their stored nodes, through the inverse that
   already existed — `seon.sci.admit/semantic-value` — so a later turn can
   `(count result/e0)`. A node that kept only a name (var, type, class,
   object, failed, throwable, truncated, elided, projected, pruned) binds
   NOTHING: ruling 59c's no-handle, because an unresolved symbol is honest
   and a handle onto a description of a value is not. The regression is
   `seon.sci.eval-test/a-later-turn-reaches-the-values-its-earlier-forms-produced`,
   and it **passes**: `bin/test seon.sci.eval-test` = 66 tests / 377
   assertions / 6 failures, 0 errors, with the rehydration test green. The
   six failures are two tests,
   `runtime-function-rows-carry-parsed-contract-facts` and
   `static-and-runtime-contracted-definitions-publish-identical-facts`, both
   failing because a runtime-defined function mints NO program row at all
   (`(:seon.fn/sym row)` is nil for `user/parsed-at-runtime` and
   `parity/same-facts`). That is program-graph indexing, not results,
   comments or forks — but `seon.sci.eval-test` was not baselined at HEAD
   either, so treat it as UNATTRIBUTED, likely inherited, and baseline it
   before blaming this lane. Note the shape: a row that is absent rather
   than wrong is the population invariant's own failure mode.
4. **The one end-to-end regression is not written.** `seon.repl-test` proves
   the grammar over emissions (9 tests, 18 assertions, green); the spec's
   proof — one reply evaluated once through `evaluate-sources`, asserting
   page bytes = stored history bytes = prompt history text — needs item 2
   first, because until then the prompt has different bytes by construction.
5. **No live page verification** on `http://127.0.0.1:7766/ns/my.agents.juniper/debug`
   and no screenshot. The dev cluster's data needs the reset the spec
   describes, and that reset is the orchestrator's.

## 5. Two platform findings this work uncovered

- **`bin/seon init --changed` refuses every publication**, for every file,
  including untouched ones: the operator JVM serving `data/clusters` started
  `Sep 5 18:19`, and the commit whose manifest rows it needs, `b0fdadd2e`,
  landed `Sep 6 21:35`. Filed as
  [a stale operator JVM refuses every changed publication](../../../seon/issues/stale-operator-jvm-refuses-every-changed-publication.md);
  it blocks the edit hook for every agent in this tree and it blames the
  innocent file being published.
- **`seon.cluster.reply-test` was already red before this lane touched it.**
  Baseline measured at commit `6a16fb60e` in an isolated worktree so the
  attribution is evidence rather than assertion; see §6.

## 5b. The reds this lane's rewiring leaves open — READ THIS FIRST

The runner's own confirmation pass reproduced **23** failures under
`bin/test seon.repl-test seon.cluster.run-test seon.render.transcript-test
seon.render-coverage-test seon.cluster.reply-test seon.bootstrap-test`.
**Three are inherited; twenty are this lane's.** That is more than a
coherent slice should leave, and it is the honest state of commit
`5ac5bfe34`.

**Inherited — red at HEAD `6a16fb60e`, measured in a detached worktree:**

- `seon.cluster.reply-test/every-refusal-is-a-value`
- `seon.cluster.reply-test/every-refusal-matches-its-declared-error-class`
- `seon.cluster.reply-test/forms-run-and-prose-becomes-source-comments`

**This lane's, asserting the OLD byte grammar** (prompt plus a bare result,
with no `#:seon.repl{…}` map, and the comment inside the source). By the
testing law's rule 6 these expectations are stale and the fix is the
expectation — but they were NOT updated, and until they are the gate is red:

- `seon.render.transcript-test/` — `a-tight-budget-degrades-then-elides-loudly`,
  `durable-history-entries-never-invent-executions`,
  `error-receipt-without-triage-has-an-execution-error-face`,
  `every-generated-history-is-ordered-total-and-token-bounded`,
  `history-unit-derives-both-projections-from-one-bounded-derivation`,
  `malformed-receipt-bytes-and-any-unique-about-stay-replayable`,
  `populated-history-restores-the-repl-fidelity-checklist`,
  `receipt-content-enters-the-shared-capped-floor`,
  `same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order`,
  `selected-evaluations-project-only-their-stored-source-and-result`,
  `stored-evaluations-are-terminal-transcript-values`,
  `supersession-chains-vanish-before-token-accounting`,
  `tight-budgets-pull-only-a-budget-derived-newest-candidate-set` (13)
- `seon.render-coverage-test/` —
  `effect-receipts-render-state-from-attribute-presence`,
  `important-runtime-entities-declare-and-use-readable-faces` (2): these
  assert which producers a family declares, and the evaluation family now
  declares `seon.repl/render-ai|html`.
- `seon.cluster.reply-test/` — `a-fenced-reply-retains-surrounding-prose-as-comments`,
  `attribution-follows-the-one-reader`,
  `crlf-events-stay-within-the-original-reply`,
  `the-source-is-exactly-what-the-agent-wrote`,
  `tilde-fences-have-the-same-presentation-semantics` (5): these ARE the
  expectations this lane rewrote, and they are still wrong. **Root cause
  found, and it is production code, not the expectations:** a reader
  event's `::source` span ALREADY OPENS at the first comment above the
  form, so `plan-sources` kept handing that span on as the form source
  while deriving `prose` from `(subs source cursor start)` — the comment
  therefore landed in the source and the comment fact came back empty. The
  form source must be `(subs source form-start end)`, where `form-start` is
  the offset of the form itself. A repair along exactly these lines was in
  the working tree, unattributed to this lane, when this lane ended
  (a private `form-start` deriving the offset from the form's own reader
  metadata); do not duplicate it — read `src/seon/cluster/reply.clj`
  first.

**Unattributed, and the one that matters:**

- `seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets`

`seon.cluster.run-test` was never baselined at HEAD in this lane, so whether
this is inherited or caused here is UNKNOWN. **Do not assume it is a stale
expectation.** Baseline `bin/test seon.cluster.run-test` at `6a16fb60e` in a
detached worktree before deciding. An unattributed red is a hypothesis.

### If you revert instead of finishing

Reverting `5ac5bfe34` is defensible — but `src/seon/cluster/loop.clj` is
touched by BOTH `5ac5bfe34` (comment threading, the `entry-source` caller)
and `741c1d6c3` (the run id into `fork-for-turn`), so a blind revert of the
commit drops the result rehydration too. Revert the files, keep loop.clj's
`:seon.cluster.run/id` line, and keep the landing note.

## 6. Gate tallies

Recorded so the next lane can separate its reds from the inherited ones.

| selection | tree | result |
|---|---|---|
| `seon.repl-test` | this lane | 9 tests / 18 assertions / **0 failures, 0 errors** |
| `seon.sci.eval-test` | this lane | 66 tests / 377 assertions / 6 failures, 0 errors — the rehydration regression GREEN; the two failures are unattributed program-row absences (§4.3) |
| the six-namespace selection | this lane | 74 tests / 623 assertions / 58 failures, 1 error — 24 distinct tests, 3 inherited, 21 this lane's (§5b) |
| `seon.cluster.reply-test seon.bootstrap-test` | HEAD `6a16fb60e`, isolated worktree | 20 tests / 181 assertions / **6 failures, 1 error** |
| `seon.cluster.reply-test seon.bootstrap-test` | this lane, before the expectations were updated | 20 tests / 182 assertions / 14 failures, 1 error |

### 6.1 The inherited reds

Measured at commit `6a16fb60e` in a detached worktree (`git worktree add`,
`reference-code` symlinked to the main tree's submodules), so the
attribution is evidence rather than assertion. Three tests were ALREADY RED
before this lane changed anything:

- `seon.cluster.reply-test/every-refusal-is-a-value`
- `seon.cluster.reply-test/every-refusal-matches-its-declared-error-class`
- `seon.cluster.reply-test/forms-run-and-prose-becomes-source-comments`

The additional reds this lane's change produced were the reader and
bootstrap tests asserting the OLD concatenated shape —
`the-source-is-exactly-what-the-agent-wrote`,
`a-fenced-reply-retains-surrounding-prose-as-comments`,
`tilde-fences-have-the-same-presentation-semantics`,
`attribution-follows-the-one-reader`,
`crlf-events-stay-within-the-original-reply`, and
`seon.bootstrap-test/intent-membership-is-the-only-opening-delta-and-is-budgeted`.
**Those expectations were stale, not the production change** (the testing
law's rule 6): each now asserts the two fields — the source is the form, the
comment is `:seon.cluster.eval/comment` beside it — rather than one glued
string. The three inherited reds above are untouched and remain open; they
belong to whoever owns the refusal-class work, not to this change.
