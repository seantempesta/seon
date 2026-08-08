---
type: research
status: complete
tags: [research, runtime, agent]
---

# Observer lane — default-cluster live drive, 2026-08-08

Independent observation of the 08-06 arc rerun on cluster `default`. This lane
took no stimulus action: it did not submit a message, transact, stop, reset, or
refork anything. Every fact below came from read-only database queries, the
live HTTP server, the cluster log file, and probes against the running JVM.

I read [live-drive-2026-08-06.md](live-drive-2026-08-06.md) and
[overnight-report-2026-08-08.md](../plan/overnight-report-2026-08-08.md) end to
end before starting.

## Verdict

**The drive cannot test the 08-06 arc, because the agent has no context at
all.** Every prompt sent to DeepSeek on this cluster is the same 509-character
string, and its entire content is one renderer contract error. Ten context
captures, ten identical sizes, zero variance:

```clojure
:capture-char-sizes  [509 509 509 509 509 509 509 509 509 509]
:capture-token-sizes [127 127 127 127 127 127 127 127 127 127]
```

The agent has never seen its instructions, its namespace, its REPL, or the
driver's message. The model's behaviour across the whole drive was a rational
response to the only thing it was shown, and no conclusion about agent
capability can be drawn from this run.

**Two of the driver's headline claims are independently true**, reproduced here
without reference to its analysis: the as-of dependency-revision defect and the
bootstrap placeholder defect. I confirm both and extend the first.

**The most important thing the 08-06 report did not predict is that the failure
loops.** Each failed turn commits a fault message addressed to the same agent;
that message wakes the next turn; the next turn gets the same broken prompt.
In twenty minutes the cluster made nine paid provider calls and generated
66,591 completion tokens from 2,025 prompt tokens.

**Custody, by contrast, is clean and is a genuine improvement over 08-06.**
Across eleven runs there was always exactly one open run, always exactly one
holder, no orphan, no double claim, and no interrupted receipt.

## Scope and method

- cluster `default`, pid `79576`, start instant `2026-08-08T04:30:56Z`;
- prepl `127.0.0.1:54233`, web `http://127.0.0.1:7994`;
- opening commit `6a76b0c6-9b39-59a7-8e94-bce52db195f3`;
- observation window `04:31Z` (boot) to `04:50:42Z`, basis 536870992 → 536871109.

A sampler thread (`tmp/observer-0808-sample.clj`) appended 84 read-only census
lines to `tmp/observer-0808-timeline.edn` every ten seconds. It derefs the
connection, pulls, and appends to a file; it never transacts. Raw dumps also
committed alongside it: `observer-0808-boot-errors.edn`,
`observer-0808-runs.edn`, `observer-0808-forms-evals.edn`,
`observer-0808-turn1.edn`, `observer-0808-exact-prompt.txt`.

One correction to my own method, recorded because it nearly produced a false
finding: I first counted receipts with `:seon.cluster.receipt/*`, which does
not exist, and reported zero. Evaluation receipts are `:seon.cluster.eval/*`.
A `q` over an uninstalled attribute returns empty rather than failing, so the
wrong attribute name is indistinguishable from real absence. Separately, my
first census called `(seon.db/db)` with no connection bound; it returned a flat
error value, and `count` over that error map returned 3 for every entity class,
which looks exactly like a plausible census. Both readings were wrong and both
looked fine.

## The exact prompt — all ten captures, verbatim

```text
;; (seon.render/walk) => error
Walk failed: seon.db/read-evidence violated its contract (invalid-output): [#:datahike.read{:revision {:datahike.cache/connection-id [{:value nil, :message "missing required key"}], :datahike.cache/generation [{:value nil, :message "missing required key"}], :datahike.read/attributes [{:value #, :message "should be :all"}], :datahike.read/revision [… 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]}}]
```

That is the complete user-role content. 509 characters, 127 estimated tokens.

Two faces inside it are themselves defects. The value that failed is
attribute-set shaped, so it belongs to the second branch of the
`:seon.db/dependency-revision` `[:or]`, but the reported complaint is the first
branch's `"should be :all"` — and the model acted on exactly that wrong hint,
proposing `:datahike.read/attributes :all` as the fix. The set also renders as a
bare `#` (`{:value #, :message "should be :all"}`), which is not a legible face
for a set.

### Root cause, reproduced independently

`seon.db/dependency-revision` (`src/seon/db.clj:256-276`) reads
`(:cache-context database)` as a map key. A current `datahike.db.DB` carries it;
derived values do not.

