---
type: research
status: complete
tags: [research, runtime, agent]
---

# Default-cluster live drive — 2026-08-08

## Verdict

The arc got further than 2026-08-06 and failed differently, at a new place.

Two of the six 08-06 blockers are genuinely fixed, three are still present (one
of them recurred after being archived as resolved), and one is transformed. The
mechanism that closed 08-06's worst blocker — rendering a turn at its run's
opening basis, `seon.cluster.run/opening-db` — introduced a NEW blocker class
of its own, because an as-of database value is not a total input to the code
that reads it. Two instances of that one class were found. I fixed the first
(one line, `src/seon/context.clj`) under the drive's small-fix exception, which
unblocked the turn and let the drive reach a real settled run for the first
time on this cluster. The second instance needs a design decision and is filed.

The honest headline: **root's agent context is now 127 tokens of error text.**
Not 44,306 tokens of noise as on 08-06 — 127 tokens containing nothing but the
message that the walk failed. DeepSeek-flash received exactly that, spent 9,840
reasoning tokens rationally debugging it, and never saw the human message it
was supposed to answer. Neither of the two messages the drive submitted ever
triggered a run at all.

The planning+memory extension was NOT attempted. Running it against an empty
context would have measured nothing.

## Scope and method

- cluster `default`; pid `79576`, start instant `2026-08-08T04:30:56Z`;
- prepl `127.0.0.1:54233`; web `http://127.0.0.1:7994`;
- source head `98411daeb` at start; baseline basis `536870992`;
- model `deepseek-v4-flash` (shipped default), owner-pre-authorized.

I read [live-drive-2026-08-06.md](live-drive-2026-08-06.md) and the "Decisions
needing you" and "Landed overnight" sections of
[overnight-report-2026-08-08.md](../plan/overnight-report-2026-08-08.md) end to
end before touching the cluster, as instructed.

Probes were `mcp__seon__eval_clj` against cluster `default` plus the real HTTP
server. The one mutation to production source is described and evidenced below.
The cluster was never reset, reforked, or stopped.

## Arc timeline

| Time (UTC) | Event |
|---|---|
| 04:30:56 | cluster boots (ready in 5,976 ms, 1 agent, 0 recovered runs) |
| 04:31:05–10 | `bootstrap:root` run: 13 forms, 5 of them error |
| 04:31:13–14 | three maintenance failures commit; run `a7e24a23` opens on the compact-error message |
| 04:31:14 | root's `seon.cluster.agent/turn` `:step` DIES — `seon.db/database-value-identity` contract violation |
| 04:35:02 | drive submits `LIVE-DRIVE-0808-A` → HTTP 204 in 92 ms, `inbound-536870994-0` committed |
| 04:35–04:37 | nothing. No run, no capture, no attempt, no receipt, and NO NEW ERROR — the turn is simply dead |
| 04:37 | root cause reproduced in the REPL; one-line fix applied to `src/seon/context.clj`; `seon.context` hot-reloaded into the live JVM; `capture-tx` verified against an as-of value |
| 04:38:02 | drive submits `LIVE-DRIVE-0808-B` → HTTP 204 in 105 ms, `inbound-536870997-0` committed |
| 04:38:2x | **first context capture ever on this cluster** — `a7e24a23-…-context-536870998`, 509 chars |
| 04:38–04:39 | real DeepSeek SSE call in flight (confirmed by virtual-thread dump inside `seon.ai/streamed-completion`) |
| 04:39:47 | attempt records; 13 forms evaluated; run `a7e24a23` CLOSES. Run `cf7cc2f1` opens and closes in the same second on another maintenance-error message |
| 04:41:32 | run `20768b1f` opens on `db9b5b2a-…-your-run` — the error message root's OWN failure produced |

Both human messages are still unclaimed at the final read.

## The one production change

`src/seon/context.clj:167`

```clojure
;; before
basis-t (long (:t (db/database-value-identity db)))
;; after
basis-t (long (db/basis-t db))
```

