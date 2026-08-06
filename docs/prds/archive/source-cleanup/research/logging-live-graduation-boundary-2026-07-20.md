---
type: research
status: complete
tags: [research, prd, architecture, testing]
---

# Logging live-graduation boundary (2026-07-20)

## Decision

Stage 3 is source-complete but not live-complete. Commit `51f28046` owns the
writer-side source and regression test. The earlier B2 commits `b109266e` and
`2cbd1892` own the client-side routing into `seon.log`. What remains is one
coordinated observation against a single frozen artifact:

- one current client line and one current writer line both satisfy the same
  byte-shape predicate;
- a deliberately triggered `seon.agent.loop` failure is returned as an error
  value and is then readable through `seon.log/tail`; and
- obsolete top-level probe output is removed from `logs/` without deleting a
  managed-process log, an active structured event file, retained Inspect
  evidence, or another lane's artifact.

None of those observations authorizes more logging source work. The protected
turn/canvas value-to-throw-to-value sites remain deferred to their owning
natural touches exactly as ruled in [[../logging-unification]].

Audit baseline: repository HEAD `c932c9e19c40a8a439f296b92ba265bd19bc9c08`.
The checkout had concurrent tracked host/database edits, so no test, build,
restart, log deletion, or live probe was run by this documentation lane.

## Dependency ledger

| Contract | Selected source | Load-bearing fact |
|---|---|---|
| Client console line | `src/seon/log.cljs` (`console!`, `log!`, `tail`) | `console!` emits `ISO timestamp`, two spaces, a five-character padded uppercase level, bracketed source, and body. `log!` writes the same event to the configured NDJSON-EDN file; `tail` reparses that active file newest-first. |
| Loop fault producer | `src/seon/agent/loop.cljs` (`await-bounded`) | A per-turn timeout calls `seon.log/error!` with source `:seon.agent.loop/step-bound` and returns `{:seon.error/message ...}` rather than throwing. |
| Writer console line | `src/seon/db/server.clj` (`writer-log-output`, `configure-logging!`, `start!`) at `51f28046` | The writer pads the level to five characters, emits the same timestamp/source/body structure, guards each value, and has a whole-formatter fallback. Boot, readiness, and shutdown messages all use Timbre. |
| Timbre behavior | `com.taoensso/timbre` 6.5.0 in `deps.edn`; vendored source `reference-code/timbre` at `b72cc65290cf2e5136cfa0a3dd449eb806e31ff5` | `protected-fn` rethrows output-function failures (`src/taoensso/timbre.cljc:430-446`); synchronous appenders may throw into their caller (`:589-594`); the default argument formatter prints without the required outer call-site containment (`:856-862`). This is why both writer guards are part of the contract. |
| Managed log ownership | `script/seon/dev/process.clj` (`log-directory`, `log-file`, `current-log`) and `script/seon/dev/cli.clj` (`logs!`) | Each managed process publishes its current generation log path. The operator retains up to ten generation logs per process. Cleanup must derive live ownership from these records, not from filename guesses. |
| Current default log root | `script/seon/dev/config.clj` and `src/seon/client.cljs` | The launch descriptor supplies `:seon.launch/log-dir`; the client places `pod-events.log` in that directory. At the audit baseline the default is `logs/operator`, not the old top-level `logs/pod-events.log`. Stage 2 will rename the client identity and event filename, so the frozen descriptor is authoritative. |

## What is already complete

Commit `51f28046` vendors the exact Timbre source, installs the guarded writer
output function, and replaces every bare `[database]` print in
`seon.db.server` with Timbre. Its focused writer proof includes a value whose
`print-method` throws, verifies two lines are still emitted, verifies the call
site returns normally, and directly exercises the whole-formatter fallback.
The recorded gates are 8 tests / 47 assertions focused and 232 tests / 1,896
assertions for the then-current complete writer suite.

The client routing defect has already been fixed and archived in
[[../../../seon/issues/archive/pod-failure-reports-bypassed-seon-log]].
`b109266e` routes the agent-loop and other client failure sites through
`seon.log`; `2cbd1892` routes database-listener failures. The archived note
already carries source/test and file-emission evidence. Stage 3 must not reopen
or duplicate that note merely because the final paired observation is newer.

