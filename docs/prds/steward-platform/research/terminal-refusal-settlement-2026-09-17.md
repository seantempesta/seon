---
type: research
status: active
tags: [research, turn, settlement, wave/settlement]
created: 2026-09-17
---

# The terminal refusal settlement repeated what the refusal denied

Measured on the freshly reset `default` cluster (pid `30138`, process
`30138-1789572734465`, store 110 MB, Juniper fixture seeded 2026-09-16
15:32:40Z). Two fault families were assigned; both were read from the durable
fault facts, and one of the two stated hypotheses is refuted below.

## Fault A — what the 7810-byte refusal actually was

The occurrence (`:seon.error/id`
`1812cde1cc5ad17b0b5321d1875512f42a1c77f594616db1ee33496e4d18938c`, entity
47606, occurrence `873a4d028ea5`, 2026-09-16T15:33:01Z) carries inline
`:seon.error/data-edn`

```clojure
#:seon.print{:face :seon.print/map
             :entries [[… :seon.sci.admit/reason] [… :over-bound]
                       [… :seon.sci.admit/bytes] [… 7810]]}
```

with `:seon.error/capped? true`, `:seon.error/data-size 35090`, and the full
evidence in blob digest
`78006f5a7e683c35a53483469b5272c25b9015e9d62c8c4505dbe164d8b1f72e`
(35090 bytes, recovered with `seon.blob/get`).

**The `:over-bound` marker is not the defect.** It is
`seon.error/bounded-admission` (`src/seon/error.clj:390-407`) doing exactly
what it is declared to do: the fault's INLINE evidence is admitted under a
per-fault byte cap, an over-bound admission is replaced by its marker
re-admitted unbounded, and the whole 35090-byte evidence is written to the
blob tier. Nothing was lost and no shown text bypassed the value renderer.
The assignment's hypothesis — that a terminal refusal's shown text reaches
admission unbounded because the settle path skips the render profile — is
**refuted**: `refusal-terminal-data` does call `pr-str` on the flat error
value for `:seon.eval/shown`, but that string never reached an admission
bound in this incident and is not what refused.

The blob says what actually happened:

```clojure
:clojure.core.async.flow/op   :step
:clojure.core.async.flow/pid  :seon.agent/turn
:seon.agent/id                "juniper"
:clojure.core.async.flow/msg  :seon.agent/wake
:cause "Terminal refusal settlement was refused."
:data {:seon.error/kind :seon.turn.loop/terminal-refusal-settlement-refused
       :seon.turn.loop/terminal-refusal-settlement-refused :seon.turn/no-such-run
       :seon.turn.loop/settlement      {:seon.error/kind :seon.turn/refused
                                        :seon.turn/transition seon.turn/close-call
                                        :seon.turn/rule :seon.turn/no-such-run
                                        :seon.turn/request {:seon.turn/id "3f81dfc4c014" …}}
       :seon.turn.loop/refused-outcome {… the byte-identical first refusal …}}
```

Frames: `seon.turn/settle!` `turn.clj:3746` ← `seon.turn/turn` `turn.clj:4938`
← `seon.turn/step` `turn.clj:5270`, on the agent's flow proc.

## The chain, from the datoms

| `:t` | instant | what |
|---|---|---|
| 536870999 | 15:33:00Z | juniper turn `3f81dfc4c014` OPENED (`situation :call`, 8 datoms) |
| 536871000 | 15:33:01Z | **933 retractions, 1 assertion (`:db/txInstant`)** — all four juniper turns (`f4c23ce4f31a`, `3f81dfc4c014`, `9fc9bc9ef8ad`, `404bfad994bc`), their 20 evaluations, 18 read-evidence and 135 read-request entities |
| 536871001 | 15:33:01Z | 170 datoms |
| 536871002 | 15:33:01Z | the terminal-refusal-settlement fault |

The turn entity the loop was settling was **retracted whole one transaction
after it opened**. The writer log agrees: `data/clusters/default/logs/seon.log`
shows `:datahike/write-rejected … "run transition refused: no-such-run"` at
15:33:01.267, .468 and .521, then the fault at .527.

The retractor is `seon.context-blocks-fixture/clear-history!`
(`test/seon/context_blocks_fixture.clj:288-301`), called from
`install-running!` through
`docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj`. It
retracts every juniper turn and evaluation at the writer:

```clojure
(mapv #(vector :db.fn/retractEntity %) (concat evaluations turns))
```

Its docstring claims it runs "after the fixture agent and earlier arm wakes
are idle", but it establishes no idleness — it disarms and then wipes, while
`install-running!` deliberately leaves the ordinary graph running. Retracting
a turn also retracts its incoming `:seon.runtime/turns` component datom
(`reference-code/datahike/src/datahike/db/transaction.cljc:997-1012`), which
is why the runtime's turn set went with it. Filed separately as an issue; not
fixed here, because it is a shared fixture and the loop must be total against
a vanished turn in any case — PRD §14 compaction legitimately retracts the
agent's record.

## Root cause, with file:line

`seon.turn/settle!` (`src/seon/turn.clj:3746` at the incident, the throw) and
`seon.turn/settle-batch-refusal!` (`:3682`) handle a refused terminal commit
by building `refusal-terminal-data` (`:3620`) and committing it. That data
unconditionally re-issued