`database-value-identity`'s output contract requires a `:datahike/commit-id`
uuid. `datahike.db.AsOfDB` has none, so the contract threw into the turn proc
and killed it before the first capture. `seon.db/basis-t` is declared 13 lines
below in the same namespace, is total over every value shape, and produces a
byte-identical `:t` (both call `dbi/-max-tx`). Commit `419a5e529` reached for
the wrong sibling.

The file was clean and held by no lane. Full write-up and live proof:
[capture-basis-read-through-the-identity-reader-kills-the-turn](../../../seon/issues/archive/capture-basis-read-through-the-identity-reader-kills-the-turn.md).
A class regression over all four database value shapes is OWED and was not
written here.

## The exact agent context — verbatim, in full

This is the complete `:seon.context.capture/prompt` for capture
`a7e24a23-14b7-41ab-8a96-5f3c06a9a8ee-context-536870998`. Not an excerpt.

```text
;; (seon.render/walk) => error
Walk failed: seon.db/read-evidence violated its contract (invalid-output): [#:datahike.read{:revision {:datahike.cache/connection-id [{:value nil, :message "missing required key"}], :datahike.cache/generation [{:value nil, :message "missing required key"}], :datahike.read/attributes [{:value #, :message "should be :all"}], :datahike.read/revision [… 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]}}]
```

One contribution, named `walk`, 127 tokens. Root cause reproduced with a clean
two-sided falsifier — `read-evidence` succeeds on a current database value and
throws on an as-of one, because `dependency-revision` reads `:cache-context` as
a MAP KEY and `AsOfDB` does not carry it. Filed as
[Give an as-of database value a dependency revision](../../../seon/issues/walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md).

Sent that, DeepSeek did the only sensible thing: it debugged the error. Its
thirteen planned forms are almost entirely `;`-prose about `read-evidence`,
plus `(require '[clojure.pprint :refer [pprint]])`, `(pprint (seon.db/read-evidence db))`,
and `(seon.render/walk profile db)`. Instruction fidelity to the drive message
was zero — the requested value `{:live-drive/phase :opened :live-drive/agent "root"}`
never appears, because the message was never in the prompt.

Six of the thirteen evaluations errored as flat values, correctly and without
crashing the loop: `Unable to resolve symbol: ...`, `db`, `profile`, `pprint`,
and `Could not find namespace clojure.pprint.`

## Token measurements

Every value below is a real measurement, not an estimate of an estimate:
provider usage from `:seon.ai.attempt/usage-edn`, local sizes from
`seon.ai.tokens/estimate`.

| Measure | 2026-08-06 | 2026-08-08 |
|---|---:|---:|
| Prompt characters | 135,272 | **509** |
| Prompt tokens (local estimate) | 44,306 | **127** |
| Prompt tokens (provider) | 44,306 | **225** |
| Completion tokens | 7,329 | **10,502** |
| of which reasoning | 7,179 | **9,840** |
| Reasoning blob characters | — | 36,511 |
| Total tokens | 51,635 | 10,727 |
| Prompt cache hit | 17,792 | **0** |
| Contributions in the capture | many | **1** |
| Finish reason | stop | stop |
| HTTP 402 / retry | none | none |

**Token sentinel verdict: an INVERSE explosion, filed as high priority.** The
completion is 47× the prompt and 9,840 tokens of reasoning bought nothing. The
producing render is named: `seon.render/walk`, failing whole through
`seon.db/read-evidence`. The 08-06 explosion (44k of schema and config dumps)
cannot be judged fixed from this run — it was not reduced, it was replaced by
total context loss, and the real size will only be known once the walk renders
again.

Separately, the largest single value in the database is still a serialized
print tree: error `db9b5b2a`'s `:seon.error/data-edn` is **4,249,999
characters** of nested `#:seon.print` nodes (4,010,918 on 08-06 — it grew).
Existing owner:
[Keep contract-violation evidence as data](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).

## Before/after on the six 08-06 blockers

