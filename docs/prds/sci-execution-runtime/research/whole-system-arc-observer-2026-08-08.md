---
type: research
status: complete
tags: [research, runtime, agent]
---

# Observer lane — whole-system arc on `default`, 2026-08-08

Independent observation of the whole-system arc drive on cluster `default`,
run as the trust-nothing half of a driver+observer pair. This lane took no
stimulus action: it submitted no message, transacted nothing, stopped nothing,
and reforked nothing. Every fact below came from read-only Datalog queries
against the live connection, my own HTTP requests to the running server, the
cluster log file, and source reads.

I read end to end, before starting:
[whole-system-arc-2026-08-08.md](../plan/whole-system-arc-2026-08-08.md) and my
predecessor's [live-drive-observer-2026-08-08.md](live-drive-observer-2026-08-08.md),
including its two recorded self-deceptions. I did not repeat either of them.
I did commit four *new* ones and caught all four before they reached this
report; they are recorded in [Method](#method-and-my-own-near-misses) because
the class matters more than my embarrassment.

## Verdict

**The concurrency proof is real and is the headline.** Four agents held four
runs open simultaneously for a measured 20–30 seconds, with clean per-agent
custody, exactly-once message claiming, and zero cross-agent context leakage.
That is the first time this has been demonstrated on measured intervals rather
than inferred from ordering, and it is a genuine advance over 08-06 and 08-08
morning.

**The predecessor's headline defect is fixed.** The 509-character empty-context
prompt is gone. Agents now receive a real REPL session with their namespace,
instructions, and the human's message. Everything the 08-08 morning lane could
not test became testable.

**The arc did not complete its own acceptance criteria.** None of the three
assigned contracted functions exist; no plan was recorded as a durable fact;
the agent's defs (`:seon.def/*`) is entirely absent from the database; and — the
deviation most worth stating plainly — **root never created the three agents.
Something outside root's runs did.**

**Stage 3's restart ran and mostly succeeded, but its honesty claim did not.**
71 seconds unavailable, every agent and every fact carried forward, custody
clean across the process replacement. However `:seon.cluster.eval/interrupted-at`
is declared and has zero datoms cluster-wide: the run that was in flight closed
with no error and no interruption marker, indistinguishable by query from a run
that ended normally. "Interrupted receipts honest" is not demonstrated, and the
receipt-gap defect is what makes it unfalsifiable.

**Three new defects are worth the owner's attention**, all found by measurement
rather than inspection: the prompt-token budget is silently exceeded because
the estimator disagrees with the provider by 23–26%; the provider seam fails
under concurrent turns with paid-for-but-discarded completions, and a new
`HttpClient` is built per request; and a wrong-arity call reports a namespace
that demonstrably exists as missing.

## Scope and method

- cluster `default`, pid `31475`, generation `08bc9022-036d-4035-af03-3925a6d5b10e`;
- prepl `127.0.0.1:51016`, web `http://127.0.0.1:7994`;
- JVM start `2026-08-08T09:33:25Z`; observation window `09:36:39Z` → `10:09:19Z`,
  spanning the stage-3 restart;
- after the restart: pid `48613`, prepl `52905`, same web port;
- basis before the restart: 24 runs, 101 forms, 99 receipts, 55 error facts;
- basis after: 26 runs, 105 forms, 102 receipts, 60 error facts, 22 messages.

Two samplers ran throughout, both committed:

- `tmp/observer_0808b_sample.clj` — an in-JVM thread appending a read-only
  census every 5 s. It derefs the connection, queries, and appends to a file;
  it never transacts. Output: `tmp/observer-0808b-timeline{,2,3}.edn`.
- `tmp/observer-0808b-outside.sh` — an external liveness probe every 3 s
  (HTTP status + `pgrep`), because an in-JVM sampler cannot survive the
  stage-3 restart it is supposed to observe.

Raw dumps alongside: `tmp/observer-0808b-errors.edn`,
`tmp/observer-0808b-evalerrors.edn`, `tmp/observer-0808b-prompt-first.txt`,
`tmp/observer-0808b-prompt-last.txt`.

### Method, and my own near-misses

The predecessor's warning generalizes further than it stated, so I am recording
four traps I hit myself. Every one produced a plausible-looking wrong answer.

1. **`d/datoms db :avet attr` silently returns nothing for a non-indexed
   attribute.** My census counted errors that way and reported **0 errors** for
   the first 63 samples. The real count at that moment was 35. `:seon.error/id`
   is unique and indexed so it returned 21, which made the 0 look like a
   considered result sitting next to a working one. Counting by query gives 35;
   counting by `:avet` gives 0. Both "run".
2. **A map-spec pull on a non-ref attribute throws.**
   `:seon.cluster.run/process` is a string (`"31475-1786181598529"`), and
   `{:seon.cluster.run/process [:db/id]}` raised — but only when a run was
   *open and held*. My sampler therefore crashed at exactly the moments it
   existed to observe, wrote a `:sampler-error` line, and left 71 lines reading
   `:open []`. "No open runs, custody clean" was the most confident wrong
   conclusion I reached all session. The 15 error lines were the concurrency.
3. **A nested call inside a Datalog predicate matches nothing.**
   `[(clojure.string/starts-with? (str ?n) "arc.")]` returned zero rows while
   the three `arc.*` namespaces plainly existed. No error; just an empty result.
4. **`d/q` returns a set, so `frequencies` over a projected column collapses
   duplicates.** "Runs per agent" came back `{inventory 1, root 1, timeline 1,
   health 1}` across 23 runs. Including `?r` in the find gives the true
   `{root 10, inventory 5, timeline 4, health 4}`.

I also nearly filed two false defects and killed both with one more probe:

- **`(doc seon.cluster/ensure-entity!)` returning nil is not a defect.** The
  `doc` macro expands to `println`s and ends in `nil`
  (`src/seon/sci/eval.clj:1092-1100`), exactly like `clojure.repl/doc`. The
  documentation is in `:seon.cluster.eval/output` — 1,184 correct characters.
- **The SSE feed is not dead.** A 5-second sample returned 0 bytes; a
  20-second sample returned **810,671 bytes** of `datastar-patch-elements`
  morphs. My window was too short, not the feed broken.

The shared lesson: an empty or nil result from a read API is never evidence of
absence until a second, differently-shaped probe agrees.

## Timeline

| Time (UTC) | Event |
|---|---|
| 09:33:25 | JVM start, pid 31475 |
| 09:33:25–09:33:30 | `bootstrap:root` — 13 forms; 2 carry raw `{{seon.ns/name}}` |
| 09:33:30–09:34:21 | 4 root runs on boot self-faults; prompt 16.8k → 33.5k tokens |
| 09:34:21–09:38:34 | idle |
| 09:38:34 | human opening message claimed by root run `ec979da7` |
| 09:38:53 | `ec979da7` closes after 2 forms — `doc`, then a process-id query |
| 09:40:35–09:41:37 | root `9c7fa70f`: 3× `ensure-entity!` **wrong number of args (2)** |
| 09:45:03 | **three agents appear**; `bootstrap:{inventory,health,timeline}` run concurrently |
| 09:45:24–09:45:46 | **four-way concurrency**: root, inventory, health, timeline all open |
| 09:47:38 | driver sends a correction to all three; 6 `unparseable-body` errors |
| 09:50:55–09:55:40 | further sibling turns; 8 more `unparseable-body` in retry pairs |
| 09:51:32 | root `63a30421`: 3× `ensure-entity!` with `db/*conn*` → **nil connection** |
| 10:03:57 | root run `945f3226` opens — it will be caught by the restart |
| 10:04:06 | **stage-3 restart begins**: HTTP refuses while pid 31475 still alive |
| 10:04:46 | pid 31475 exits; 10:04:49 new pids appear |
| 10:05:17 | first `200` from pid 48613 — **71 s unavailable**; `ready-ms` 6,221 |
| 10:04:55 | recovery closes `945f3226` — no error, no interruption marker |
| 10:09:19 | window ends; 26 runs, 105 forms, 102 receipts, 60 errors |

## Charter findings

### (a) Three-agent concurrency — REAL, measured

Measured from `:seon.cluster.run/opened-at` and `/closed-at`, not inferred from
ordering. Pairwise overlaps, largest first:

```clojure
[{:a "inventory/26594ee9" :b "timeline/4ea21f09"  :overlap-ms 30002}
 {:a "health/188de1d3"    :b "inventory/26594ee9" :overlap-ms 21075}
 {:a "inventory/26594ee9" :b "root/b0f70394"      :overlap-ms 21044}
 {:a "health/188de1d3"    :b "timeline/4ea21f09"  :overlap-ms 20873}
 {:a "health/188de1d3"    :b "root/b0f70394"      :overlap-ms 20847}
 {:a "timeline/4ea21f09"  :b "root/b0f70394"      :overlap-ms 20645}
 {:a "health/bootstrap"   :b "inventory/bootstrap" :overlap-ms 5361}]
```

Four distinct agents held four runs concurrently for roughly 20 seconds around
09:45:25–09:45:45, and the three bootstrap runs overlapped ~5.3 s three ways.
The sampler independently caught `:open` and `:held` carrying three run ids at
09:45:04 and again at 09:53:32.

**Custody is clean.** Every open run carried `:seon.cluster.run/process`
`"31475-1786181598529"`; every closed run had shed it (all 24 closed runs show
`:seon.cluster.run/process` absent). No run was ever held by two processes, and
no closed run retained a holder. Concurrency did not blur custody.

**No cross-agent context leakage.** Counting namespace mentions in each
capture's exact prompt bytes:

| Agent | own-ns mentions | `arc.inventory` | `arc.health` | `arc.timeline` |
|---|---:|---:|---:|---:|
| inventory | 27, 26 | — | **0** | **0** |
| health | 27, 28 | **0** | — | **0** |
| timeline | 27, 30 | **0** | **0** | — |
| root | 41–53 | 4–7 | 4–7 | 4–6 |

Each sibling sees its own world and neither sibling's. Root sees all three,
correctly — it is the delegator and the human's message names them.

### (b) Receipts — the 46/41-style gap RECURS, with a new trigger

101 forms, 99 receipts. The gap is exactly 2 and is persistent, not transient.
(It was also transiently 80/46 at 09:45:04 while three bootstraps evaluated at
once, converging to 80/80 within 10 s — that part is healthy.)

Both unsettled forms belong to root, and both are prose-only forms whose source
is **raw provider control markup**:

```clojure
{:run "b0f70394" :agent "root" :missing-ordinals [0]
 :src "; <｜｜DSML｜｜AgentThoughts>We need respond to current instruction about core fault. Need inspect. Let's gather data first.</｜｜DSML｜｜AgentThoughts>"}
{:run "91967e81" :agent "root" :missing-ordinals [0]
 :src "; <assistant1>"}
```

This confirms the existing blocker
[Settle a receipt for every recorded run form](../../../seon/issues/a-runs-last-form-can-close-without-a-receipt.md)
on a second cluster and adds its trigger: DeepSeek's internal channel markup
(`<｜｜DSML｜｜AgentThoughts>`, `<assistant1>`) is being read as agent source,
recorded as a comment-only form, and then settles nothing. Every receipt that
*does* exist is honest — distinct identity, correct run provenance, zero
interrupted, errors recorded as errors.

### (c) Message flow — exactly once, and now observable in its real job

14 messages at the mid-window census, later 19. Fan-out is uniform:

```clojure
:fanout {1 14}
```

Every message was claimed by exactly one run, and in every case the claiming
run's agent equals `:seon.cluster.message/to`. Delegation messages to
`inventory`, `health`, and `timeline` at 09:45:24–25 were each claimed by that
agent's own run. Unlike 08-08 morning — where claiming was correct but
unobservable because no message reached a prompt — the messages here demonstrably
reach the recipients' prompts. I consider the claiming mechanism proven on this
drive.

Three `:seon.cluster.message/unknown-recipient` errors are honest refusals, not
claiming failures: root tried to message the agents before it had created them.

### (d) Web surfaces — sampled myself

| Route | Status | Bytes | TTFB cold | TTFB warm |
|---|---|---:|---:|---:|
| `/` | 200 | 396,688 | 3.61 s | 0.015 s |
| `/agent/root` | 200 | 396,688 | — | 0.014 s |
| `/ns/my.agents.root` | 200 | 396,688 | — | 0.015 s |
| `/ns/my.agents.root/debug` | 200 | 112,875 | — | 0.027 s |
| `/ns/arc.inventory` | 200 | 451,169 | 1.71 s | — |
| `/ns/arc.health` | 200 | 344,264 | 1.75 s | — |
| `/ns/arc.timeline` | 200 | 249,481 | 1.75 s | — |
| `/ns/arc.inventory/debug` | 200 | 62,204 | 0.031 s | — |
| `/data` | 200 | 3,168 | **6.54 s** | **6.41 s (no warm path)** |

The three aliases are byte-identical, matching the predecessor. The three new
namespace pages are distinct sizes, so they are really rendering their own
agents' worlds rather than aliasing. Debug pages return promptly.

**SSE works.** `/feed/root` returns `text/event-stream` immediately and
streamed **810,671 bytes in 20 seconds** of `datastar-patch-elements` article
morphs — roughly 40 KB/s sustained. That is the live-update property the arc
requires, and it holds.

**`/data` regressed.** The predecessor filed it at 5.4–5.5 s; I measure
6.41–6.54 s for the same 3,168 bytes, with TTFB equal to total on every sample
and still no warm path. I priced one request independently by snapshotting the
declaration-population counter around it:

```clojure
{:total-delta 927
 :observed-ms 6412
 :top {"seon.schema.datahike (datahike.clj:71)"  264
       "seon.schema.datahike (datahike.clj:72)"  133
       "seon.schema.datahike (datahike.clj:112)" 109
       "seon.schema.datahike (datahike.clj:220)"  80
       "seon.schema.datahike (datahike.clj:203)"  80}}
```

927 resolutions for one 3 KB page, essentially all from the Malli-to-Datahike
bridge. Same cause the predecessor proved, 67% more resolutions.

### (e) Token sentinel — healthy ratios, but the declared budget is breached

All attempts, from durable `:seon.ai.attempt/usage-edn`:

| At (UTC) | Agent | Prompt | Completion | Reasoning | c:p | Cache hit |
|---|---|---:|---:|---:|---:|---:|
| 09:33:31 | root | 16,772 | 1,278 | 1,190 | 0.08 | 512 |
| 09:33:46 | root | 17,695 | 410 | 301 | 0.02 | 512 |
| 09:33:54 | root | 19,811 | 725 | 452 | 0.04 | 1,280 |
| 09:34:05 | root | 33,476 | 1,117 | 999 | 0.03 | 1,408 |
| 09:38:34 | root | 35,453 | 892 | 808 | 0.03 | 1,536 |
| 09:40:35 | root | **35,827** | 6,702 | 6,119 | 0.19 | 1,664 |
| 09:45:06 | root | 9,496 | 2,738 | 2,706 | 0.29 | 0 |
| 09:45:24 | inventory | 16,812 | 3,608 | 3,313 | 0.21 | 0 |
| 09:45:25 | health | 16,778 | 1,038 | 1,010 | 0.06 | 0 |
| 09:45:25 | timeline | 16,900 | 778 | 614 | 0.05 | 0 |
| 09:45:46 | root | 9,716 | 2,202 | 2,195 | 0.23 | 3,072 |

**No pathology.** The worst ratio is 0.29 against the 46.7 the directive told
me to watch for; most are far below the 0.22 band. `finish_reason` is `stop`
every time. There is no runaway completion and no context collapse.

Three real observations:

1. **The declared prompt-token budget is exceeded.**
   `:seon.config.ai/prompt-token-budget` is 32,768 and the effective settings
   recorded on the attempt confirm it. Three prompts went out at 33,476,
   35,453 and 35,827 provider tokens — up to **3,059 over** — with no refusal
   and no warning. Cause below in (e2); filed.
2. **Session growth is bounded by run completion, not unbounded.** Root's chain
   grew 16.8k → 35.8k tokens over six turns, then **reset to 9,496** when a run
   completed. My earlier concern about unbounded growth was wrong and I am
   recording the correction. The three fresh agents each opened at ~16.8k.
3. **`thinking :disabled` is billing reasoning tokens.** The same attempt whose
   `:seon.ai.attempt/settings-edn` records `:seon.config.ai/thinking :disabled`
   reports `reasoning_tokens 1190`, and a later one 6,119. Across the window,
   reasoning is 90–99% of every completion (e.g. 2,706 of 2,738). Either the
   dial is not reaching the wire or DeepSeek ignores it; either way we are
   paying for output we declared off.

### (e2) The estimator disagrees with the provider by a quarter — NEW

`seon.ai.tokens/estimate` is a flat chars/4 (53,137 chars → exactly 13,284).
DeepSeek's own count is consistently higher:

| Run | Prompt chars | `tokens/estimate` | Provider | Under by | Ratio |
|---|---:|---:|---:|---:|---:|
| 5dceb446 | 53,137 | 13,284 | 16,772 | 3,488 | 1.26 |
| dc1c7df7 | 63,538 | 15,884 | 19,811 | 3,927 | 1.25 |
| f79b24c3 | 108,032 | 27,008 | 33,476 | 6,468 | 1.24 |
| 9c7fa70f | 116,572 | 29,143 | **35,827** | 6,684 | 1.23 |
| 26594ee9 | 54,026 | 13,506 | 16,812 | 3,306 | 1.24 |
| b0f70394 | 32,786 | 8,196 | 9,496 | 1,300 | 1.16 |

The budget guard in `src/seon/cluster/prompt.clj:105-123` compares
`tokens/estimate` against the budget and drops render distance until it fits.
It is doing its job correctly against a number that is systematically ~23% low,
so the effective budget is ~40,000 provider tokens, not the declared 32,768.
Nothing is loud about it. This also affects every human-visible size, since
`AGENTS.md` makes `tokens/estimate` the one display unit.

### (f) Restart — happened at 10:04, recovery mostly clean, honesty NOT demonstrated

The stage-3 restart occurred after my first pass through this section (which
recorded it as not-yet-happened). Measured across it by the external probe and
the in-JVM sampler:

| Time (UTC) | Observation |
|---|---|
| 10:04:03 | last `200` from the old JVM |
| 10:04:06 | HTTP starts refusing (`000`) while pid 31475 is **still alive** — the web surface stops before the process exits |
| 10:04:08–10:04:39 | in-JVM sampler reports `:cluster-absent` ×7 — the cluster is deregistered from `running-instances` while the JVM still runs |
| 10:04:46 | pid 31475 gone |
| 10:04:49 | new pids appear (48595, 48598, **48613**) |
| 10:05:01 | first request to the new JVM times out at 12.0 s |
| 10:05:17 | first `200`, 3.49 s cold |
| 10:05:23 | warm, 15 ms |

**Total unavailability: 71 seconds** (10:04:06 → 10:05:17). New pid 48613, new
prepl 52905. `:seon.boot/ready-ms` is 6,221 — so boot itself was 6.2 s and most
of the 71 s is shutdown plus the pre-ready window.

**What recovered — cleanly.** All four agents survived. The database carried
forward without loss: 26 runs, 105 forms, 102 receipts, 22 messages, 60 error
facts, and all `arc.*` namespaces still bound to their agents. `bin/seon status`
reports `1/1 clusters alive`. Recovery reported `:seon.boot/recovered-runs 1`
and `:seon.boot/recovery-operations 1`. No run was left open, and no run
retained a stale `:seon.cluster.run/process` — custody across a process
replacement is clean.

**What was NOT honest.** The crash model promises that recovery marks dangling
receipts `:interrupted`. The one run that was in flight across the restart:

```clojure
{:id      "945f3226-e46c-44c0-b3a5-e8546ec316b2"
 :agent   "root"
 :opened  "2026-08-08T10:03:57Z"      ; before the restart
 :closed  "2026-08-08T10:04:55Z"      ; after the old JVM exited — closed BY recovery
 :process nil                          ; custody correctly shed
 :run-error ""                         ; no error recorded
 :forms   [{:ord 0 :src "; <assistant1>I'm checking the facts before answering — first the relevant schema and entity attributes."}]
 :receipts []}                         ; no receipt, no interrupted-at
```

`:seon.cluster.eval/interrupted-at` **is declared** in the live schema and has
**zero datoms cluster-wide**. So from the database alone this interrupted run is
*indistinguishable* from the two prose-only runs that closed normally before the
restart with the identical signature — one form, zero receipts, no error. The
only evidence that a turn was cut off is `:seon.boot/recovered-runs 1` on the
boot instance value, which is process-local and dies with the next JVM.

One honest caveat, because it limits the claim: this run's single form was
comment-only, and comment-only forms settle no receipt anyway (charter (b)). So
this does not prove that a *mid-evaluation* interruption goes unmarked. What it
does prove is weaker and still important — recovery closed a dangling run
without recording anywhere durable that it was interrupted, and the two causes
of an unsettled form are not separable by query. "Interrupted receipts honest"
is therefore **not demonstrated on this drive**, and the receipt-gap defect is
what makes it unfalsifiable.

**"Plans survive the restart" has nothing to test.** No durable plan fact was
ever recorded, and the `:seon.def/*` attributes for the agent's defs appear nowhere in the
database, before or after. Nothing was lost because nothing was written.

### (g) Every error fact

55 `:seon.error/kind` facts at window end (my `:avet`-based count of 0 was the
trap in Method #1):

| Kind | Count |
|---|---:|
| `:seon.ai/unparseable-body` | 14 |
| `:seon.sci.eval/evaluation-failed` | 12 |
| `:seon.instrument/contract-violated` | 12 |
| `:user-input` | 8 |
| `:seon.cluster.message/unknown-recipient` | 3 |
| `:seon.db/invalid-read` | 2 |
| `:seon.operator/collection-incomplete` | 1 |
| `:seon.operator/process-census-incomplete` | 1 |
| `:seon.operator/reap-incomplete` | 1 |
| `:seon.operator/failed` | 1 |

Largest `:seon.error/data-edn` is 158,271 characters — still a print tree
serialized into error data, but two orders of magnitude below the 4.25 MB the
predecessor recorded. That is a real improvement on
[Keep contract-violation evidence as data](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).

Verbatim messages that matter:

```text
Wrong number of args (2) passed to: seon.cluster/ensure-entity!
seon.cluster/ensure-entity! violated its contract (invalid-input):
  [[{:value nil, :message "must be a live unreleased Datahike connection from the calling cluster"}]]
There is no agent named "inventory" in this cluster, so nothing was sent to it.
No such namespace: arc.inventory
Could not find namespace clojure.pprint.
The provider's response was not readable JSON: closed
```

`clojure.pprint` still does not resolve, so
[Make `clojure.pprint` available in the agent's REPL](../../../seon/issues/agent-repl-cannot-require-clojure-pprint.md)
reproduces unchanged on a fresh cluster.

### (g2) The provider seam fails under concurrency — NEW

All 14 `:seon.ai/unparseable-body` errors read `The provider's response was not
readable JSON: closed`. Their distribution is the finding:

```clojure
:by-agent {"inventory" 6, "health" 4, "timeline" 4}   ; root: 0
:by-time  {"09:47:38" 6, "09:52:28" 2, "09:53:28" 2, "09:53:29" 2, "09:55:40" 2}
```

They occur **only** for the three concurrent siblings, never for root, and they
arrive in pairs — the initial call plus its retry (`maximum-retries 2`). The
burst of 6 at 09:47:38 is three agents × 2.

`src/seon/ai.clj:1069` builds a **new `HttpClient` per request**:

```clojure
(let [client (.build (HttpClient/newBuilder))
```

A JDK `HttpClient` owns its connection pool and selector thread, and when it
becomes unreachable its connections are closed — which is exactly the `closed`
that `slurp` on the streamed body reports. I am stating this as a strong
hypothesis with a correlation and a file:line, not a proven cause; it needs a
falsifier (hold one client and re-run a concurrent burst).

The cost is real and acknowledged in our own code comment: a 2xx body existed,
so **the provider generated and charged for 14 completions we discarded**.

### (h) Declaration-population fallback and the warning wall

Measured myself, 28 minutes after boot: **14,583 resolutions across 45
callers**. I measured the per-call cost rather than reusing the predecessor's
constant:

```clojure
{:ms-per-call-10 12.90, :ms-per-call-50 11.51}   ; flat => not memoized
```

≈168 seconds of CPU in a 28-minute JVM. The caller mix has shifted materially
since the morning lane:

| Caller | Count |
|---|---:|
| `seon.print (print.cljc:232)` | 6,337 |
| `seon.schema.datahike (datahike.clj:71)` | 2,080 |
| `seon.schema.datahike (datahike.clj:72)` | 1,040 |
| `seon.schema.datahike (datahike.clj:112)` | 768 |
| `seon.schema.datahike (datahike.clj:220)` | 600 |

`seon.print` is now the dominant single caller at 43% of all resolutions, up
from 1,288 in the morning. The warning wall persists by volume: **44 of the
first 62 boot log lines (71%)** are fallback occurrence lines; at window end
the log is 113 lines.

## Arc coverage against the spec

| Spec item | Outcome |
|---|---|
| 1. Root claims opening message, delegates to three agents | **Partial.** Root claimed the message. Root did **not** create the agents — see below. |
| 2. Three agents run concurrent live turns; messages flow | **Met, and well.** Measured 20–30 s overlaps, exactly-once claiming, no leakage. |
| 3. Each agent defines a contracted function a later form calls | **Not met.** The only `arc.*` functions are `arc.{inventory,health,timeline}/largest`, all from the bootstrap teaching plan. None of the three assigned functions exist. |
| 4. Pages update live over SSE; debug shows prompt bytes; `/data` answers; reconnect repaints | **Mostly met.** SSE streams 810 KB/20 s; debug pages return in ~30 ms; `/data` answers but in 6.4 s. Reconnect-repaint not separately exercised by me. |
| 5. Canvas centerpiece | **Not reached.** No canvas facts. |
| 6. Restart mid-arc; runs recover; plans survive | **Partial.** The restart ran (71 s unavailable) and recovery was clean — all agents, facts and custody carried forward. But the interrupted run carries no interruption marker, and no plan was ever recorded for a restart to preserve. |
| 7. Token sentinel holds | **Met on ratios, breached on the budget.** No collapse, no unbounded completion; but 35,827 > 32,768 declared. |

### Root did not create the three agents

This is my main disagreement with what a driver report is likely to claim, so
the evidence is laid out fully.

Root's attempts both failed:

- run `9c7fa70f` (09:40:35–09:41:37), ordinals 1–3, following the human's
  instruction to elide the connection:
  `(cluster/ensure-entity! "31475-1786181598529" {...})` →
  `Wrong number of args (2) passed to: seon.cluster/ensure-entity!` ×3.
  Call preparation did **not** supply the connection.
- run `63a30421` (~09:51:32), ordinals 2–4, after switching approach:
  `(cluster/ensure-entity! db/*conn* "31475-…" {...})` →
  `invalid-input: [[{:value nil, :message "must be a live unreleased Datahike
  connection from the calling cluster"}]]` ×3. `db/*conn*` is nil in the
  agent's context.

The agents appeared at **09:45:03**. Root's runs bracket that instant without
containing it: `9c7fa70f` closed at 09:41:37 and `b0f70394` opened at 09:45:05.
Root held no open run between 09:41:37 and 09:45:03, so no root form created
them. Note also that `63a30421`'s attempt came *after* the agents already
existed — root was still trying to create agents that were already there.

Two consequences the owner should weigh. First, the arc's step 1 as written was
not demonstrated: the delegation path an agent must actually walk is broken at
`ensure-entity!`, in both the eliding and explicit forms. Second, the human's
message contained instructions that do not match the system ("elide it — call
preparation supplies your cluster's"), so part of this is a task-authoring
defect rather than a runtime one — but the runtime's own contract error
(`db/*conn*` nil) is not.

### The bootstrap teaching plan still loses its lesson

The plan deliberately teaches by failure: it defines `largest` with `:any`
(correctly rejected — that rejection is the lesson, not a defect), redefines it
properly, then calls `(largest)` with no arguments to show an honest arity
error. In all four agents that last step reports:

```text
No such namespace: arc.inventory
```

The namespace demonstrably exists in the same run: ordinal 1
`(in-ns 'arc.inventory)` succeeded, ordinal 8's `defn` returned the var
`arc.inventory/largest`, and ordinals 9 and 11 both called `largest`
successfully. **The error message is false**, and the intended lesson is
replaced by a claim the agent can disprove from its own transcript.

Separately, placeholder substitution is now *partially* fixed: the three
runtime-created agents all got correct `(in-ns 'arc.inventory)` forms, while
`bootstrap:root` still carries the raw `(in-ns '{{seon.ns/name}})` and
`"{{seon.ns/name}}/largest"`. Exactly 2 of 114 recorded forms carry a
placeholder, both root's.

## Disagreements, stated plainly

1. **"The arc ran" would overstate it.** Stages 1's concurrency sub-goal is
   genuinely met and is excellent. Step 3 (contracted functions), step 5
   (canvas), and step 6 (restart) were not reached, and step 1's delegation was
   completed by something other than root.
2. **The restart should not be reported as fully passed.** Recovery of state
   and custody was genuinely clean and deserves credit. But
   `:seon.cluster.eval/interrupted-at` has zero datoms cluster-wide, so the
   "interrupted receipts honest" half of the crash model is unproven — the
   interrupted run is byte-for-byte indistinguishable from a normal prose-only
   close. If a driver report claims the crash model held, that is the clause to
   push back on.
3. **"Plans survive restart" has no evidence either way.** No durable plan fact
   was ever written; the `:seon.def/*` attributes for the agent's defs do not appear anywhere in
   the database, before or after the restart. There is nothing for a restart to
   preserve, so a successful-looking restart proves nothing about planning or
   memory.
4. **The token sentinel should not be reported as simply holding.** The ratios
   are healthy and that is real. The declared 32,768 budget was exceeded three
   times because the estimator is 23–26% low.
5. **I refute my own first impression that prompts grow without bound.** They
   grow within a session chain and reset on run completion (35,827 → 9,496).
6. **`/data` is not fixed.** The predecessor filed 5.5 s; it is now 6.4–6.5 s.

## Ugly output, verbatim

**A map with non-keyword keys crashes the MCP projection.** Minimal repro,
`(sorted-map "a" 1 "b" 2)`, returns a ~40-frame stack trace instead of a value:

```text
class java.lang.String cannot be cast to class clojure.lang.Keyword
  [clojure.lang.Keyword compareTo "Keyword.java" 124]
  [clojure.lang.PersistentTreeMap valAt "PersistentTreeMap.java" 297]
  [seon.cluster$evaluation_node invokeStatic "cluster.clj" 220]
  … 37 more frames …
  :phase :print-eval-result
```

`src/seon/cluster.clj:219-220` does `(string? (:seon.cluster.eval/result-edn
value))` on any map; a sorted map compares the keyword against its String keys
and throws. It cost me a probe and dumped 40 frames into my context.

**The MCP admission window still elides without saying what it dropped.** Two
fresh instances. A vector of 16 rows presented as five elements whose last is a
bare marker string:

```clojure
[{:ag "seon.sci.admit/elided", :run "seon.sci.admit/elided", :ord 0,
  :src "seon.sci.admit/elided", :seon.sci.admit/elided true}
 …
 "seon.sci.admit/elided"]
```

and a map whose keys were dropped wholesale:

```clojure
{"open" [], "max-tx" 536871081, "n-evals" 41, "held" [],
 "seon.sci.admit/elided" true}
```

Neither carries omitted count, known total, path, next offset, producing
profile, or requery identity — all of which the vocabulary table requires of an
elision value, and all of which the elision the *agent* receives does carry.

**Provider control markup reaches the database as agent source:**

```text
; <｜｜DSML｜｜AgentThoughts>We need respond to current instruction about core fault. Need inspect. Let's gather data first.</｜｜DSML｜｜AgentThoughts>
; <assistant1>
```

**`:seon.ai.attempt/usage-edn` stores bare string keys**, so the natural
keyword read returns nil silently:

```clojure
{"prompt_tokens" 16772, "completion_tokens" 1278, "total_tokens" 18050, …}
```

`(:prompt_tokens u)` → `nil`. This produced a full table of `nil` ratios that
looked like a real result until I checked the key types. It is the provider's
raw JSON shape stored unnormalized, against the fully-namespaced-keys rule.

## What is genuinely in good shape

Calibration, not alarm — these held under a load nothing had exercised before.

- **Concurrency itself.** Four agents, four simultaneous runs, 20–30 s of
  measured overlap, and nothing about custody, claiming, or context degraded.
  This is the arc's central question and the answer is yes.
- **Custody derived from one source.** 26 runs, every open run holding exactly
  one process, every closed run having shed it. No orphan, no double hold —
  including across a process replacement, where the dead JVM's pid was not left
  behind on any run.
- **Recovery of state across the restart.** Four agents, 105 forms, 102
  receipts, 22 messages, three `arc.*` namespaces still bound to their owners,
  no run left open, ready in 6.2 s. Nothing re-executed and nothing durable was
  lost. The gap is the interruption *marker*, not the recovery.
- **Message claiming.** Exactly once, in order, recipient always matching, and
  this time provably reaching the recipient's prompt.
- **Context isolation.** Zero foreign-namespace mentions across six sibling
  prompts. Whatever builds the walk is respecting agent boundaries.
- **The context defect is genuinely fixed.** From a 509-character error to a
  real REPL session with instructions, namespace, toolkit, and the human's
  message. Everything else in this report was only testable because of that.
- **Contract enforcement works and reads well.** The `:any` refusal names the
  function, the rule, and a concrete remedy. The `ensure-entity!` refusal names
  the exact argument and what it must be.
- **Error data is two orders of magnitude smaller** — 158 KB worst case against
  4.25 MB.
- **The web transport.** Byte-identical aliases, a 15 ms warm keyframe for a
  397 KB page, debug pages in ~30 ms, distinct per-namespace pages for the new
  agents, and an SSE feed sustaining 40 KB/s of morphs.
- **The diagnostics did the finding again.** The declaration-population counter
  named its own callers and priced `/data`; the durable context captures made
  the estimator drift measurable at all; `:seon.ai.attempt/settings-edn` is what
  exposed the thinking-disabled discrepancy. Cheap correct diagnosis is what
  made this report possible without touching process memory.

## Issues

New:

- [Bound the prompt by the provider's token count, not a chars/4 estimate](../../../seon/issues/prompt-token-budget-is-checked-against-a-25-percent-low-estimate.md) — blocker
- [Reuse one HttpClient so concurrent provider calls stop closing mid-read](../../../seon/issues/concurrent-provider-calls-fail-with-a-closed-response-body.md) — blocker
- [Mark the run a restart interrupted, so recovery is provable from facts](../../../seon/issues/recovery-closes-an-interrupted-run-without-marking-it.md) — blocker
- [Project an MCP value whose map keys are not keywords](../../../seon/issues/mcp-projection-crashes-on-non-keyword-map-keys.md) — friction

Filed independently by the drive lane on the same window, both confirmed by my
own evidence and cross-referenced rather than duplicated:

- [Settle what arrived when a provider stream closes mid-body](../../../seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md) — the recovery half of my HttpClient note
- [Install call preparation on the cluster's sci context](../../../seon/issues/call-preparation-is-never-installed-on-the-cluster-sci-context.md) — this is the cause of the `ensure-entity!` wrong-arity failures I observed

Both were missing `index.md` rows when I validated (`bin/issues-index --check`
failed on `missing-schedule-row` for each). I added their rows rather than leave
the authority invalid; the notes themselves are untouched and remain the drive
lane's. Final state: clean, 165 open, 1,008 archived.

Evidence appended to existing notes:

- [Settle a receipt for every recorded run form](../../../seon/issues/a-runs-last-form-can-close-without-a-receipt.md) — recurrence on a second cluster, plus the provider-control-token trigger
- [Name the arity when an agent calls its own function with the wrong one](../../../seon/issues/a-wrong-arity-call-reports-a-missing-namespace.md) — reproduced in all four agents on `default`, and it is the shipped bootstrap lesson
- [Resolve the declaration population once per admission, not once per node](../../../seon/issues/value-admission-resolves-the-declaration-population-per-node.md) — 14,583 resolutions in 28 min; `seon.print` now the dominant caller at 43%
- [Return `/data` without a five-second stall](../../../seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md) — regressed to 6.4–6.5 s, 927 resolutions per request
- [Substitute the bootstrap plan's namespace placeholder before it is evaluated](../../../seon/issues/bootstrap-plan-forms-ship-unsubstituted-namespace-placeholders.md) — narrowed to `bootstrap:root` only; runtime-created agents substitute correctly

I also drafted a fourth new note for the wrong-arity message before re-reading
`index.md` and finding it already owned by
[a-wrong-arity-call-reports-a-missing-namespace.md](../../../seon/issues/a-wrong-arity-call-reports-a-missing-namespace.md).
The duplicate was deleted and the evidence folded into the existing note.