- `receipt-settle-tx` — whose `receipt-settle-call` calls `receipt-run`
  (`src/seon/turn.clj:829-836`) and refuses `::no-such-run` / `::run-closed`;
- `close-tx` — whose `close-call` (`:396`) calls `require-open-run`
  (`:296-303`) and refuses the same two rules.

So when the FIRST refusal's rule was `:seon.turn/no-such-run`, the refusal
settlement contained the byte-identical close that had just refused, refused
identically, and `settle!` threw — a core fault about the RECORDING replacing
the turn's real outcome, on the agent's flow proc. This is the project's
recurring class: **a seam acting on a pre-read the authority will re-decide**
(AGENTS.md owner law 2026-08-29), and §2.4's totality requirement on the
terminal writer.

## The fix

`seon.turn/open-run-tx-call` (new, `src/seon/turn.clj:417`) is the writer's
own decision: given the run id and the run-dependent transaction data, it
emits that data only while `current-run` finds the run and `open?` holds,
and emits `[]` otherwise. Both refusal arms now wrap their settlement and
close in `[:db.fn/call #'open-run-tx-call run-id …]`; the error recording,
which depends on no run, is outside the guard and always commits.

No bound was raised, no truncation call was added, and no clipping spot was
introduced — §2.4's one-clipping-spot rule is untouched, because presentation
was never involved. The ordinary `close-tx` / `receipt-settle-tx` paths keep
their strict refusals and the one-open-turn fence is unchanged.

## Fault B — `agent-already-running` is not a fault

Two `:seon.turn/refused` facts, one for juniper (turn `404bfad994bc`) and one
for root (`abbc66b94499`), both `:seon.turn/transition seon.turn/open-call`,
`:seon.turn/rule :seon.turn/agent-already-running`, message
`"run transition refused: agent-already-running"`. Neither occurrence carries
`:seon.error/op`, `:seon.error/proc` or `:seon.instrument/fn`, and the
signature row has no `:seon.error/fn` or `:seon.error/frame` — they were not
committed by the fault committer from flow's error-chan. They were committed
by `seon.turn/open-turn` (`src/seon/turn.clj:4143-4153` before the change),
which routed EVERY refused open outcome into `settle!`, and therefore into
`error/recording`.

The turn PRD (`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`)
rules this ordinary flow, read end to end for §3 and §14:

- §3: "A wake asserted mid-turn has `:t > basis` and opens the next turn. No
  reference from turn to wake, no claim, no per-wake write."
- §14: answeredness is derived from the opening transaction's `:t`; nothing
  is claimed and nothing is lost when a wake meets an open turn.

`open-turn`'s own comment says the same: "`open-call`'s
`agent-already-running` is what stops two openers, and the derivation is what
stops a second turn for an answered wake." The fence firing IS the design.
Committing it as a durable core fact made ordinary loop flow a defect report,
and because `:seon.error/steward` is a listened attribute it also woke the
steward with it.

Changed: `open-turn` now reports `:released` for
`:seon.turn/agent-already-running` and settles every other refusal exactly as
before.

## Verification boundary

### In-process results (live `default`, pid 30138, 2026-09-16 15:48-15:51Z)

| test | result |
|---|---|
| `seon.turn-test/open-run-tx-call-emits-only-while-its-run-is-open` (new) | 4 pass, 0 fail, 0 error |
| `seon.turn-test/a-terminal-refusal-settles-when-its-run-has-vanished` (new) | 5 pass, 0 fail, 0 error |
| `seon.turn-test/a-wake-meeting-an-open-turn-releases-without-a-fault` (new) | 3 pass, 0 fail, 0 error |
| `seon.turn-test/a-refused-generated-form-records-its-refusal` (landed 3596cfb96, same family) | 5 pass, 0 fail, 0 error |
| `seon.turn-test/compaction-refuses-an-open-turn-at-the-writer` | 3 pass, 0 fail, 0 error |

Each run used the three-argument `seon.test/run` with
`:seon.test/remaining-ms 180000`: the two-argument arity's 20 s backstop is
consumed by the post-adoption base construction and reports a bound failure
rather than a result.

The counterfactual is the incident itself: the pre-change code produced
`:seon.turn.loop/terminal-refusal-settlement-refused` on `default` from
exactly the rule (`:seon.turn/no-such-run`) and the turn (`3f81dfc4c014`)
these regressions construct. The Fault B run reached `open-call`'s real
refusal (`:datahike/write-rejected … "agent-already-running"` on the writer
log during the run), so it exercises the changed branch and not the
system-turn path.

In-process on live `default` (pid 30138) only, through
`(seon.test/run (#'seon.test/resolve-test 'seon.turn-test/<test>) (seon.operator/connection "default"))`
after reloading `seon.turn-test` through `#'seon.test/with-test-loader`. No
test JVM was launched; `default` was never stopped or restarted. The
orchestrator's batched cold gate over `seon.turn-test` and
`seon.turn-loop-test` is the final proof and is requested in
`tmp/orchestrator/gate-requests/terminal-refusal.txt`.