| # | 2026-08-06 blocker | 2026-08-08 verdict | Evidence |
|---|---|---|---|
| 1 | A successful plan freezes before receipt zero and blocks the root queue | **TRANSFORMED** — the freeze is gone; the turn now runs to 13 receipts and a closed run. But the turn was DEAD for a different reason (contract violation at `:step`) until this drive fixed it, and the wedge was worse: no run, no capture, no error | run `a7e24a23` closed 04:39:47 with 13 `:seon.cluster.eval` rows, after the `context.clj` fix; before it, zero of everything |
| 2 | An unclaimed human message enters an unrelated run's prompt | **STILL PRESENT (recurred after archiving)** — the first half is fixed (no post-opening message leaks in), the second half is not: a human message is never selected at all while error messages are queued. Both drive messages remain triggerless; root works its own error backlog, which feeds itself | all four runs triggered by `maintenance-error/…-your-run`, `db9b5b2a-…-your-run`, or nothing; issue reopened with the table |
| 3 | Fresh scheduled maintenance cannot settle (`:seon.operator.log/path` not installed) | **TRANSFORMED** — the missing-attribute refusal is gone; maintenance now RUNS and refuses for three real external-claim reasons | `:seon.operator/collection-incomplete`, `/process-census-incomplete`, `/reap-incomplete` at 04:31:13–14 |
| 4 | The problems projection breaks both health and root rendering | **FIXED** | `runtime_status` returned health with `:seon.problems/error-signatures 6`, `:seon.problems/errored-receipts 5`; root page renders |
| 5 | Transcript rendering passes a set to `pull-many` | **FIXED (render-side evidence)** | zero occurrences of `pull-many` and zero of `Renderer unavailable` in the 641 KB root page, against fourteen on 08-06 |
| 6 | `/data` is a deterministic 500 | **FIXED** | HTTP 200, 3,168 bytes, 5.88 s |

