---
type: research
status: active
tags: [research, mcp, tooling]
---

# MCP toolset audit — the model's experience of `bin/mcp-server`

Audited 2026-08-01 against the live `default` cluster (pid 35130, prepl
`127.0.0.1:63855`, url `http://127.0.0.1:7994`) on branch
`codex/runtime-reliability-refactor`. Every defect below was reproduced
through the advertised MCP tools from an agent harness, not inferred from
source. The server is `bin/mcp-server` → `script/seon/dev/mcp.clj` (623
lines), registered identically by `.mcp.json:2-8` and
`.codex/config.toml:3-4`.

The bridge's architecture is right and small: a babashka process that never
loads `src/` or `src-old/` (`script/seon/dev/mcp.clj:11-14`, proven by
`test/seon/dev/mcp_bridge_test.clj:57,66`), rediscovering clusters on every
call so restarts need no bridge restart. Everything wrong here is a
PROJECTION defect — the truth reaches the model, buried.

## Tool inventory

Three tools are advertised (`script/seon/dev/mcp.clj:502-519`).

| Tool | Purpose | Schema quality | Output quality | Verdict |
|---|---|---|---|---|
| `eval_clj` | Evaluate ONE form through the selected cluster's `io-prepl` (`:503-511`) | **Weak.** `code` — the one required property — carries NO description (`:506`). `max_output_tokens` is in the schema but absent from the description, so a model that hits truncation cannot discover the dial from the tool card. `cluster` and `session_id` descriptions are good and accurate. | **Poor on the error path, good on the success path.** Success returns `{runtime, cluster, session-id, events}` with the prepl's own `tag/val/ns/ms/form` — correct vocabulary, correct grain. Failure returns the raw `Throwable->map` (see D1). | **Keep, fix the error projection and the `code` description.** |
| `runtime_status` | "Report fresh JVM clusters from advertisement files and the current bridge session count" (`:517-518`) | Fine — no inputs. | **Poor.** 17 rows, 16 of them `state=missing`, one alive, alphabetically sorted so the live row lands third (see D5). One row (`store`) is not a cluster at all. | **Keep, fix the projection.** |
| `list_sessions` | "List the bridge's active CLJ io-prepl sessions" (`:513-515`) | Fine — no inputs. | Returns one prose line: `CLJ io-prepl sessions: default/dbsurface, default/default` (`:464-473`). Truthful and small. | **Fold into `runtime_status`.** It answers a strict superset of what `runtime_status` already prints (`:500` prints the count, this prints the names). Two tools for one fact costs every model a tool-selection decision it should not have to make. |

Nothing else is advertised; the dead CLJS tools were already removed
(`dece13518`, guarded by `mcp_bridge_test.clj:94,103`). That guard is
working — no drift found.

## Defect evidence

### D1 — an evaluation error returns 40 frames of `clojure.lang.Compiler` around a 64-character cause

Reproduced: `eval_clj {code: "(nonexistent-symbol-xyz 1)"}` returns
`isError` with a single `:ret` event whose `:val` is the pr-str of
`Throwable->map`. Measured in the same session:

```clojure
{:total 2690, :cause-len 64, :frames 40, :first-party 0}
```

2,690 characters carrying 64 characters of signal — 97.6% noise, and NOT
ONE of the 40 frames is first-party. A REPL prints the cause and a handful
of frames; this envelope prints everything `clojure.core.server/prepl`
handed it.

**Site:** `script/seon/dev/mcp.clj:436-444`. `collect-prepl-response!`
returns events verbatim; `execute-clj-eval` inspects only
`(:exception terminal)` to choose `mcp-error`, and never touches `:val`.
The bridge already parses each event as EDN (`:383`), so the exception map
is structured data in hand at the moment it is thrown away.

This is the ONLY place the bridge sees an exception, and it is the one seam
where the projection belongs.

### D2 — a contract violation's message grows with the offending value, bypassing the cap that exists three lines below it

Reproduced against a live schema'd boundary:

```clojure
(seon.render/call-with-walk-context (seon.schema/snapshot) (seon.schema/snapshot))
;; ex-data:            27,662 chars
;; :seon.error/message 25,915 chars   ← 94% of the payload
;; ::schema               408 chars
```

The message begins usefully and then never stops:

```
seon.render/call-with-walk-context violated its contract (invalid-input):
[{:seon.sci.eval/evaluation ["disallowed key"],
  :seon.cluster.prompt/request ["disallowed key"],
  :seon.schema/projection ["disallowed key"], …
```

