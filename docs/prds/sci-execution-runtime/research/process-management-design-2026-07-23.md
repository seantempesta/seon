---
type: research
status: active
tags: [research, runtime, operator]
---

# Process management design — 2026-07-23

## Executive ruling

The failure is a process-graph completeness bug, not merely a stale-file bug. Web-render is part of `all-process-ids`, the selected target graph, and the generated process specifications, but `clean-or-force!` filters requested targets through an older four-member shutdown list that omits web-render. Thus `down` and `reset` can request web-render, silently produce no result for it, and report success while its generation remains live and recorded (`script/seon/dev/process.clj:26-32,216-232,609-680,2146-2174`; `script/seon/dev/cli.clj:27-40,296-308,815-842`). This exactly explains the retained transcript: web-render was later blocked by the generic “managed process present” guard (`docs/seon/issues/predfix-web-render-record-survives-operator-down.md:11-25`; `script/seon/dev/process.clj:2481-2523`).

The process record should remain, but only as an immutable descriptor of one exact managed generation: desired argv/artifact identity, log, containment generation, control paths, and the `(pid,start-instant)` identities required to control or disprove that generation. It must not be treated as stored liveness. Liveness is already derivable from the operating system using PID plus start instant (`script/seon/dev/process.clj:99-111`; `script/seon/dev/state.clj:11-26`). The record is therefore analogous to the `:seon.agent.run/process` and epoch, not to a stored `alive?` flag: the identity says which process instance is meant, the generation fences commands, and current observation decides whether that identity still exists (`docs/seon/architecture/agent-runtime.md:35-39,49-69`; `docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:882-896`).

The one strengthened mechanism is:

> Every lifecycle operation reconciles a generation through the canonical owned-process dependency graph, using exact `(pid,start-instant)` observations and generation-bound containment evidence. Reverse topological order determines shutdown. Requested targets and returned absence proofs must be identical sets. A dead exact identity is automatically consumable; a surviving exact subtree is automatically reaped. No member list, callback, status reader, or caller may implement a second lifecycle rule.

`ProcessHandle.onExit()` should supply push notification while an operator invocation is alive, but it cannot be durable authority. `bin/seon` is a one-shot Babashka command and exits after the requested transition, so an in-memory future cannot repair records after the command JVM itself is killed (`bin/seon:1-7`). Crash recovery must therefore remain derivable and repeatable from the record plus current OS evidence.

## Dependency ledger