Those earlier suite counts are implementation evidence, not the required
frozen live evidence. They also predate later source movement and cannot count
as either final consecutive full-program suite pass.

## Freeze boundary

The proof counts only when all of the following are true:

1. Every source-editing lane is paused and has handed off its owned paths.
2. `git status --short` has no tracked source, test, config, dependency, or
   documentation edit that participates in the artifact. Ignored reproducible
   caches may remain but are not evidence.
3. The exact `git rev-parse HEAD`, Timbre gitlink, selected `deps.edn` version,
   operator artifact identity, and default launch descriptor are recorded.
4. The client and writer are built from that revision and `bin/seon status
   --edn` reports the frozen default cluster ready. No build input changes from
   the first observed line through the final hygiene inventory.
5. No agent run is active while the short timeout probe temporarily redefines
   the timeout accessor. If this cannot be established, use an isolated named
   cluster built from the same artifact; do not perturb an active default
   agent.
6. Resolve terminology at the frozen revision. Before Stage 2 the operator
   command and event file are `pod` and `pod-events.log`; after the atomic
   retirement they are `client` and `client-events.log`. Never cross the rename
   with a restart, and never treat the abandoned pre-rename file as the active
   tail source.

The decisive source-shape predicate for both process lines is:

```text
^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z  (TRACE|DEBUG|INFO |WARN |ERROR) \[[^]]+\] .+$

```

This predicate tests the shared contract while allowing different sources and
messages. Comparing prose substrings or merely finding `INFO` in both logs is
too weak.

## Exact paired-line observation

Run the following after the coordinated cold start. At the current pre-rename
HEAD, `client_process=pod`; after Stage 2 set it to `client`.

```bash
stage3_head=$(git rev-parse HEAD)
client_process=pod
mkdir -p tmp/source-cleanup-stage3-proof
bin/seon status --edn > tmp/source-cleanup-stage3-proof/status.edn
bin/seon logs "$client_process" --lines 200 > tmp/source-cleanup-stage3-proof/client.log
bin/seon logs writer --lines 200 > tmp/source-cleanup-stage3-proof/writer.log
rg '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z  (TRACE|DEBUG|INFO |WARN |ERROR) \[[^]]+\] .+$' \
  tmp/source-cleanup-stage3-proof/client.log \
  tmp/source-cleanup-stage3-proof/writer.log

```

Record one matched current-generation line per file, the two process
generation IDs from status, `stage3_head`, and the artifact digest. The writer
line should be a first-party `seon.db.server` boot/readiness line so a library
formatter cannot accidentally satisfy the check. The client line should be a
first-party `seon.client` boot/readiness line. The current-generation paths
printed in the two `bin/seon logs` headings must agree with the process records;
an old top-level `logs/database-server.log` or `logs/pod.log` is not proof.

The check fails if either file has no match, either selected line is from a
previous generation, the revision/artifact moves, or a formatter fallback is
the only writer output. It does not require identical messages or a writer
event file: the owner ruling is format-only on the writer.

## Exact loop-fault-to-tail observation

Use the repository MCP `eval_cljs` against the frozen cluster-qualified root
agent. Generate a unique token first. The first form calls the real private
timeout boundary, shortening only the accessor used synchronously by that
call. The MCP bridge must await the returned Promise.

```clojure
(^:async (fn []
  (with-redefs [seon.config/turn-timeout-ms (fn [] 25)]
    (await
     ((deref #'seon.agent.loop/await-bounded)
      "stage3-live-<token>"
      (js/Promise. (fn [_resolve _reject])))))))

```

The complete returned envelope must contain the ordinary value
`{:seon.error/message "stage3-live-<token> exceeded ..."}` and no thrown
exception. Then evaluate a second form in the same frozen client:

```clojure
(->> (seon.log/tail {:seon.log/n 50
                     :seon.log/level :error
                     :seon.log/source :seon.agent.loop/step-bound})
     (filterv #(clojure.string/includes?
                (:seon.log/message %)
                "stage3-live-<token>")))

```