```clojure
{:label :current, :evidence-ok? true,
 :revision [[:datahike.cache/attribute-revisions :datahike.cache/connection-id
             :datahike.cache/generation :datahike.read/attributes]]}
{:label :as-of,   :evidence-ok? false, :cc nil, :err "CONTRACT: …invalid-output…"}
{:label :history, :evidence-ok? false, :cc nil, :err "CONTRACT: …invalid-output…"}
```

Two additions to the driver's note. **`history` fails identically, not only
`as-of`** — so the four-shape acceptance criterion is confirmed necessary, not
speculative. And **the loss is silent at the point it happens**: the identity is
built with `select-keys`, which returns `{}` over a value with no
`:cache-context` rather than failing, so the two required keys disappear without
a word and the first complaint arrives frames later at the output arm.

Both are appended to
[Give an as-of database value a dependency revision](../../../seon/issues/walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md).

## Timeline

| Time (UTC) | Event |
|---|---|
| 04:30:56 | JVM start |
| 04:31:05–04:31:10 | `bootstrap:root` — 13 forms, 13 receipts, **5 errored** |
| 04:31:06 | boot core fault: `database-value-identity` dev panic |
| 04:31:13 | run `a7e24a23` opens on a fault message |
| 04:35:02 | driver submits `LIVE-DRIVE-0808-A` |
| 04:38:02 | first provider attempt — 225 prompt / 10,502 completion tokens |
| 04:38:02 | driver submits `LIVE-DRIVE-0808-B` |
| 04:39:44 | `a7e24a23` evaluates 6 forms in one second, 5 of them errors |
| 04:39:47 | `a7e24a23` closes; run `cf7cc2f1` opens and closes in the same second, 0 forms |
| 04:41:32–04:48:43 | seven more runs, seven more attempts, same 509-char prompt |
| 04:50:42 | 11 runs, 46 forms, 41 receipts, 9 attempts, 37 errors |

Run `a7e24a23` opened at 04:31:13 and its first evaluation landed at 04:39:44 —
**8m31s from run open to first form**, essentially all of it the provider call
and the wait for a wake. Once evaluation began, all six forms settled inside one
second, and the run closed three seconds later. Per-form evaluation is not the
cost here.

## Charter findings

### (a) Custody — clean, and better than 08-06

Across eleven runs, at every one of 84 samples: exactly one run without
`:seon.cluster.run/closed-at`, exactly one run carrying
`:seon.cluster.run/process`, and they were always the same run. Every closed run
had shed its process attribute. No orphan, no second holder. The 08-06 finding
that a run stayed open indefinitely did not recur.

### (b) Receipts — terminal, but the accounting does not balance

41 receipts, **0 interrupted**, all carrying `:seon.cluster.eval/at` and
`:seon.cluster.eval/result-size`. Every receipt that exists reached a terminal
state, and identities are distinct.

But **46 forms produced 41 receipts**. The first instance was visible early: run
`a7e24a23` recorded 7 forms and 6 receipts, with ordinal 6 having no
`:seon.cluster.eval` row at all — no result, no error, no interruption — while
the run closed anyway. Its source was prose-only. By 04:50 the gap had grown to
five. Filed as
[Settle a receipt for every recorded run form](../../../seon/issues/a-runs-last-form-can-close-without-a-receipt.md).

29 of 41 receipts carry an error — a **71% evaluation error rate**, which is a
consequence of the empty context rather than an independent fact.

### (c) Message claiming — the 08-06 signature failure is FIXED

This is where I most expected to confirm 08-06 and instead refuted it. At
04:44:22 every claimed message was claimed by exactly one run, in strict eid
order, with no fan-out:

```clojure
:trigger-fanout {25346 1, 25356 1, 25361 1, 25367 1, 25372 1}
```

`LIVE-DRIVE-0808-A` (eid 25372) **was** claimed by a run of its own. The 08-06
failure — a human message leaking into an unrelated run's prompt while never
being claimed — did not happen. It could not have: no message reaches any
prompt at all now.

