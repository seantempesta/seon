---
type: research
status: complete
tags: [research, runtime, testing]
---

# Tool exercise — the agent-facing capability surface under load

Owner-directed for the night of 2026-08-07: "tool testing… anything around
the JVM interactions and IO heavy or background tasks would be great for
finding bugs and making sure the system is fully working."

Read end to end before starting, as required:
[seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md), and
`AGENTS.md`'s "Loud failures, unrepresentable classes", "Transport law", and
vocabulary/toolkit rows. Surfaces read at source before driving them:
`src/my/fs.clj`, `src/my/web.clj`, `src/my/shell.clj`,
`src/my/background.clj`, `src/seon/effect.clj`, `src/seon/fs/jvm.clj`.

## Summary

Nine defects found, eight of them new, three of them blocking work outside
this lane. The single most consequential: **every background capability
request loses its connection on the `:io` hop**, so all eight concurrent
`my.background` submissions failed identically while the same command
succeeded in the foreground. That is the parallel isolation audit's Defect I
observed failing outright rather than surviving on a lucky fallback, on the
one path nobody had driven.

What IS sound, measured and worth saying: effect identity and receipt
provenance. Every receipt this lane produced — 22 of them across six runs,
including eight submitted concurrently from one form — carried its run
reference and a distinct ordered
`[run-id form-ordinal effect-ordinal]`, with no collision or
cross-contamination. `my.shell` handles a 40 MB stdout exactly as the
transport law says it should: bulk to the blob tier, the row carrying
digest, size, and a bounded preview, in 211 ms. `my.fs`'s symlink discipline
is correct in both `stat` and `glob`.

What is not sound is mostly the surface above the door: a capability
affordance that cannot do the thing its docstring promises, a capability
namespace agent code cannot resolve at all, an interruption path that
orphans both the child process and the receipt, two request shapes the
docstrings do not teach, and a declaration/registration split that lets any
lane's file save brick a running cluster with a 7 KB stack trace.

One area remains genuinely unexercised: `my.web`, because agent code cannot
reach it (finding 6). Its fixtures are built and waiting.

## How the exercises were driven

The MCP `door` mode is NOT a path to the capability door. It evaluates in the
cluster's shared ctx but creates no run, so `seon.effect/request!` refuses
before anything crosses:

```text
#:seon.error{:kind :seon.effect/no-evaluation-context,
             :message "Capability requests require a current run form.",
             :data {}}
```

Nor is calling the JVM handlers directly an exercise of the path the owner
asked about. So the harness opens a real SYSTEM RUN carrying
caller-supplied form sources — the same mechanism
`seon.cluster.curate/prove!` uses for a proof — and drives
`seon.cluster.loop/turn` until it closes. No model call is involved; the
sources ARE the agent's forms. The exercised path is therefore the full one:
sci eval → call preparation → effect door → `:io` executor → receipt →
settled value → committed facts.

Harness and raw results are preserved for the fix lanes at
[probes/tool-exercise/](probes/tool-exercise/) (`probe.clj`,
`exercises.clj`, and one `.edn` per exercise holding the complete drive
result — eval receipts and effect receipts).

Cluster: `tools`, in the isolated operator root
`tmp/tool-exercise-operator`, booted 22:33, `ready-ms` 6312.

## Exercise matrix and verdicts