Exactly one returned entry must carry source
`:seon.agent.loop/step-bound`, level `:error`, the token, and a valid
`:seon.log/at`. Finally verify the same token exists in the active structured
event file named by the frozen launch descriptor. This proves producer,
structured-file sink, and `tail` reader as one path; calling `seon.log/error!`
directly or grepping only the console log would not prove the loop boundary.

The probe is intentionally bounded and does not transact domain data. Run it
only with no active default run, because `with-redefs` changes a global Var for
the duration of the awaited 25 ms call. If the form cannot compile against the
frozen client, stop and record that as missing proof; do not add a public probe
function or a second logging path merely to satisfy the gate.

## Safe shared-log cleanup

The audit baseline has 701 top-level files under `logs/`, including 468
`pod-bench-*`, 67 `pod-plan-*`, and 58 `.eval` files. Those counts demonstrate
the old midden but do not establish ownership of every file. Meanwhile the
actual operator root contains managed generation directories and the active
structured event file under `logs/operator/`. Filename-only deletion is
therefore unsafe.

Cleanup is an explicit, recoverable quarantine pass after the paired proof:

1. Stop producers through their owning operator only after recording the live
   evidence. Obtain handoff for retained branches, Inspect runs, and any
   independent lane that writes under `logs/`.
2. Snapshot every managed current-log path from the default, named branches,
   ACME, and other live launch descriptors. Protect their containing process
   directories, the active event file plus rotations, and all current
   operator/watcher/writer logs.
3. Inventory top-level regular files without following directories. Classify
   `.eval` files by Inspect run metadata and digest. Evidence belonging to a
   retained run moves into that run's `src-inspect-ai/evals/runs/<run>/`
   evidence directory through the existing Inspect retention mechanism; an
   uncorrelated file is quarantined, never relabeled as proof.
4. Move reviewed legacy top-level bench/plan/probe/calibration/reproduction
   output into a HEAD-named directory under `tmp/`. Do not recursively remove
   `logs/`, `logs/operator`, `logs/clusters`, `logs/acme`, or a retained branch
   directory. Keep the quarantine until final graduation so every move is
   reversible.
5. Cold-start through the operator once more, then inventory the frozen launch
   descriptor's log root. It may contain the active structured event file and
   rotations plus operator-managed generation directories. No new top-level
   bench/plan/probe or misplaced `.eval` output may appear.

Use a reviewed NUL-delimited manifest rather than a deletion glob:

```bash
stage3_head=$(git rev-parse HEAD)
stage3_quarantine="tmp/source-cleanup-stage3-log-quarantine-$stage3_head"
mkdir -p "$stage3_quarantine"
find logs -maxdepth 1 -type f \
  \( -name 'pod-bench-*' -o -name 'pod-plan-*' -o -name 'pod-cal-*' \
     -o -name 'pod-probe-*' -o -name 'pod-repro-*' -o -name '*.eval' \) \
  -print0 > "$stage3_quarantine/candidates.nul"

```

Decode and review that manifest against the protected-path snapshot before
moving a single file. Move only individually approved paths while validating
that each is a top-level regular file under the checkout's exact `logs/`
directory. The integration pass should record the before/after inventories
and quarantine path. Deletion, if desired later, belongs to the final U11-style
cutover cleanup after the user no longer needs recovery—not this proof.

If a new supported command still writes bench/probe output into top-level
`logs/`, or a new Inspect run writes `.eval` files there, that is a current
producer defect. Create an issue note with the producing command and path
before changing its owner; do not hide it by repeatedly pruning output.

## Issue and ledger closure

No new source defect was discovered by this audit. The B2 issue note is already
resolved and archived, and there is no separate open writer-format issue note
to move. After the frozen observation succeeds, the orchestrator updates
[[../logging-unification]], [[../roadmap]], [[../register]], and
[[program-graduation-matrix-2026-07-20]] with:

- exact frozen HEAD and artifact identity;
- selected client/writer generation IDs, current log paths, and one matched
  line from each;
- both MCP forms and complete returned values for the loop/tail proof;
- before/after log inventories and the recoverable quarantine path; and
- confirmation that no current producer recreated a misplaced file.

Stage 3 then reads **live-complete at that revision**. It does not graduate the
whole source-cleanup program: the final twice-consecutive three-suite pair and
the complete frozen live-cluster matrix remain separate gates.