- The first-party lifecycle authority is `seon.dev.process`; atomic record I/O and exact process identity are in `seon.dev.state` (`script/seon/dev/process.clj:1-15`; `script/seon/dev/state.clj:1-63`).
- The detached containment implementation is `script/seon/dev/detach.py`: launcher → owner → process-group anchor → workload (`script/seon/dev/detach.py:101-143,186-254,262-389`).
- R42 requires event-driven progress/death detection and permits only loud, configured stall breakers; R27 requires every protective limit to be a calibrated config fact rather than a runtime numeric literal (`docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:740-749,764-773,893-896`).
- The accepted census identifies `ProcessHandle.onExit()` as the push replacement for JVM process-death polling and `CompletableFuture.allOf` as the process-tree absence primitive (`docs/prds/sci-execution-runtime/research/poll-timeout-census-2026-07-23.md:44-55,103-114`).
- The JDK contract says `onExit()` returns a `CompletableFuture` completed after termination, supports dependent actions, and may be called independently more than once. It also warns that PID reuse is unpredictable and status/action races exist. See [Oracle `ProcessHandle` documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessHandle.html#onExit()) and [Oracle `ProcessHandle.Info.startInstant()` documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessHandle.Info.html#startInstant()).
- A read-only probe against the checkout’s JVM confirmed the required behavior: the future was incomplete while `sleep` was live, completed after exit with the same PID, and the handle then reported not alive.

## Lifecycle today

### 1. Process membership and dependency order

Web-render is a declared managed member. `target-process-ids` includes it for every source-checkout ownership variant, and `specs` gives it dependencies on pod and, when locally owned, writer (`script/seon/dev/process.clj:26-32,216-232,609-645`). The existing `start-order` already topologically sorts this dependency graph (`script/seon/dev/process.clj:690-709`).

The lifecycle coordinator does not reuse that graph. It uses the hard-coded shutdown vector `[pod host writer watcher]`, silently filtering away any selected ID absent from that vector (`script/seon/dev/process.clj:2154-2161`). Because web-render depends on pod, the correct derived shutdown prefix is web-render then pod, followed by the remaining reverse-topological members.

There is no closure assertion comparing requested targets with result IDs. Classification is computed only over whatever the hard-coded vector happened to retain (`script/seon/dev/process.clj:2161-2174`). The CLI further hides the incompleteness by rendering only the first three component results (`script/seon/dev/cli.clj:237-245`).

Tests reproduced the same blind spot. The real `clean-or-force!` ordering test selects watcher, pod, host, and writer but not web-render (`test/seon/dev/process_test.clj:1622-1678`). CLI tests manufacture web-render results in a mock helper, so they prove formatting and request construction without proving that the real coordinator ever visits web-render (`test/seon/dev/cli_test.clj:19-34,669-691,1081-1115`).

### 2. Publication and adoption

The Python launcher starts a detached containment owner in a new session and waits for its descriptor (`script/seon/dev/detach.py:101-143`). The owner starts an anchor, which starts the workload in the anchor’s process group (`script/seon/dev/detach.py:186-223,279-316`).

Only after the descriptor returns does `spawn-detached!` repeatedly acquire start instants for owner, anchor, and workload. Failure to acquire all three aborts publication (`script/seon/dev/process.clj:1126-1223`). It then atomically writes the complete process record and sends the generation-bound adoption request (`script/seon/dev/process.clj:1224-1262`). Record-before-adoption is the correct ordering: reversing it would permit an adopted process with no durable descriptor.

The owner publishes adoption separately and will drain an unadopted generation after its admission window (`script/seon/dev/detach.py:317-346`). Existing regressions cover failures before the record and between record publication and adoption (`test/seon/dev/process_test.clj:1307-1359`).

### 3. Derived liveness

`state/process-identity-alive?` compares the recorded start instant with the currently observed start instant for that PID (`script/seon/dev/state.clj:11-26`). `process-status` already distinguishes exact live identity, reused PID, absent PID, and absent record (`script/seon/dev/process.clj:711-717`). `containment-live?` additionally requires matching adoption, exact record/owner identity, anchor/process-group agreement, all three exact identities alive, and no terminal result (`script/seon/dev/process.clj:727-751`).

This is already the correct R22-style identity construction. The missing piece is applying it consistently to stale classification and every lifecycle entry.

### 4. Terminalization and cleanup

On requested drain or workload exit, the anchor terminates the process group, the owner waits for group absence, writes the generation-bound terminal result atomically, and removes its control socket (`script/seon/dev/detach.py:224-254,347-389`). The process record deliberately remains so the next operator can consume exact terminal evidence.

`stop!` compares any expected record with the current record before acting, classifies dead-stale and orphaned-workload cases, otherwise drains the containment owner, and then deletes the process record and containment paths (`script/seon/dev/process.clj:1759-1808`). This exact-record comparison is the correct generation fence and must remain.

Record deletion is weaker than record publication: `write-edn!` fsyncs the temporary file and parent directory, and `delete-edn!` fsyncs the parent after unlink, but `clear-process!` bypasses `delete-edn!` and calls `fs/delete-if-exists` directly (`script/seon/dev/state.clj:40-63`; `script/seon/dev/process.clj:317-325`). A killed operator can therefore lose durable proof of the deletion even after the in-process unlink appeared successful.

### 5. PID reuse leaves an avoidable manual-cleanup state

`dead-stale-containment?` currently requires every recorded numeric PID to be wholly absent from the OS (`script/seon/dev/process.clj:1454-1467`). That is stricter than exact-identity absence. If an unrelated process reuses one PID, every recorded `(pid,start-instant)` identity can be dead while the raw PID is present; the classifier then returns `containment-uncertain` indefinitely (`script/seon/dev/process.clj:1469-1483`).

The test suite codifies this weakness by expecting a present recorded PID to preserve uncertainty, but it substitutes PID presence rather than an exact matching start instant (`test/seon/dev/process_test.clj:2340-2375`). This contradicts the stronger identity primitive already used elsewhere and creates precisely the manual-cleanup trap the new design must remove.

### 6. Owner death is not self-healing at the earliest boundary

If the containment owner is killed, the anchor’s stdin pipe reaches EOF. The anchor already selects that pipe, but an empty `readline()` is treated as an unrecognized command and ignored (`script/seon/dev/detach.py:224-239`). The surviving anchor and workload therefore become an orphaned subtree until a later operator recognizes the narrow owner-dead/anchor-live/workload-live pattern and kills the workload (`script/seon/dev/process.clj:1430-1452,1721-1757`).

The existing owner-death regression proves this behavior: it kills the owner, expects `orphaned-workload`, then relies on `ensure-host!` to reap and replace it (`test/seon/dev/process_test.clj:1957-2056`). Crash-anytime posture requires the anchor to interpret owner-pipe EOF as a death event and drain its own process group immediately. Later operator reconciliation remains the backstop, not the first detector.

## Why the record remains necessary

The record is not merely a cached `alive?` projection. After owner or workload death, the OS cannot reconstruct:

- the selected containment generation;
- the expected owner, anchor, and workload start instants;
- the generation-bound control socket and terminal paths;
- the argv, environment digest, artifact digest, and log belonging to the observed generation.

Those are all stored in the validated record (`script/seon/dev/process.clj:82-111`). They are necessary for exact control, convergence, diagnostics, and protection from PID reuse.

What must disappear is the assumption “record exists ⇒ managed process is present.” The record means “this exact generation was published.” Its current state is always derived. `ensure!` currently rejects every non-converged record before invoking the lifecycle mechanism, even when the record is provably dead or drained (`script/seon/dev/process.clj:2481-2523`). That guard turns recoverable evidence into a blocker and should be replaced by generation reconciliation.

Read-only `status` should remain a pure observation and may show `drained`, `dead-stale`, or `orphaned-subtree`. Mutating commands—up, ensure, restart, down, reset—must consume those observations through the same reconciliation transition before proceeding.

## The strengthened mechanism

### Canonical owned-process graph

Extract the ownership-dependent dependency graph currently embedded in `specs` into one pure function in `seon.dev.process`. `specs`, `target-process-ids`, start order, shutdown order, schemas, and lifecycle closure checks must all consume that graph.

For a requested subset:

1. Select the requested graph nodes.
2. Restrict each node’s dependencies to that selected set.
3. Run the existing `start-order`.
4. Reverse the result for shutdown.
5. Assert before stopping that the ordered ID set equals the requested target set.
6. Assert after stopping that the result ID set equals that same target set.

This makes silent omission impossible. A future sixth member either appears automatically from the graph or causes a loud completeness failure before any destructive reset work. No second shutdown registry or hand-maintained order is permitted.

For the current default graph, shutdown is:

```text
web-render → pod → host → writer → watcher
```

The exact suffix may vary with ownership, but it must always be the reverse of the selected dependency graph.

`classify-stop-result` must treat a requested web-render containment drain as clean, like host and watcher. Its current `case` has branches for watcher, host, pod, and writer only, so even a correctly stopped web-render would be mislabeled forced (`script/seon/dev/process.clj:2085-2117`).

### One derived generation observation

Replace the scattered predicates with one pure observation over the raw record:

- `:absent` — no record;
- `:live` — adoption matches, owner/anchor/workload exact identities are live, no terminal;
- `:drained` — matching terminal exists and the exact owner identity is dead;
- `:orphaned-subtree` — exact owner identity is dead and any recorded subordinate identity remains live;
- `:dead-stale` — every recorded exact identity is dead, regardless of raw PID reuse, and no matching-generation control owner responds;
- `:containment-uncertain` — contradictory evidence remains.

The observation must carry the exact record/generation used to derive it. No status keyword is persisted.

`dead-stale` must be based on `not process-identity-alive?` for the recorded identities, not `process-start-instant(pid) == nil`. A reused PID is evidence that the old identity is dead, not evidence that it remains dangerous.

### One generation reconciliation transition

Introduce one internal `reconcile-generation!` and make `stop!`, general `ensure!`, recovery, down, restart, and reset use it.

Its cases are:

- `absent`: return absent evidence.
- `live`: requested stop drains the exact generation; convergence may retain it only when exact spec and readiness both hold.
- `drained`: consume terminal evidence and clear the exact record.
- `orphaned-subtree`: terminate every still-matching recorded handle, wait for exit notification, force after the configured shutdown grace, then clear only after all exact identities are dead.
- `dead-stale`: clear immediately as forced/dead-stale evidence.
- `containment-uncertain`: retry observation within the configured operation/stall policy; if evidence remains contradictory, fail loudly without deleting or starting another generation.

Before deletion, re-read and compare the complete expected record or at minimum its generation plus owner identity. Use `state/delete-edn!` so deletion has the same durability standard as publication (`script/seon/dev/state.clj:40-63`). After deletion, re-read and prove absence before returning.

General `ensure!` must no longer turn a provably dead or terminal record into `managed-process-present`. If an existing generation is not converged, it invokes this same clean-or-force reconciliation for that exact target, proves absence, and only then publishes the replacement. A live contradictory listener still blocks replacement; dead evidence does not.

### Event-driven death detection

Use `matching-process-handle` as the identity gate before subscribing; it already resolves a handle and verifies the recorded start instant (`script/seon/dev/process.clj:1706-1719`).

Within a live operator invocation:

- `await-terminal!` subscribes to the exact owner’s `onExit()` future and validates the terminal file after the future completes.
- Orphan reaping subscribes to every still-matching recorded handle and uses `CompletableFuture.allOf`. Request normal termination, wait only for the configured shutdown grace, then force still-matching handles and await the same exit futures.
- Readiness watches race the ready/progress source with the exact owner exit future, so process death is reported immediately rather than on the next 200 ms status poll (`script/seon/dev/process.clj:941-988`).

No `onExit` callback may independently delete a record. The command process can be killed, callbacks are process-local, and an asynchronous callback could race a later generation. Notifications wake the one reconciler; record comparison and deletion remain inside the lifecycle lock.

The detached anchor should separately treat owner-pipe EOF as `owner-exit` and immediately run its existing group-drain path. That is the persistent supervisor’s native push signal and works even when the Babashka operator was killed. The later Java reconciler then sees either a terminal result or exact-identity absence.

Shutdown grace is legal because it bounds silence after an explicit stop request, but every grace and force ceiling must be an R27 config fact with schema, units, and calibration provenance. The current process specs and drain paths contain `2500`, `5000`, `30000`, and additional `10000` literals that should not survive the conversion (`script/seon/dev/process.clj:378-388,499-520,570-634,1582-1609,1721-1757`; `docs/prds/sci-execution-runtime/research/poll-timeout-census-2026-07-23.md:70-80`).

### Down and reset postconditions

`down` succeeds only when:

- every process ID in the canonical owned graph has one result;
- every corresponding record is absent;
- no generation-bound control owner responds;
- no exact recorded owner/anchor/workload identity remains alive; and
- no owned readiness door is accepting without a record.

`reset` must establish the same closure for every selected reset target before deleting the database. Today it deletes the database immediately after the incomplete coordinator returns (`script/seon/dev/cli.clj:825-842`). The new closure assertion must therefore sit inside `clean-or-force!`, before reset can cross that destructive boundary.

Web-render also needs an unmanaged-door probe. `readiness-paths` knows its port file, but `accepting-unmanaged?` has no web-render branch (`script/seon/dev/process.clj:1016-1049,1069-1087`). It should read the dynamic port file and perform the same HTTP readiness probe used by managed web-render readiness (`script/seon/dev/process.clj:883-915`). This closes the missing-record/live-listener half of the lifecycle law.

Stop evidence should print every bounded canonical member rather than `take 3`. With five managed members, truncation concealed the omitted process and made the faulty down appear complete (`script/seon/dev/cli.clj:237-245`; `docs/seon/issues/predfix-web-render-record-survives-operator-down.md:19-22`).

## Sol implementation specification

Owned files:

- `script/seon/dev/process.clj`
- `script/seon/dev/state.clj`
- `script/seon/dev/detach.py`
- `script/seon/dev/cli.clj`
- `test/seon/dev/process_test.clj`
- `test/seon/dev/cli_test.clj`
- the existing operator recurring test surface, if separate

Implementation order:

1. Extract the canonical owned dependency graph and make `specs`, `target-process-ids`, `start-order`, and reverse shutdown ordering consume it.
2. Add target/result set equality assertions to `clean-or-force!`.
3. Add web-render clean classification and remove the three-result evidence truncation.
4. Consolidate exact generation observation; change dead-stale detection from raw PID absence to exact identity absence.
5. Replace direct state-record deletion with exact-record-checked `state/delete-edn!`.
6. Route recovery, `ensure!`, stop, down, restart, and reset through one `reconcile-generation!`.
7. Add the web-render unmanaged readiness-door probe.
8. Change anchor EOF handling so owner death self-drains the process group.
9. Replace process-death polling in the reconciler with identity-checked `ProcessHandle.onExit()` futures. Keep polling only for genuine filesystem/readiness races until their progress source is converted.
10. Move operator shutdown grace/force bounds used by this mechanism to R27 configuration facts; do not introduce another timeout literal.
11. Update existing tests that assert the weaker behavior: clean-or-force must include web-render, PID reuse must become dead-stale rather than uncertain, and owner death must self-drain before operator recovery.
12. Run the focused operator suite, then the recurring `bin/seon test operator` gate, then the isolated live reset proof.

No new registry, lifecycle daemon, cleanup command, or manual “delete stale record” escape hatch is permitted.

## Class-killing regression

Add one recurring real-process regression—not a mocked target-set test—with this exact scenario:

1. Create an isolated operator configuration and lightweight five-member process graph using the real detached containment helper.
2. Start the graph through a separate Babashka supervisor invocation using `with-startup-ownership`.
3. Hold the invocation after web-render’s record is written and adopted, and capture every generation plus owner/anchor/workload identity.
4. Send `kill -9` to that Babashka supervisor. This deliberately bypasses shutdown hooks; the existing SIGINT regressions do not cover this boundary (`test/seon/dev/process_test.clj:977-1036`).
5. Start a fresh operator invocation against the same process directory.
6. Execute the reset lifecycle: clean-or-force the selected graph, cross the simulated database deletion boundary, reconcile the graph again, and finish with a normal down.
7. Assert:
   - requested target IDs equal result IDs;
   - web-render appears in the reverse-topological stop results;
   - every pre-kill exact owner/anchor/workload identity is dead;
   - every selected process record is absent before reset deletion;
   - no readiness door accepts without a record;
   - reset reaches ready without `managed-process-present`;
   - final down again proves complete absence; and
   - the test performs no direct kill of a workload and no record-file deletion.

Add two focused companions:

- Simulate every recorded PID being reused with a different start instant; observation must be `dead-stale`, reconciliation must clear the record, and no unrelated process may receive a signal.
- Kill only the containment owner; anchor EOF must self-drain the exact process group, after which a fresh reconciler consumes the dead record without invoking the orphan fallback.

The live acceptance is the issue’s original boundary on an isolated cluster:

```text
bin/seon cluster reset predfix
# kill -9 the active supervisor during managed startup/reconciliation
bin/seon cluster reset predfix
bin/seon down
```

The second reset must reach ready, and the final down must report every canonical member—including web-render—with no live or recorded generation and no manual cleanup. That closes the acceptance criteria in `docs/seon/issues/predfix-web-render-record-survives-operator-down.md:27-38` and makes future member omission a construction-time closure failure rather than another orphan.