Bonus, from the same 08-06 list: the debug page (friction #7) is **FIXED** —
HTTP 200 in 24 ms against no first byte in five seconds — and its left pane is
now the exact captured prompt bytes, which also settles 08-06's dishonesty #11.

## Web surfaces

| Route | Status | Bytes | Time | Note |
|---|---|---:|---:|---|
| `/` | 200 | 608,558 | 2.99 s | |
| `/agent/root` | 200 | 641,432 | 2.53 s | byte-identical to `/ns/my.agents.root` |
| `/ns/my.agents.root` | 200 | 641,432 | 0.015 s | alias routing calibrated working |
| `/ns/my.agents.root/debug` | 200 | 2,581 | 0.024 s | was no first byte in 5 s |
| `/data` | 200 | 3,168 | 5.88 s | was 500 |

Root page census: 242 `<article>`, 70 `<pre>`, 141 raw `:db/id` strings, 25
`violated its contract` occurrences, 0 `pull-many`, 0 `Renderer unavailable`.
The page grew from ~519 KB to 641 KB. No browser was driven; typography,
overflow, and console state remain unproven.

## New defects filed

1. [Give an as-of database value a dependency revision](../../../seon/issues/walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md)
   — blocker. The whole agent context collapses to one error. Two-sided
   falsifier included.
2. [Keep an unclaimed message out of an unrelated run's prompt](../../../seon/issues/unclaimed-message-enters-an-unrelated-run-prompt.md)
   — REOPENED from archive with the recurrence table and added acceptance.
3. [Substitute the bootstrap plan's namespace placeholder before it is evaluated](../../../seon/issues/bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders.md)
   — blocker. `(in-ns '{{seon.ns/name}})` and `"{{seon.ns/name}}/largest"`
   shipped raw into `bootstrap:root`, producing five boot-time errors.
4. [Read the capture basis with the total reader, not the identity reader](../../../seon/issues/archive/capture-basis-read-through-the-identity-reader-kills-the-turn.md)
   — resolved in this drive; class regression owed.

Closed with this drive's evidence:
[the data page](../../../seon/issues/archive/data-page-omits-the-live-sci-context.md)
and [the debug page](../../../seon/issues/archive/debug-page-blocks-before-first-byte.md).

## Ugly output — reported verbatim per the dogfood rule

**A hole where a run id belongs.** Every maintenance-error message reads:

```text
Collection did not preserve and verify every recorded root.
(:seon.operator/collection-incomplete). It interrupted run . Inspect error
maintenance-error/maintenance-receipt/["root/maintenance/compact" #inst
"2026-08-02T03:00:00.000-00:00"]; nothing was retried. Signature:
d1fdaabcf31f099e7c0965dd7552f1adb983203c797c6048b573260ec97ca508.
```

`It interrupted run .` — the sentence is built as if a run id will be there and
nothing checks that it is. And the "error to inspect" is a 96-character
composite lookup key with an inline `#inst`, which is an identity, not
something a reader can act on. Three of these are the first thing a fresh root
agent sees.

**A stack trace where an error value belongs.** An admission projection failure
in the MCP envelope returns a raw ~60-frame `:via`/`:trace` string instead of a
flat `:seon.error` value:

```text
{:via [{:type clojure.lang.ExceptionInfo, :message "value admission could not
project a clojure.lang.PersistentArrayMap", ...}], :trace [[clojure.lang.
APersistentMap$KeySeq first "APersistentMap.java" 171] ... 60 more frames ...],
:phase :print-eval-result}
```

**Declaration-population noise on ordinary reads.** A plain `pull` emits
stderr lines into the envelope:

```text
seon.schema: DECLARATION POPULATION FALLBACK ×10 — seon.print (print.cljc:779)
seon.schema: DECLARATION POPULATION FALLBACK ×100 — seon.cluster (cluster.clj:294)
```

Counts of 10 and 100 on a single read, after the overnight admission blocker
was closed. Worth a look by that lane.

**Elision eats the answer.** When an `eval_clj` result exceeds the profile,
nested values are replaced by `"seon.sci.admit/elided"` rather than truncated,
so a query returning four fields can come back with all four elided and the
useful part gone. Re-querying smaller works, but the first result is pure cost.

**`eval_clj` has a hard 30 s timeout** with no dial, which makes waiting on a
90-second provider turn impossible from the tool that is meant to observe it.
Sleep-and-poll has to be chopped into sub-30 s pieces.

## What worked well

- The public message boundary is solid: HTTP 204 in 92 ms and 105 ms, both
  messages admitted, ordinal-stamped, and immediately queryable.
- Errors are values where they should be: six agent mistakes became durable
  `:seon.sci.eval/evaluation-failed` facts and the loop never crashed.
- The database made every diagnosis possible without touching process memory —
  triggers, runs, forms, evals, attempts, captures, errors, and their basis
  transactions were all plain queries.
- Hot reload did exactly what the architecture claims: re-evaluating one `defn`
  against the running system changed proc behavior immediately, with no restart
  and no lost state.
- The virtual-thread-aware dump found the live SSE read in
  `seon.ai/streamed-completion` in one call and prevented a wrong "the turn is
  hung" attribution.
- Route aliasing is byte-identical, and `/ns/{namespace}` served the same
  641,432 bytes in 15 ms that `/agent/root` took 2.5 s to produce.
- Provider integration is clean: one attempt, correct usage accounting,
  `stop`, no 402, no retry.

## Arc coverage

| Requested probe | Outcome |
|---|---|
| Observe maintenance settle | done — it does not settle; three honest external-claim refusals |
| One human message to root | done twice; both committed, neither claimed |
| Root claims the message, opens a run | NOT REACHED — runs open on error messages only |
| Plan and execute through receipts | reached, but on the wrong trigger and with an empty context: 13 receipts, 6 errors |
| Settled reply | NOT REACHED — run closed with no reply to the human message |
| Six blockers, fixed/present/transformed | done, table above |
| Token sentinels | done; inverse explosion filed |
| Planning + memory extension | NOT ATTEMPTED — meaningless against an empty context |

## Dispatch recommendation

One lane, one seam. The walk's as-of refusal is the only thing between this
cluster and a real drive; it is the sibling of the defect already fixed here,
in the same file, in the same class. Fix it, then the message-selection
starvation, then re-run this arc — at that point the 08-06 token explosion
becomes measurable again for the first time since the context collapsed.