| # | Exercise | Verdict | Note |
|---|----------|---------|------|
| 1 | `my.fs/stat` on an ordinary file | PASS | correct facts, 130 ms |
| 2 | `my.fs/stat` on a symbolic link | PASS | `:symbolic-link? true`; does not follow |
| 3 | `my.fs/glob` over a tree containing a symlink to an outside directory | PASS | returned `["escape" "inside.txt"]`, `examined 2`; did not descend the link |
| 4 | `my.fs/read`, whole 32 MiB file, over the 16 MiB ceiling | PASS (refuses) | correct refusal, but see #6 for what it omits |
| 5 | `my.fs/read`, 64-byte and 4096-byte WINDOWS of that file | **FAIL** | identical refusal — the window affordance cannot read a large file |
| 6 | the read-limit refusal's content | **FAIL** | names the ceiling only; no path, no observed size; contradicts its own declared `error/message` |
| 7 | `my.fs/write` × 5, written from the docstring | **FAIL (surface)** | all refused `missing required key`; door correctly never crossed |
| 8 | effect identity under a 5-form run | PASS | 5 distinct `[run-id form-ordinal effect-ordinal]`, all terminal |
| 9 | receipt provenance (environment carriage) | PASS | every receipt resolved its run; owner symbol, opened/settled instants, duration all present |
| 10 | run driving via `work/next-agent-work` | **FAIL** | required `:seon.cluster.work/now` that nothing reads; `curate/prove!` omits it |
| 11 | value admission after a sibling lane saved a schema resource | **FAIL** | cluster bricked; every eval returns a 7 KB stack trace |
| 12 | `bin/seon init` with one bad `[:fn]` declaration under `src/` | **FAIL** | no operator root anywhere can publish or boot |
| 13 | `my.shell/run` — echo, 3 s sleep, exit code 7 | PASS | exit codes and stdio digests correct |
| 14 | `my.shell/run` — 40,000,000-byte stdout | PASS | blob tier + bounded preview + digest; receipt 1,587 bytes; 211 ms |
| 15 | `my.shell/run` — interrupted at a 4 s time limit | **FAIL** | child orphaned, receipt left pending, recorder threw a contract violation |
| 16 | `my.shell/run` request shape from the docstring | **FAIL (surface)** | `:my.shell/cwd` required, docstring names no keys |
| 17 | `my.web/fetch` against a local server | **FAIL** | `Unable to resolve symbol: my.web/fetch` — unreachable from agent code |
| 18 | ctx resolvability of nine `my.*` functions | **FAIL** | only the two `my.web` functions do not resolve |
| 19 | `my.background` — 8 concurrent submissions, ordinal distinctness | PASS | 8 distinct ordinals `[run 0 0]`…`[run 0 7]`, all terminal |
| 20 | `my.background` — the submitted work itself | **FAIL** | all 8 failed: the handler's connection is `nil` on the `:io` hop |
| 21 | `my.background/poll` × 8 | PASS (works) / concern | ~290 tokens per polled result; 2,833 tokens for one poll of 8 |

## Findings

### 1. `my.fs/read` cannot read a window of a large file (blocker)

[Issue](../../../seon/issues/my-fs-read-refuses-a-bounded-window-of-a-large-file.md).
`src/seon/fs/jvm.clj:239-265` compares the ceiling against the WHOLE FILE
because the result promises a whole-file digest, so `:my.fs/byte-offset` and
`:my.fs/max-bytes` only select bytes out of a stream they never bound. A
64-byte window of a 33,554,432-byte file is refused exactly like the whole
read, and pays 125 ms streaming 16 MiB before giving up. The docstring's own
stated use — "when a whole file may be too large" — is the case that fails.

Verbatim, three times over, for three different windows:

```text
#:seon.error{:kind :my.fs/read-limit,
             :message "The file exceeds the configured read ceiling.",
             :data #:seon.config.fs{:max-read-bytes 16777216}}
```

The refusal is also thinner than its own declaration: `my.fs.edn:133` says
the error "must identify the path whose read exceeded its limit", and no
path is present in the schema or in the value.

### 2. `next-agent-work` requires a `now` nothing reads (blocker)

[Issue](../../../seon/issues/next-agent-work-requires-a-now-it-never-reads.md).
`:seon.cluster.work/agent-request` requires `:seon.cluster.work/now`, which
appears nowhere in `src/seon/cluster/work.clj`. A required argument no code
reads can only be forgotten — and `seon.cluster.curate/execute-revision!`
(`curate.clj:131-141`) forgets it, so every session-curation proof throws a
contract violation that `prove!`'s `catch Throwable` reports as an opaque
`::proof-fault`. This is the ethos rule inverted: the fix is to delete the
key, not to add a checker.