`me/humanize` on a closed map emits one entry PER KEY of the offending
value, so the message length is linear in the argument. The owner's
observed "every registered function contract" is exactly this: the argument
happened to be the schema snapshot.

**Site:** `src/seon/instrument.clj:156-160` —

```clojure
:seon.error/message
(str (:fn-name data) " violated its contract (" (name kind) "): "
     (pr-str (me/humanize (m/explain offended value))))
```

`value` is unbounded and enters the message with a bare `pr-str`. The fix
is already present in the same function for a different key: three lines
below (`:168-176`) the SAME `value` is put through `admit/admit` with the
projection caps before landing in `::args`, with a docstring explaining
precisely why ("a description that can hang is worse than no
description"). The cap was applied to the data and skipped on the message.

**`c6db32f56` does NOT cover this.** That commit added the
`:malli.core/invalid-arity` arm (`instrument.clj:134-146`), which builds
its message from the arity and the function symbol and is genuinely small
(measured: `seon.fn/plan-file-change` bad-map violation = 685 chars total).
The non-arity arms — `invalid-input` and `invalid-output` — still embed the
unbounded humanize. Verified by reproduction above, after `apply!` with
`:panic`.

This value reaches agents too, not only the MCP: `seon.sci.eval`'s
`failure-value` merges the refusal through unchanged
(`src/seon/sci/eval.clj:386-388`).

### D3 — truncation destroys the envelope instead of trimming it, and the two caps are set to collide

Reproduced with `max_output_tokens: 64`:

```json
{"seon.dev.mcp/truncated?":true,
 "seon.dev.mcp/preview":"{\"seon.dev.mcp/runtime\":\"clj\",…\"val\":\"\\\"0123456789…01234567\n… output truncated by MCP bridge"}
```

Three distinct problems, all at `script/seon/dev/mcp.clj:47-64`:

1. **The structured envelope is discarded.** `content-text` (`:58-64`)
   detects an over-limit encoding and replaces the whole map with a
   two-key map holding a JSON string. The model loses `:tag`, `:ns`, `:ms`,
   and the exception flag, and receives doubly-escaped JSON inside JSON.
2. **The cut is at a raw character index** (`:52`), so the preview ends
   mid-token and is not readable EDN or JSON.
3. **The two caps are the same number, which guarantees the fallback
   fires.** `bounded-event` (`:363-371`) already trims each `:val` to
   `transport-char-limit`, then `content-text` re-checks the JSON
   *encoding* of the whole envelope against the *same* limit (`:60`).
   Escaping plus envelope keys always add length, so any result that
   approaches the cap loses its structure by construction. The per-event
   cap and the payload cap must not be equal.

There is also no follow-up surface: `bounded-event` sets
`:seon.dev.mcp/truncated?` (`:369`) and `content-text` sets it again
(`:63`) with no retained handle, offset, or total size — the model cannot
tell how much it lost or ask for more.

### D4 — the multi-form refusal is correct but positionless

Reproduced: `eval_clj {code: "(+ 1 2) (+ 3 4)"}` →

```json
{"seon.dev.mcp/failure":"multiple-forms",
 "seon.dev.mcp/error":"CLJ evaluation accepts exactly one form; wrap sequences in `(do ...)`."}
```

The contract and the remedy are both right. What is missing is WHERE:
`require-single-clj-form!` (`script/seon/dev/mcp.clj:332-361`) reads with a
plain `PushbackReader` (`:339`), which tracks no position, so the refusal
cannot say the second form started at 1:9 or echo `(+ 3 4)`. For a
multi-hundred-line paste — the case where this actually bites — the model
has to re-read its own input to find the split. `LineNumberingPushbackReader`
supplies line/column for free at the same call site. Same gap on the
`:invalid-form` arm (`:355,359`): the underlying reader exception is passed
as a `cause` that never reaches the envelope, which carries only
`(ex-message throwable)` (`:427`).

### D5 — `runtime_status` is 94% dead rows, sorted so the live one is buried, and one row is not a cluster

Reproduced verbatim: 17 rows, 16 `state=missing`, 1 `state=alive`,
`default` third alphabetically. Two separate causes:

1. **No liveness ordering or collapsing.** `advertisement-rows`
   (`script/seon/dev/mcp.clj:187-203`) maps every directory to a row and
   sorts by `:seon.dev.mcp/cluster` (`:197`); `execute-runtime-status`
   (`:492-500`) joins them all. The pid/start-instant liveness check
   already exists and is correct (`matching-live-process?`, `:131-138`) —
   its answer is simply not used for ordering or filtering.
2. **The rows are not stale advertisements — they are cluster DATA
   directories with no advertisement at all.** Confirmed on disk: only
   `data/clusters/default/prepl.edn` exists. `read-advertisement` returns
   `:missing` when the file is absent (`:156-159`). So these are past
   clusters' persisted branches, which is normal and expected, and
   reporting each one as a line item is pure noise. (`:stale` — a live-file
   advertisement whose pid is dead, `:170-174` — is the genuinely
   interesting state and did not occur here.)
3. **`store` is listed as a cluster.** `data/clusters/store` is the
   PROCESS-ROOT Datahike store (`CLAUDE.md`, boot sequence step 2), not a
   cluster. `valid-cluster?` (`:106-109`) is a name-shape check only, so
   the store directory passes and is reported with `state=missing`.

Related residue at the same seam: a `:missing` advertisement falls back to
`old-writer-endpoint` (`:269-271`), which reads
`tmp/seon-writer-repl-port-<cluster>` from the deleted `src-old` writer
(`:205-228`). Nothing in the fresh system writes that file.

## Ranked fixes

Ordered by model-experience damage per unit of work.

### 1. Project the exception at the bridge — `eval_clj`

**Seam:** MCP-server-side, `script/seon/dev/mcp.clj:436-444`.

When `(:exception terminal)`, parse the already-EDN `:val` and replace it
with a flat value: `:cause`, `:phase`, the `:via` type chain, `:data`, the
deepest N frames with first-party (`seon.*`, `my.*`) frames retained
preferentially, and `:frames-omitted n`. Keep the prepl's own event
vocabulary around it — `tag`/`val`/`ns`/`ms`/`form` are
`clojure.core.server`'s names and must not be renamed; the projected keys
are ours and are namespaced `:seon.dev.mcp/*` like every other key the
bridge adds.

**Acceptance:** `(nonexistent-symbol-xyz 1)` returns under 400 characters
with the cause as the first field, and a frame count stating what was
dropped. A first-party failure (a throw from a `seon.*` fn) shows the
`seon.*` frames.

### 2. Cap the contract-violation message through the caps that already exist

**Seam:** SHARED, `src/seon/instrument.clj:156-160`. Not an MCP fix — it
lands on the agent-facing error value too (`src/seon/sci/eval.clj:386-388`).

Build the message from a BOUNDED explanation: take the first N problems
from `(m/explain offended value)` and state the count of the rest, rather
than `pr-str`-ing the full humanize. If the humanized value is retained at
all, route it through the same `admit/admit` call already used for
`::args` (`:168-176`) instead of adding a second cap.

**Acceptance:** the reproduction above
(`call-with-walk-context` with the schema snapshot) yields a message of
bounded length independent of the argument size, naming the function, the
arm, and the first problems; `bin/test seon.sci.eval-instrumentation-test`
stays green.

### 3. Make `runtime_status` lead with the live system

**Seam:** MCP-server-side, `script/seon/dev/mcp.clj:187-203, 492-500`.

Sort `:alive` first (the check at `:131-138` already computes it), exclude
the process-root `store` directory by deriving it from the store path the
boot sequence owns rather than by name-matching, and collapse
advertisement-less directories into one summary line ("12 clusters with no
advertisement: …") while keeping `:stale`, `:invalid`, and `:unreadable`
rows individually visible — those are real anomalies. Fold
`list_sessions`' names into the existing session line (`:500`) and delete
the tool (`:513-515, 532`), updating `mcp_bridge_test.clj:94`.

**Acceptance:** the live `default` row is line one; the current 17-line
output is ≤5 lines; a deliberately stale advertisement (kill a cluster's
pid without removing `prepl.edn`) still appears as its own flagged row.

### 4. Never destroy the envelope on truncation

**Seam:** MCP-server-side, `script/seon/dev/mcp.clj:47-64, 363-371,
395-408`.

Give the per-event cap a fraction of the payload budget so the JSON
envelope always fits, and on overflow trim the `:val` strings in place
while keeping every envelope key. Report `:seon.dev.mcp/retained-chars`
and `:seon.dev.mcp/total-chars` so the model knows what it lost.

**Do NOT add a paged-retrieval tool.** That requires the bridge to retain
results across calls — new state in a process whose whole virtue is that it
holds none. The model already has two better levers, and the fix is to
SURFACE them: `max_output_tokens` (up to 16,000) and narrowing the form
itself. Say both in the `eval_clj` description.

**Acceptance:** the `max_output_tokens: 64` reproduction returns a
well-formed envelope with `tag`, `ns`, `ms`, `form` intact and a trimmed
`:val`, never a `preview` string.

### 5. Give the single-form refusal a position

**Seam:** MCP-server-side, `script/seon/dev/mcp.clj:332-361`.

Read through `LineNumberingPushbackReader`; on `:multiple-forms` report the
line/column at which the second form began and its first ~60 characters.
On `:invalid-form`, carry the reader exception's own message into the
envelope instead of dropping it as a bare cause.

**Acceptance:** `"(+ 1 2)\n(+ 3 4)"` names line 2 column 1 and echoes
`(+ 3 4)`.

### 6. Describe `code`, and delete the `src-old` writer fallback

**Seam:** MCP-server-side, `script/seon/dev/mcp.clj:506` and `:205-228,
269-271`.

`code` needs one sentence saying it is exactly one form, that the session
retains `*1`/`*2`, and that oversized results should be narrowed or given a
larger `max_output_tokens`. The `old-writer-endpoint` fallback reads a port
file no fresh code writes; delete it so a `:missing` advertisement produces
the accurate "start the cluster" error (`:230-249`) instead of a
mystery connection attempt.

## What is genuinely in good shape

Calibration, not alarm — most of this bridge is right, and several of its
choices are the ones the repository rules ask for:

- **Discovery on every call** (`:11-14`) means a running MCP client
  observes cluster starts, stops, and replacements with no restart. This is
  the resiliency rule implemented, not documented.
- **Liveness is (pid, start-instant), not a flag** (`:119-138`). A recycled
  pid cannot masquerade as the old process. The check is correct; only its
  consumers are.
- **The process is below the application** and provably so — two tests
  assert it loads with only the tooling classpath and requires no
  application namespace (`mcp_bridge_test.clj:57,66`). This is why a broken
  `src/` still leaves the model able to diagnose.
- **The prepl's vocabulary is preserved.** `tag`, `val`, `ns`, `ms`, `form`
  are `clojure.core.server`'s own names and are passed through unrenamed;
  everything the bridge adds is namespaced `:seon.dev.mcp/*`. That is the
  naming rule followed exactly, and the fixes above must not break it.
- **Errors are already flat values, not throwables**, at the MCP boundary
  (`mcp-error`, `:90-97`) — the remaining defect is the size of one field,
  not the shape.
- **The session-lost error is a model-legible refusal**: it names the
  cluster, the session id, and `:seon.dev.mcp/retry-with-new-session true`
  (`:322-327`), so a model can recover without guessing. Likewise
  `endpoint-error` carries `:seon.dev.mcp/remedy` with the literal command
  (`:230-249`). These are the standard the error projections in D1/D2
  should be held to — the bridge already knows how.
- **The single-form contract itself is right.** One form per call keeps the
  prepl event stream unambiguous; D4 asks only for a better refusal, never
  for relaxing it.
- **The parent watchdog** (`:594-607`) means no orphaned bridge processes
  accumulate across harness restarts.
- **Deleted tools stay deleted**, guarded by a test that fails if an
  unknown name is silently accepted (`mcp_bridge_test.clj:103`).

No advertised tool is obsolete. The only tool-level change recommended is
folding `list_sessions` into `runtime_status`; no new tool is justified.

## Landing status

- **Fix 1 landed** in `a99001ecb`. The many-key contract message fell from
  25,915 characters to about 220 while retaining the function, violation arm,
  first three problems, and omitted count; 11 tests / 58 assertions passed.
- **Fix 2 landed** in `560e3d603`. The unresolved-symbol `:val` fell from 2,342 characters /
  33 raw frames to 362 characters / zero retained first-party frames plus an
  omitted count of 33. A first-party contract failure retained three
  `seon.*`/`user` frames and omitted 23.
- **Fix 3 landed** in `a8c30e21f`. The live repository status fell from 19 lines to four:
  the alive row leads, the process-root store is absent, and 16 dormant
  cluster directories occupy one named summary line. Stale, invalid, and
  unreadable advertisements remain individual rows.
- **Fix 4 landed** in `43aab84c0`. At `max_output_tokens: 64`, the 371-character preview-only
  fallback became a 304-character structured envelope retaining runtime,
  cluster, session, `tag`, `ns`, `ms`, and `form`. Its 1,002-character `:val`
  reports zero retained / 1,002 total rather than double-escaped preview text.
- **Fix 5 landed** in `f12679974`. The newline-separated two-form refusal grew from 233
  positionless characters to 365 useful characters naming line 2, column 1,
  and preview `(+ 3 4)` while retaining the `(do ...)` remedy. The same
  handler path passed in both the JVM and the Babashka server process.