That last clause is the caveat. The claiming machinery is provably correct and
currently unobservable in its real job, because the prompt the run then renders
contains none of the message. Message claiming should be re-verified once the
context defect is fixed;
[Keep an unclaimed message out of an unrelated run's prompt](../../../seon/issues/unclaimed-message-enters-an-unrelated-run-prompt.md)
should not be closed on this drive's evidence.

Four of the five claimed messages were faults the system committed about
itself, which is the loop below.

### (d) Prompt renders — the token sentinel

All nine attempts, from the durable `:seon.ai.attempt/usage-edn` facts:

| # | At (UTC) | Prompt | Completion |
|---:|---|---:|---:|
| 1 | 04:38:02 | 225 | 10,502 |
| 2 | 04:39:47 | 225 | 9,992 |
| 3 | 04:41:32 | 225 | 3,995 |
| 4 | 04:42:18 | 225 | 2,463 |
| 5 | 04:42:43 | 225 | 12,147 |
| 6 | 04:44:50 | 225 | 6,931 |
| 7 | 04:46:01 | 225 | 3,134 |
| 8 | 04:46:36 | 225 | 12,338 |
| 9 | 04:48:43 | 225 | 5,089 |

**2,025 prompt tokens produced 66,591 completion tokens, 60,317 of them
reasoning — a 32.9:1 ratio.**

The sentinel finding is the inverse of the one the directive anticipated. 08-06
recorded 44,306 prompt / 7,329 completion for one turn; the prompt has since
collapsed 197× and the completion has grown 9×. **A starved prompt is more
expensive than a bloated one**, because the model substitutes reasoning for the
context it was not given. Token explosions are worth hunting at both ends.

### (e) Errors — 37 facts, none of them the drive's subject

Nine at boot, before any agent turn, on a freshly reforked cluster:

| Kind | Count | Message |
|---|---:|---|
| `:seon.sci.eval/evaluation-failed` | 3 | `Unable to resolve symbol: largest` |
| `:seon.instrument/contract-violated` | 2 | `seon.program/declaration-row violated its contract (invalid-output)` |
| `:seon.instrument/contract-violated` | 1 | `seon.db/database-value-identity violated its contract (invalid-output)` |
| `:seon.operator/collection-incomplete` | 1 | `Collection did not preserve and verify every recorded root.` |
| `:seon.operator/process-census-incomplete` | 1 | `The process census could not read every external claim.` |
| `:seon.operator/reap-incomplete` | 1 | `The reaper cannot read every external claim.` |

The three `largest` failures confirm the driver's bootstrap-placeholder note
independently: `resources/seon/bootstrap.edn` defines `largest` and then
demonstrates it three times, and all three demonstrations fail unresolved. The
teaching plan's own lesson — call a function badly, see an honest arity error —
is replaced by three identical resolution failures. A fresh agent's first
history is its teaching material erroring.

The boot core fault is worth naming on its own:

```text
SEON CORE FAULT (dev panic): seon.db/database-value-identity violated its
contract (invalid-output): {:datahike/commit-id [{:value nil, :message "should
be a uuid"}], :seon.error/kind [{:value nil, :message "missing required key"}],
:seon.error/message [{:value nil, :message "missing required key"}]}
```

It fires once during boot and the same function succeeds afterwards
(`{:db-name "cluster-default" :t 536870999 :datahike/commit-id "6a76b2ab-…"}`),
so this is boot ordering: the identity is asked for at a moment when the commit
id is not yet available. The face is also confusing — it reports the success
branch's complaint *and* the error branch's two missing keys, so a reader cannot
tell which shape was intended.

`:seon.error/data-edn` sizes were 4,321 / 4,320 / 2,144 ×3 / 15,403 / 147,791 /
149,869 / **4,249,999** characters. The 4 MB print tree recorded on 08-06 at
4,010,918 characters recurred on a brand-new cluster and grew — a 55-character
message carrying 4.25 MB of nested `#:seon.print` nodes. Existing owner:
[Keep contract-violation evidence as data](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).

### (e2) One independent capability gap the agent found for us

Given nothing but a contract error, the agent did the reasonable Clojure thing
and reached for `pprint`. Run `a7e24a23`, form ordinals 4 and 5:

```clojure
(require '[clojure.pprint :refer [pprint]])
;; error — "Could not find namespace clojure.pprint."
(pprint (seon.db/read-evidence db))
;; error — "Unable to resolve symbol: pprint"
```

Verified directly against the live base context rather than trusting the
receipt: `clojure.string`, `clojure.set`, `clojure.walk`, and `clojure.edn` all
resolve; `clojure.pprint` does not. Two of that run's six receipts were this one
gap. This is independent of the context defect — it would fail the same way in a
healthy turn. Filed as
[Make `clojure.pprint` available in the agent's REPL](../../../seon/issues/agent-repl-cannot-require-clojure-pprint.md).

### (f) Web surfaces — two 08-06 blockers fixed, one traded

| Route | 2026-08-06 | 2026-08-08 |
|---|---|---|
| `/` | 200, 518,673 B | 200, 313,618 → 737,602 B; TTFB 2.4–3.1 s cold, 13 ms warm |
| `/agent/root` | 200 | 200, byte-identical alias |
| `/ns/my.agents.root` | 200 | 200, byte-identical alias |
| `/ns/my.agents.root/debug` | **no first byte in 5 s** | **200 in 25 ms** |
| `/data` | **500 in 41 ms** | **200 in 5.5 s** |

The debug page is genuinely fixed: it returns a 2,087-byte shell immediately and
streams content over `/feed/root?debug=true`. Its left pane honestly reads
`No recorded context capture exists for this agent.` when none exists. The
"Renderer unavailable" unit that opened both 08-06 prompts is gone from the root
page.

The root page reflects the drive live — `LIVE-DRIVE` appears in it, and the SSE
feed streams article morphs correctly. It also **grew from 313 KB to 737 KB
during the observation window**, roughly 140 KB per lap of the fault loop, while
carrying 255 `<article>`, 74 `<pre>`, 115 raw `:db/id` strings, 34 occurrences of
`violated its contract` and 20 of `Walk failed`.

That contrast is itself a finding: **the two projections of the same blocks
disagree completely.** `:seon.render/html` degrades per block — 255 articles
render around 34 embedded contract errors. `:seon.render/ai` fails whole — one
error replaces the entire prompt. The AI projection has no per-block degradation,
and it is the one that feeds the agent.

`/data` is fixed as a 500 and broken as a stall: three consecutive samples at
5.52 / 5.43 / 5.49 s for 3,168 bytes, with TTFB equal to total every time and no
warm path. Filed as
[Return `/data` without a five-second stall](../../../seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md),
with the cause proven below.

### (g) The declaration-population fallback — not closed

The overnight report records the admission blocker as CLOSED. That is true of
the admission seam and understates the class; the dominant caller has moved
outside admission.

At 04:36Z, six minutes after boot: **3,118 resolutions across 26 callers**, led
by `seon.print (print.cljc:232)` at 1,288, `seon.sci.admit (admit.clj:431)` at
408, and `seon.db (db.clj:362)` at 352. By 04:37Z, 5,104. By 04:50Z, **9,665**.

The cost is real, not just noisy logging. The resolution is **not memoized** —
10 calls and 100 calls both average **10.6 ms** — so the counter multiplies
directly into wall time. 9,665 resolutions is roughly 102 seconds of CPU in a
twenty-minute-old JVM.

And it fully explains `/data`. Snapshotting the counter around exactly one
request that took 5.441 s:

```clojure
{:total-delta 556
 :estimated-ms 5893.6            ; 556 × 10.6 ms
 :by-caller {"seon.schema.datahike (datahike.clj:71)" 160
             "seon.schema.datahike (datahike.clj:72)"  80
             "seon.schema.datahike (datahike.clj:112)" 64
             "seon.schema.datahike (datahike.clj:203)" 50
             ;; … ~530 of 556 from seon.schema.datahike
             "seon.print (print.cljc:232)"              4}}
```

5,894 ms estimated against 5,441 ms observed, within 8%. The Malli-to-Datahike
bridge re-resolves the whole population per attribute. Appended with the full
table to
[Resolve the declaration population once per admission, not once per node](../../../seon/issues/value-admission-resolves-the-declaration-population-per-node.md).

The warning wall is also still a wall by volume: `data/clusters/default/logs/
seon.log` is 87 lines, of which **70 are fallback occurrence lines** — 80% of
the entire cluster boot log.

## The fault loop

Runs `cf7cc2f1` and `84799227` each opened and closed within the same second
with zero forms, because the model's reply could not be read:

```text
A run phase failed: Invalid symbol: refused:
A run phase failed: Reader tag is not accepted: :message
```

Each of those closures committed the message quoted above, addressed to the same
agent. Those messages are ordinary wake facts, so they trigger the next run,
which renders the same broken 509-character prompt, which produces another
unreadable reply. Nine provider calls in twenty minutes with no external
stimulus after 04:38.

Nothing in the cycle is deduplicated by fault signature or bounded. Filed as
[Stop a failed turn from waking itself through its own fault message](../../../seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md).
It is filed separately from the context defect on purpose: the empty context
explains why turns fail, this explains why they repeat, and it would turn any
recurring turn failure into the same loop.

## Ugly output, verbatim

**The MCP admission window drops a vector's tail without saying so.** Asking for
nine error rows returned a five-element vector whose fifth element is the string
`"seon.sci.admit/elided"`:

```clojure
[{:db/id 25335, :kind "seon.instrument/contract-violated",
  :message "seon.sci.admit/elided", :at "2026-08-08T04:31:06Z",
  :seon.sci.admit/elided true}
 …
 "seon.sci.admit/elided"]
```

There were nine rows. The projection presents as a complete five-element vector.
The vocabulary table defines an elision value as data carrying omitted count,
known total, path, next offset, profile, and requery identity — this carries
none of that, and a reader who does not already know the true count cannot tell
anything was removed. My explicitly 300-character-truncated `:message` was also
replaced wholesale by the marker.

For contrast, the elision the agent received inside its own run is the correct
shape, which makes the MCP one clearly a defect rather than a design choice:

```clojure
{:seon.print/face :seon.print/elided
 :seon.print/omitted 106349
 :seon.render.data/total 108397
 :seon.render.data/next-offset 2048
 :seon.render.profile/id :seon.render.profile/agent
 :seon.print/requery-refusal "the value has no durable blob or entity identity"}
```

**A set prints as a bare `#`.** In the agent's actual prompt:
`{:value #, :message "should be :all"}`.

**`datahike.api/db` intermittently throws `class datahike.db.DB cannot be cast
to class clojure.lang.IFn`** at the REPL against a valid connection, while
`(deref conn)` on the same connection works. It succeeded once and then failed
on every subsequent call in the same session. Not chased to cause; recorded
because it cost time and looks like a real inconsistency in the one database
namespace.

## Disagreements with the reports

Stated plainly, as the charter asks.

1. **"Admission blocker CLOSED" is too strong.** The admission seam improved;
   the class did not close. 9,665 resolutions in twenty minutes, un-memoized at
   10.6 ms, with the dominant caller now `seon.print` and the worst single
   surface `seon.schema.datahike`. Evidence appended to the owning issue.
2. **"Warning wall fixed at owner" is not what the log shows.** 70 of 87 boot
   log lines are the fallback diagnostic. The per-occurrence line is short now,
   which was the fix; the volume is unchanged because the underlying count is
   unchanged.
3. **The 08-06 report's message-claiming blocker did not recur**, and I want
   that on the record as a refutation in the system's favour — but it also
   cannot be closed on this drive, because no message reaches any prompt to
   leak into one.
4. **08-06's `/data` and debug-page blockers are fixed.** Both were real; both
   are gone. `/data` traded a 500 for a 5.5 s stall, which is a different issue,
   not the same one unfixed.

## Arc coverage

| Requested probe | Outcome |
|---|---|
| Real root task, run, turns, reply | Runs opened and closed and the model replied nine times, but never saw a task. Not tested. |
| Contracted function, next-turn call | not reached |
| Schema declaration, transact, query | not reached |
| Second agent, send/wait/complete | not reached |
| Honest bad arity / unresolved symbol | Observed, but as accidents of a broken context rather than as designed probes. The bootstrap's own deliberate bad-arity lesson also failed, unresolved instead of bad-arity. |
| Exact rendered context | Completed — ten captures, all 509 chars |
| Web routes | All five loaded; three 200 aliases byte-identical, debug fixed, `/data` fixed but slow |

The four unreached rows are unreached for the same single reason, and none of
them should be counted as covered or as failing.

## What is genuinely in good shape

- **Custody.** Eleven runs, 84 samples, always exactly one open run and one
  holder, never an orphan or a double claim. This is the part of the 08-06
  report that is now clean.
- **Message claiming is exactly-once and in order**, with no fan-out.
- **Receipts that exist are honest**: distinct identities, correct run
  provenance, zero interrupted, errors recorded as errors rather than swallowed.
- **The provider integration is sound**: nine attempts, correct usage and cache
  accounting, `finish_reason` `stop` every time, no 402, no retry storm.
- **Context capture is durable and exact** — it is the reason this whole
  diagnosis was possible without touching process memory.
- **The web transport works**: byte-identical aliases, a warm keyframe path at
  13 ms for a 738 KB page, a debug page that no longer blocks, and a working SSE
  feed streaming block morphs.
- **The diagnostics did the finding.** The declaration-population counter named
  its own callers and priced `/data` to within 8%; the elision value inside the
  agent's run carried a complete, honest description of what it dropped. Both
  are the flywheel working.

## Issues filed

New:

- [Stop a failed turn from waking itself through its own fault message](../../../seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md) — blocker
- [Settle a receipt for every recorded run form](../../../seon/issues/a-runs-last-form-can-close-without-a-receipt.md) — blocker
- [Make `clojure.pprint` available in the agent's REPL](../../../seon/issues/agent-repl-cannot-require-clojure-pprint.md) — friction
- [Return `/data` without a five-second stall](../../../seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md) — friction

Evidence appended to existing notes:

- [Give an as-of database value a dependency revision](../../../seon/issues/walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md)
- [Resolve the declaration population once per admission, not once per node](../../../seon/issues/value-admission-resolves-the-declaration-population-per-node.md)

`bin/issues-index --check` clean: 159 open, 1,002 archived.