### 3. A schema-resource edit bricks value admission in a running cluster (blocker)

[Issue](../../../seon/issues/a-schema-resource-edit-bricks-value-admission-in-every-running-cluster.md).
Declarations are re-read from the classpath at runtime (`schema.clj:728-734`
says so in its own warning: "reads every schema resource on the classpath");
the predicates they name are registered at namespace load. At 22:43 a
sibling lane saved `resources/seon/schemas/seon.flow.edn` plus its matching
`register-core-predicate!` in `src/seon/flow.clj`. The running cluster took
the declaration and not the registration:

```clojure
(pr-str {:has-step-var? (seon.schema/core-predicate-registered?
                         'seon.flow/step-var?)
         :flow-loaded? (some? (find-ns 'seon.flow))})
⟹ "{:has-step-var? false, :flow-loaded? true}"
```

From that moment every `eval_clj` in that cluster failed — including calls
whose value was a plain `String`, because the envelope map goes through the
same projection. Nothing in `bin/seon status` or `runtime_status` reported a
problem; the cluster looked alive and simply could not project a value.

This is the live cost of the load-time registration sentinels the seon.env
PRD already schedules for deletion. It belongs in that PRD's acceptance set,
because it converts "tidy-up" into "any lane can silently brick every other
lane's cluster".

### 4. An inline `[:fn]` predicate under `src/` blocks the whole repository (blocker, already filed)

[Issue](../../../seon/issues/an-inline-fn-predicate-in-src-refuses-every-corpus-projection.md),
opened by the render-proc lane; this lane added the second half of its blast
radius. It does not only redden tests — it refuses EVERY publication, so
`bin/seon init` fails in any operator root and no fresh cluster can be
booted at all. It cost this lane its first 30 minutes. (The owning lane
resolved the declaration independently at 22:39.)

### 5. Every background capability request loses its connection (blocker)

[Issue](../../../seon/issues/every-background-capability-request-loses-its-connection.md).
The most consequential finding of the night. `seon.effect/dispatch` wraps
the foreground handler in `bound-fn`, so `*request-context*` reaches the
`:io` executor; the background arm hands `flow/submit!` a plain closure, and
flow conveys no bindings by design. `seon.shell.jvm:290` reads the
connection from that dynamic var, so it gets `nil`:

```text
seon.blob/stage-binary! violated its contract (invalid-input):
[[{:value nil, :message "must be a live unreleased Datahike connection from
the calling cluster"}]]
```

Eight for eight, on 7-byte stdouts, while the identical command in the
foreground staged 40 MB correctly. This is the audit's Defect I with no
lucky fallback to hide behind, and it is exactly what the flow-carriage
sequencing constraint warned about: while conveyance remains, a forgotten
environment is invisible on `:compute` and fatal on `:io`.

### 6. `my.web` is unreachable from agent code (blocker)

[Issue](../../../seon/issues/my-web-is-unreachable-from-agent-code.md).
`my.web/fetch` and `my.web/search` are in the program graph, public,
contracted, with capability owners and a namespace row, and the host loads
them fine — but they are the only two agent-facing capability functions the
SCI context cannot resolve. Nine symbols tested in one agent form; the two
`my.web` entries are the only `false`. This is why the my.web exercises
(deadline crossing, body ceiling) are unrun: there is no way to call them.

### 7. An interrupted `my.shell/run` orphans its child and its receipt (blocker)

[Issue](../../../seon/issues/an-interrupted-my-shell-run-orphans-its-child-and-its-receipt.md).
Three failures in one event: `sleep 300` was still running 29 s after its
run was cut at a 4 s limit; the effect receipt was left permanently
`:pending` with neither result nor `interrupted-at`; and the eval receipt
recorded a contract violation from `seon.problems/form-problem` (four
required keys missing) instead of the interruption. So the interruption path
leaks a process, leaks a receipt, and misreports itself.

### 8. `my.fs/write`'s docstring teaches a shape it refuses (friction)

