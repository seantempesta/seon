---
type: research
status: complete
date: 2026-09-07
tags: [research, storage, admission, print, render, error, effect]
---

# The storage-bound repair: five blockers, and the fixture that faked one

Written by the `storage-bound-repair` lane against
[the verifier's report](verify-storage-bound-2026-09-07.md) (read end to end),
[the storage-bound landing note](storage-bound-landing-2026-09-07.md),
[the agent record and turn loop PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§4 and §5, and the `data-oriented-clojure` and `repl` skills.

Owner rulings this lane executed: elision happens ONLY at the AI context
generation boundary and HTML is not limited; storage is bounded per value; a
missing value is marked, never silent; every check must say something when its
subject is absent; no hacks — root cause only.

## 1. Commits, one per defect

| commit | defect |
|---|---|
| `b6dd42ee0` | B4 — admission was not total in depth |
| `dd9757418` | B1 — faults were unbounded |
| `3b7e3ff8e` | B2 — the AI boundary had no elision at all |
| `955c5e26e` | B3 — an oversized capability request was dispatched with `nil` |
| `1f0561cc9` | B5 — a query-work truncation was reported as health |
| `fea6ebb6f` | two size spellings; an interrupted evaluation that reads as running |
| `595e8b76d` | the fixture behind the "foreign" arity break, and one filed issue |

## 2. B4 — the walk is ITERATIVE, not guarded

**Chosen: iteration, not a depth guard.** Every container `seon.sci.admit`
opens is now a frame on an explicit stack and its children are handed back to
the loop, so the JVM call stack is no longer the real depth bound. Depth is
bounded by the storage bound and by nothing else, which is the honest answer:
the walk already streams bytes, and bytes are the declared bound.

`semantic-value` walks the resulting print node the same way. That half is not
optional: a value admission ACCEPTS must be a value it can rebuild, and the
recursive derivation would have thrown an `Error` out of a total operation at
exactly the depths admission had just accepted.

Measured on this tree (`tmp/storage-repair/probe_admit.clj`, run under
`clojure -M:dev`):

| depth | before | after |
|---|---|---|
| 2000 | `StackOverflowError` out of `admit` under `:record` | admitted whole, 2000 levels in the node AND in the derived value |
| 5000 | `StackOverflowError` | admitted whole |
| 20000 | `StackOverflowError` | admitted whole |
| 100000 | `StackOverflowError` | `#:seon.eval{:missing :over-bound, :size 8388649}` |

Round-trip fidelity was re-proven on the same probe for every face the walk
emits — scalars, vectors, lists, sets, maps, records, throwables, insts, and a
lazy sequence whose realization throws: in every case the emitted EDN reads
back as exactly the returned print node, which is the property the streaming
serializer exists to have.

Regression: `seon.sci.admit-test/admission-is-total-in-depth-and-never-throws-a-stack-overflow`.
It measures depth with its own iterative helper, because `=` and the EDN
reader both recurse and a test that overflows while checking would report the
fix as the defect.

**`:seon.eval/size` is now bytes REACHED**, as `resources/seon/schemas/seon.eval.edn`
already declared it: everything emitted plus the fragment that crossed the
bound. The lane's own regression asserted the constant; it now asserts the
count, bounded above by twice the bound so the number stays a measurement
rather than a running total.

## 3. B1 — one mechanism, two bounds

A fault's evidence is a STORED value, so it goes through the same streaming
admission every stored value does, with `:seon.config.error/max-evidence-bytes`
in place of the storage bound. Over it, the field IS the missing marker —
`admit/missing-marker`, the one constructor `seon.repl/missing-text` also uses
— and the complete evidence stays in the fault's content blob beside it.

What that deletes: `evidence-profile`, `fitted-node`, `fitted-evidence-edn`,
and the token-budget halving search they drove. `seon.error` therefore no
longer needs "render-profile constants of its own" (§3.1 of the landing note):
the requirement dissolved rather than moving.

Two lies beside the bound are fixed in the same commit:

- `:seon.error/data-size` reported the SUBSTITUTE's size when the whole
  evidence went over the bound — a few dozen bytes, saying the evidence was
  small precisely when it was too large to keep. It reports the marker's own
  measured size now.
- `:seon.error/capped?` answered `false` when everything was dropped, because
  both sides of its `not=` were the same marker (F1). It is true whenever the
  full admission went missing, the inline admission went missing, or the two
  differ.

`:seon.error/inline-limit` is DELETED — it was the same number under a second
spelling, sourced from the blob threshold.

**The number is measured, not inherited.** 4,096 was the blob threshold's
value; the fact divides its inline budget among up to four payload fields, so
4,096 left ~1,700 per field. A real cold-acquisition fault
(`seon.sci.eval/unrestorable-function-root`, the source behind
`seon.sci.eval-test/one-unloadable-row-cannot-prevent-cold-acquisition`) admits
to **1,913 bytes** of evidence and lost its typed cause at that share. The
shipped bound is **16,384** — 4× the inline blob threshold — which keeps that
evidence inline. The class proof still runs at 4,096, passed explicitly.

Class proof:
`seon.error-test/fault-preparation-bounds-the-fact-and-omits-disposable-flow-state`
is GREEN — the fact was 915,655 UTF-8 bytes against its declared 4,096 and is
now under it, with each over-bound field reading
`#:seon.eval{:missing :over-bound, :size N}` and `capped?` true.
`capping-is-honest` and
`fitting-can-require-a-blob-below-the-content-size-threshold` are green with
their assertions unchanged.

## 4. B2 — `fit` is the AI boundary, and the only elision

`seon.print/fit` applies the render profile's string, child-count, depth and
token limits again (the body `cc667a426` replaced with the identity), and the
two output-aware callers fit ONLY `:seon.render/ai`:

- `seon.render/fit-terminal` emits the HTML projection whole;
- `seon.render.value/prepare` serves the whole stored value to the page while
  cutting the AI projection. Admission markers still become declared elision
  values in both projections — that is what the stored node already says.

Because HTML is never fitted, `fit-text`'s HTML branch and `html-preview` are
unreachable and are DELETED rather than kept as a second path HTML must never
take. `seon.print/admit-string` is deleted too (F5): admission stopped
clipping strings when the display caps left it, so the private text bounder now
has exactly one caller, `fit-text`, at the one boundary — and `seon.fn`'s
output census drops its admission half, which had been asserting a call path
that no longer exists.

Regression:
`seon.render.web-test/a-five-megabyte-value-is-elided-for-ai-and-complete-for-html`
renders one stored 5 MiB string through `seon.render/render-ai` and
`seon.render/render-html` on the same request: the AI projection is shorter
than the value, its cut is an elision naming `more characters` and carrying
`requery by [:seon.render.call/id …]`, and the HTML projection is at least as
long as the whole value. `seon.render.web-test/data-serves-a-five-megabyte-attribute-whole-with-its-handle`
stays green, so the `/data` route still serves the value whole on a real
socket.

`AGENTS.md` §2.4 is amended in the same commit: the "rendering limits are
disabled for design experimentation" sentence is replaced by the three bounds
a value has (storage per value; the AI projection under the render profile;
HTML unbounded), plus the rule that a query-work cut is reported as its own
elision naming the bound that made it.

## 5. B3 — the refusal is the admission's own answer

`src/seon/effect.clj` read `:seon.sci.admit/capped?`; the key was retired, the
read answered `nil` forever, and an oversized request was dispatched with a
`nil` request whose `:seon.effect/request-edn` was the string `"nil"`.

The bound is the declared storage bound the effect admission is already handed
(`:seon.config.eval.result/max-bytes` in its caps) — **no new dial**. A third
key holding the same number would be addition where the decision has not
actually split, and the refusal names the key and the size either way. An
admission answering `:seon.eval/missing` is now a flat `:seon.error` carrying
the bound, the reason, and the bytes reached; nothing is opened.

Regression: `seon.effect-test/an-oversized-request-is-refused-and-never-dispatched`
asserts the kind, the reason, the named bound, and that no effect identity was
recorded.

## 6. B5 — the pull's own cut is reported, profile or not

The presentation width still answers `Integer/MAX_VALUE` for a request with no
profile, and that is right: a request with no presentation decision must not
make one. What was wrong was reading that as "nothing was truncated" while the
PULL kept stopping at its own query-work limit — and over-fetching one value
precisely so the cut could be observed.

`acquisition-members` now receives both widths, shows the tighter of the two,
and `connection-observation` names the bound that made the cut
(`:seon.config.eval.result/max-collection` or
`:seon.render.profile/max-children`) in the elision's message and in
`:seon.print/bound-by`.

Regression:
`seon.render.walk-test/a-truncated-connection-is-reported-whichever-bound-cut-it`
— with NO profile and a narrowed query-work limit the observations fire and
name `max-collection`; with a tighter presentation width they name
`max-children`.

The five bare `(long (:some-cap caps))` reads the verifier listed
(`render/web.clj:593`, `render/transcript.clj:828/881/1072`,
`render/walk.clj:106/148/571`) are `seon.sci.admit/required-cap`, which
answers with the declared bound or refuses NAMING the absent key instead of
`RT.longCast` on nil. The seventh site, `print.cljc:974`, went with
`admit-string`.

## 7. The "foreign" arity break was a FIXTURE, and it is fixed

The verifier attributed
`seon.error-test/a-committed-fault-renders-its-evidence-without-renderer-failure-prose`
to `879967692` / `7ff102072` — `render-faults-html` declares `[faults database]`
while the walk invokes producers with one argument. **That attribution is
refuted.** Probed live (`tmp/storage-repair/probe_prep.clj`):

```text
:environment-connection? false
:prepared-count 205
:has-faults? true
:plan [{… :seon.call-preparation/slots
        [{:seon.fn.argument/index 1
          :seon.call-preparation/key :seon.db/db
          :seon.call-preparation/supplier-symbol seon.db/supplied-database-value}]}]
```

The program graph has the right plan and the right supplier. What was absent
was the environment: `seon.test-support/fork-cluster-ctx` built its projection
state with `seon.sci.eval/projection-state`, whose environment carries no
connection, so `seon.call-preparation/hook` found no database and returned its
arguments untouched — **every supplied default was inert in every test using
that fixture**. The producers' contracts were right; the fixture omitted a
declared input (§5.1, and law 2.1: hand the environment explicitly, exactly
like production).

The fixture now replaces that environment with a production-shaped one, and
the cluster name is DERIVED from the database — a fixture that seeded exactly
one cluster gets it, one that seeded none carries no environment as before, so
the blast radius is only tests that already stood up a cluster.

Both producers render, and `a-committed-fault-renders-its-evidence-without-renderer-failure-prose`
is GREEN with no production edit.

## 8. Also landed

- **Two size spellings become one.** `:seon.cluster.eval/result-size` was
  written only by the two error settlement paths in `seon.cluster.loop` and
  read by nobody (a pull selector in `seon.cluster.curate` asked for it and
  used it for nothing). Deleted from source, schemas, and the two tests that
  named it.
- **`interrupted-at` renders**, through `seon.repl` only:
  `:seon.repl/interrupted` is declared, sits in `response-order` after
  `:error`, and carries the instant. Before this an evaluation boot cut read in
  the agent's own history exactly like one still running — absence of signal as
  health, in the place the agent reasons from.
- **`seon.repl/missing-text` builds its marker through `admit/missing-marker`**,
  so the REPL response, a fault's evidence, and the effect refusal all report
  an absent value with the same data.
- **Filed, not fixed:**
  [a blocking realization is not bounded by the interrupt](../../../seon/issues/a-blocking-realization-is-not-bounded-by-the-interrupt.md)
  — the exact seam is a blocking call inside `lazy-seq` realization, entered
  from `frame-advance` while it asks the source for its next child; measured
  5005 ms against a 1000 ms deadline. Mitigated today only by a SCI binding
  table property that nothing asserts.

## 9. Gates

### 9.1 The assigned selection

`bin/test seon.sci.admit-test seon.sci.eval-test seon.error-test
seon.effect-test seon.print-test seon.repl-test seon.render.transcript-test
seon.render.walk-test seon.render.web-test seon.cluster.run-test`

**BASELINE, measured on this lane's own evidence at `90792d075`**
(`tmp/storage-repair/baseline.log`): **20 red**, 260 tests, 1555 assertions,
39 failures, 1 error.

**AFTER: 16 red, every one of them inherited, no new red** — 262 tests, 1571
assertions, 29 failures, 1 error (`tmp/storage-repair/final.log`). The four
`seon.error-test` reds the verifier could not attribute to the inherited set
are all GREEN.

The sixteen that remain, name for name against the baseline:

```
seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets
seon.render.transcript-test/a-tight-budget-degrades-then-elides-loudly
seon.render.transcript-test/every-generated-history-is-ordered-total-and-token-bounded
seon.render.transcript-test/malformed-receipt-bytes-and-any-unique-about-stay-replayable
seon.render.transcript-test/populated-history-restores-the-repl-fidelity-checklist
seon.render.transcript-test/receipt-content-enters-the-shared-capped-floor
seon.render.transcript-test/same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order
seon.render.transcript-test/supersession-chains-vanish-before-token-accounting
seon.render.transcript-test/tight-budgets-pull-only-a-budget-derived-newest-candidate-set
seon.render.walk-test/one-basis-projection-covers-the-complete-walk
seon.render.web-test/a-fresh-cluster-debug-page-renders-a-prospective-prompt
seon.render.web-test/a-never-run-agents-debug-context-is-labeled-prospective
seon.render.web-test/an-unavailable-prospective-context-renders-its-diagnostic-data
seon.render.web-test/the-message-appears-on-the-page-wire-test
seon.sci.eval-test/runtime-function-rows-carry-parsed-contract-facts
seon.sci.eval-test/static-and-runtime-contracted-definitions-publish-identical-facts
```

`seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`
— the byte-identity proof — stays GREEN.

**Restoring `seon.print/fit` did NOT turn the eight transcript reds green.**
They were described as the disabled-rendering-limits set; they survive the
limits coming back, so whatever they are, it is not only that. Naming it is
the next lane's, not this one's.

### 9.2 The platform tier

`bin/test --platform`: **GREEN — 73 platform tests, 395 assertions, 0
failures, 0 errors**, and the runner removed its isolated operator root as
successful.