[Issue](../../../seon/issues/my-fs-write-docstring-hides-its-own-request-shape.md).
The docstring names no keys and reads as flat; the request is nested under
`:my.fs/content` and `:my.fs/precondition`. The sibling `my.fs/read` in the
same namespace IS flat, so success with `read` actively mis-teaches `write`.
Five forms written from the docstring produced five contract violations and
zero effect receipts.

### 9. `my.shell/run` has the same problem (friction, same issue)

Recorded on the same note. Its docstring — "Run one foreground argv vector
and return complete process evidence" — names no keys at all, and
`:my.shell/cwd` is REQUIRED. Four forms written from the docstring were
refused with `missing required key`, again with zero effect receipts. Two of
the four capability namespaces exercised tonight refuse the call an agent
writes from their own docstring.

## Measurements

Taken from committed receipts on an otherwise-loaded machine (several
sibling lanes hammering the same box — treat these as an upper band, and the
per-form figure as the one worth attention):

- **per-form turn cost ≈ 2.4 s.** Successive effect `opened-at` instants in
  one 5-form run: 02:41:46.233, 48.660, 51.143, 54.490, 55.848 — gaps of
  2.43 s, 2.48 s, 3.35 s, 1.36 s. A 5-form run took 11.7 s wall and a 6-form
  run 9.9 s, essentially all of it between forms rather than in the work.
- **per-effect door cost ≈ 110–130 ms**, largely independent of the work:
  `my.fs/stat` on one file cost 131 ms; `my.fs/glob` over a 2-entry
  directory cost 120 ms; a `my.fs/read` that refused after streaming 16 MiB
  cost 125 ms. Two transactions (open + settle), a `config/effective`, and
  two admissions per request dominate.
- **cluster boot** `ready-ms` 6312; `bin/seon start` wall 72 s including the
  dependency-cache rebuild; complete `bin/seon init` 101 s.
- **agent creation** 3.0–5.3 s each, dominated by its bootstrap run.
- **`my.shell/run` with 40 MB of stdout: 211 ms**, staged to the blob tier,
  receipt row 1,587 bytes. Compare `my.fs/read`, which cannot read 64 bytes
  out of a 32 MB file at all — the two capabilities disagree about how to
  handle large payloads, and shell's answer is the right one.
- **`my.background/poll` costs ~290 estimated tokens per polled result.**
  One poll of eight refs returned 2,833 tokens (11,334 characters) while
  pending and 2,318 tokens (13,872 characters) once settled, because each
  descriptor carries the full `:seon.effect/request-edn` and
  `:seon.effect/result-edn`. An agent that fans out eight background jobs
  and polls twice has spent over 5,000 tokens on bookkeeping. Flagged under
  the standing token-size order; producer is `my.background/poll`'s
  selector plus the absence of a declared `:seon.render/ai` for the receipt
  descriptor.
- **A contract-violation eval result costs 480–820 estimated tokens**
  (1,921–3,266 characters) because the Malli explanation is re-encoded
  through the print faces. `Unable to resolve symbol: my.web/fetch` — six
  words — was rendered as a 2,154-character result.

## Ugly output (the dogfood list, verbatim)

1. **A ~7,000-character Java stack trace as an eval value.** The worst face
   met all night, and it repeats on every subsequent call. Shape:
   `{:via [...] :trace [[seon.schema$bind_predicates$fn__513 invoke
   "schema.clj" 159] … ~120 frames …] :cause "Predicate seon.flow/step-var?
   has no admitted callable in the corpus projection." :data {…} :phase
   :print-eval-result}`. The one useful sentence is in `:cause`, after the
   frames. Every frame names `seon.schema`/`seon.sci.admit` — the machinery,
   never the schema resource or the namespace whose registration is missing.
   Owner: `seon.sci.admit/admit-value` must return a flat error;
   `seon.cluster/mcp-project` must never render a throwable's `:trace`.

2. **`✗ class clojure.lang.LazySeq cannot be cast to class clojure.lang.IFn
   (clojure.lang.LazySeq and clojure.lang.IFn are in unnamed module of
   loader 'app')`** — the entire output of a failed `bin/seon init`. No
   layer, no schema key, no file, no caller. A bare JVM cast message is not
   a refusal; boot refusals elsewhere in the same command name their layer
   properly, so this one has simply escaped the refusal path. Owner: the
   publication path in `script/seon/fresh_operator.clj`. Adjacent open
   issues that do not cover this case (they concern refusals that DO reach
   the refusal path):
   [boot-refusal-has-no-render-producer](../../../seon/issues/boot-refusal-has-no-render-producer.md),
   [init-failure-dumps-entire-prepl-event-history](../../../seon/issues/init-failure-dumps-entire-prepl-event-history.md).

3. **`✗ Attempting to call unbound fn: #'seon.schema/core-predicate-functions`**
   — same class, same command, also unattributed.

4. **The declaration-population fallback wall.** Six to eight lines of
   `seon.schema: DECLARATION POPULATION FALLBACK ×N — <ns> (<file>:<line>)`
   attached to nearly every `eval_clj` result all night, from
   `seon.schema.datahike`, `seon.sci.admit`, `seon.instrument`,
   `seon.print`, and `seon.cluster.loop`. Already known and being worked;
   recording the volume as an independent observation, because in this
   lane's transcript it was consistently longer than the values it
   accompanied.

5. **`record unreadable /…/claims/roots/<uuid>.edn: The external claim is
   invalid.` × 8** in ordinary `bin/seon status` output. Eight lines of
   noise about stale claim files in the middle of the one command an
   operator reads most. Already filed as
   [status-floods-unreadable-external-claim-warnings](../../../seon/issues/status-floods-unreadable-external-claim-warnings.md);
   independently confirmed still present tonight.

6. **`seon.cluster.run/refused` is well-shaped but silent about the fix.**
   `#:seon.error{:kind :seon.cluster.run/refused,
   :seon.cluster.run/rule :seon.cluster.run/agent-already-running, …}` is
   readable and names the rule — good. It does not name the run that holds
   the agent, which is the fact the caller needs.

## What a follow-up lane should pick up

The harness is ready and every fixture is built.

1. Boot `tmp/tool-exercise-operator` / cluster `tools`, `load-file` both
   files from [probes/tool-exercise/](probes/tool-exercise/), and call
   `(tool-exercise.probe/ensure-agent! "exN")` once per agent.
   `probe/drive!` takes `:time-limit-ms`, which is how the interruption
   exercises get a 4 s limit instead of the cluster's 30 s.
2. The `my.web` exercises are BLOCKED on finding 6, not on setup:
   `exercises.clj`'s `start-server!` already serves `/small`, `/slow`
   (45 s, past the default eval limit) and `/huge` (a 4 MB body). Run them
   the moment `my.web` resolves — deadline crossing and body-ceiling
   behaviour are the two open questions.
3. Still unanswered after tonight, all cheap once the above lands: whether
   an atomic `my.fs/write` interrupted mid-flight leaves a torn file
   (`exercises.clj/scratch-listing` is the oracle); what `my.background`
   does to in-flight work when the turn settles and when the cluster stops
   (the transport law's "channels losable, facts durable" claim, currently
   untested); and whether concurrent writes to distinct files from parallel
   background submissions interfere — all three were gated behind finding 5,
   since no background work completes at all today.

## Honest notes on conditions

- The shared tree was under heavy concurrent edit all evening. Publication
  was broken by three different foreign in-flight states in ninety minutes
  (the inline `[:fn]` predicate, a `seon.flow` edit reverted mid-flight, and
  a `seon.db` edit that left initialization pages pointing at
  `seon.db/supplied-database-value` and `seon.db/supplied-connection`). Each
  is normal weather; together they cost this lane the second half of its
  matrix. None of them is counted as a defect against the tools.
- One trivial test-support edit was made and then superseded: with no lane
  running and every publication in the repository refused, this lane
  registered the offending predicate in `src/seon/test/selection.clj` to
  unblock itself; the owning lane independently made the function private a
  few minutes later, which is the better fix and is what is in the tree.
  Nothing else under `src/` was touched.
