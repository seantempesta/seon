---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Clean planned restart quiescence refresh — 2026-07-15

## Result

The earlier combined restart/restore audit has one stale half and one still-
current half. JVM request admission, admitted-handler joining, Datahike release
failure retention, and writer/server failure projection are implemented and
tested. Clean agent-turn quiescence and operator composition remain absent.

The next dependency-ready implementation is one planned-restart transition,
not restore administration:

1. extend the existing runtime admission value with `:quiescing`;
2. let an already-admitted loop finish its current complete turn, then close its
   still-owned run once as `:quiesced`;
3. derive drain completion from current run pointers and running turn facts;
4. expose that transition through one loopback-only action on the existing web
   server;
5. detach the feed, drain the pod's maintained remote writer, and return one
   final complete database coordinate; and
6. let the Babashka operator consume that typed response before using the
   existing managed-process inverse.

Process exit before the typed pod response is not a clean restart. Cold boot
continues to run the existing unexpected-exit recovery transaction; no clean
bit, restart entity, Promise registry, or replay path is added.

The JVM has one remaining cross-process proof gap. Its in-process stop result is
typed, but `seon.db.server/-main` only prints it and `seon.dev.process/stop!`
cannot consume it. Clean writer-drain graduation therefore depends on the same
generation-matched managed-process terminal result already required by the
subtree-containment slice. This is tracked in
[[planned-restart-cannot-observe-writer-drain-result]].

Restore, undo, force-promotion, external intent, blob materialization, and admin
mode are deliberately excluded. They consume this drain later; they must not be
implemented in this slice.

## Dependency ledger

The selected identities below were read from the current `deps.edn` and checked
against the maintained source mirrors. They replace the stale Datahike SHA in
the 2026-07-14 audit.

| Dependency or mechanism | Selected identity | Source grounding | Constraint for this slice |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` in both `:writer` and `:cljs` | `reference-code/datahike` is exactly that SHA; `src/datahike/writer.cljc:40-73`; `src/datahike/connector.cljc:438-510` | Final connection release synchronously closes writer admission, joins processing/commit loops and accepted out-of-band operations, then closes secondary indexes and Konserve. A thrown aggregate means drain was not proved. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` in both runtime graphs | `reference-code/konserve` is exactly that SHA; `src/konserve/protocols.cljc:4-36`; `src/konserve/core.cljc:278-317` | Atomic store operations do not replace application admission or process drain. This slice calls Datahike release; it never opens Konserve directly. |
| ClojureScript | `1.12.145` | `deps.edn`; `reference-code/clojurescript`; the `clojurescript` skill | A current turn is a native Promise chain. Quiescence is observed only after the awaited `turn/run-turn!` returns; no bare `await`, core.async path, or Promise cancellation is introduced. |
| Shadow CLJS | maintained fork `4e72595f57618f5c43388ad13d5136cd3bede566` | `reference-code/shadow-cljs` is exactly that SHA; `src/main/shadow/cljs/devtools/client/node.cljs:11-13,88-108` | Shadow's websocket and nREPL runtime are development transport, not lifecycle authority. The clean action must be an application web route present in the built pod, not an nREPL eval or “latest runtime” selection. |
| Babashka | executable `1.12.212` | `bb --version`; `bin/seon`; `script/seon/dev/cli.clj` | The one operator retains the `:stack` lock, target selection, bounded HTTP client, and clean-or-force policy. |
| `babashka.process` | maintained source `16a84e0af0da51b8c84e289970f6b7cc35b35d18` (`v0.6.25`) | `reference-code/babashka-process/src/babashka/process.cljc:420-451,680-713` | Process handles can wait for exit and run shutdown hooks, but process disappearance does not carry Seon's database stop result. The existing managed-process result must make that proof explicit. |
| Runtime admission | current `seon.runtime.admission` | `src/seon/runtime/admission.cljs`; `test/seon/runtime/admission_test.cljs` | One process-local state already gates agent, schedule, loop, and web work. Strengthen it in place; do not create a quiescence registry. |
| Turn/run boundary | current `seon.agent.loop`, `seon.agent.turn`, `seon.agent.run` | `src/seon/agent/loop.cljs:142-373`; `src/seon/agent/turn.cljs:270-381`; `src/seon/agent/run.cljs:357-511` | Turn open and close are already awaited. Run close already owns the CAS-fenced pointer retract. Add one honest reason and one recurrence event. |
| Pod remote writer | current `seon.db.replica/RemoteWriter` plus `seon.db/release-connection!` | `src/seon/db/replica.cljs:570-718`; `src/seon/db.cljs:367-397`; `test/seon/db/replica_test.cljs:418-462` | `d/release` closes new RPC admission and awaits every registered RPC completion. Freeze the final local complete coordinate after turn drain and feed detach, before releasing the connection. |
| JVM request and release owners | current UDS, registry, writer, and server | `src/seon/db/transport/uds.clj:138-258`; `src/seon/db/registry.clj:896-928`; `src/seon/db/writer.clj:1093-1107`; `src/seon/db/server.clj:166-204` | The old hidden-drain defects are fixed. Keep these owners; add only the operator-visible terminal proof needed to compose them. |

## Shortest falsifiers and executable probes

The default cluster was down. `bin/seon status` reported watcher, writer, and
pod absent, so no live CLJ/CLJS REPL mutation was justified and ACME was not
touched.

The shortest source falsifier for planned quiescence is the current loop gate:

- `run-loop!` tests `admission/available?` before deriving the next event and
  again immediately after a completed turn (`loop.cljs:244-245,293-294`);
- admission has only `:starting`, `:publishing`, `:available`, and
  `:unavailable` (`admission.cljs:15-30`); and
- the run reason enum has no `:quiesced` (`run.cljs:70-78`).

Closing the current boolean gate while a turn runs therefore makes the loop
return an unavailable value at its next boundary and leaves its open run
pointer untouched. That is exactly the failure this slice must change.

The smallest executable JVM probe reran the maintained request-drain contract:

```text
bin/test-writer seon.db.transport-uds-test
Ran 9 tests containing 28 assertions.
0 failures, 0 errors.
```

The focused test blocks an admitted handler, begins close, proves close still
waits, releases the handler, receives its response, then proves new admission
fails. This confirms that UDS handler joining is a completed prerequisite, not
work to redesign.

## Current source inventory

### Built and retained

- `seon.runtime.admission` is the sole executable-program admission value.
  Agent birth, messages, resume, wake, run-loop, schedules, ticker, and admitted
  web POSTs already consult it.
- `turn/open-turn!` commits `:running` before the body and `close-turn!` awaits
  the body before transacting `:done` or `:error`.
- `run/close-run!` leads with the current-run CAS, closes the run, and retracts
  only the pointer it still owns in one transaction.
- all wake triggers and the ticker have idempotent process-local inverses.
- `client/stop-runtime!` is one serialized, retryable destructive inverse over
  web, ticker, hosted-agent handles, feed, active projection, and the local
  Datahike connection.
- replica `RemoteWriter/-shutdown` closes new RPC admission and awaits every
  admitted operation; `db/release-connection!` awaits Datahike release.
- UDS close admits decoded requests under the server lifecycle lock, closes idle
  readers, preserves active responses, and joins the accept and connection
  workers.
- registry release retains failure identity; writer/server stop aggregate
  failures as `stopped? false`; the JVM shutdown hook prints the result.

### Still missing

- admission cannot represent “refuse new work while already-admitted loops may
  settle”. Its boolean `available?` conflates new admission with continuation.
- `next-event` has no quiesce event, and `run-loop!` has no boundary that closes
  the run `:quiesced` after a completed turn.
- paused runs and open runs without an active driver have no process-wide drain
  coordinator.
- `client/stop-runtime!` begins by closing the web server and does not wait for
  durable turns/runs. It cannot directly serve as an HTTP request handler and
  may release the database while a loop still owns work.
- the router has a private operator config action but no lifecycle action.
  Existing state-changing POST wrappers use only program admission and browser
  same-origin; the lifecycle action additionally needs a real loopback peer
  check and must remain callable while ordinary admission is closed.
- `seon.dev.cli/stop-development!` signals processes directly. It does not call
  the pod, validate a typed response, or distinguish clean from forced stop.
- the pod has no `SIGTERM`/`SIGINT` handler. A signal alone exits the Node process
  without calling `client/stop-runtime!`.
- the JVM stop result does not include pre-release complete coordinates and is
  not consumable by `seon.dev.process/stop!`; clean writer drain cannot yet be
  proved across the process boundary.

## Settled semantics

### One admission value, two questions

Extend `seon.runtime.admission/!state`; do not add another atom. The state needs
at least `:quiescing` between `:available` and detached `:starting`.

The existing `available?` meaning remains “may start new executable work”. Add
one explicit observation for an already-owned loop, for example
`quiescing?`. New messages, schedules, agent start/resume, web commands, wake,
and direct turn entry refuse in `:quiescing`. An already-admitted `run-loop!`
does not start another turn: at the recurrence boundary it closes the still-
owned run `:quiesced`.

Publication and teardown must not be able to acquire a quiescing state as a
second owner. The lifecycle transition is a compare-and-change from
`:available`; repeated quiesce calls observe or join the same transition. A
failed drain remains closed with its reason and retained connection capability
so the explicit force path can stop the process without inventing clean proof.

### Durable turn/run drain

Do not retain loop Promises in a new registry. The database already says what
was accepted:

- a `:seon.agent.turn/status :running` fact means an allocated turn body has
  not completed its bracket; and
- an agent's `:seon.agent/run` ref to an open run means durable current
  ownership remains.

At quiesce start, synchronously uninstall the ticker and every wake trigger so
no timer/listener schedules later work. Then repeat one immutable-snapshot
derivation:

1. query every running turn;
2. query every current open run pointer;
3. close each current run that has no running turn as `:quiesced` through the
   existing `run/close-run!`; and
4. wait while any running turn remains, letting its owning loop return to the
   new quiesce event and close its run.

Query all running turn facts, not only turns whose run is still current. A
concurrent lifecycle close may retract the current pointer while the accepted
turn body is still settling. Quiescence waits for the real bracket; it does not
pretend pointer loss cancelled a Promise.

A `close-run!` CAS loss is ordinary convergence: another owner already settled
or replaced the pointer. Re-read one database value and decide from current
facts. `:quiesced` is excluded from `outcome-reasons`, so planned maintenance
does not message a parent or user as a task result.

### Pod lifecycle action and final coordinate

Add one `POST /_seon/operator/quiesce` entry to the existing static operator
supplement and inject its handler from `seon.web.serve`. It is not an admitted
ordinary POST: it must be callable precisely to close admission. It accepts no
user-selected database identity or mode and only operates on the runtime already
owned by that pod.

The handler must verify that the actual peer address is loopback. `Origin` and
`Host` are browser-CSRF inputs, not operator identity, and an absent `Origin`
must not make a lifecycle request remotely callable when `SEON_BIND=0.0.0.0`.
The route returns EDN after the following ordered transition:

1. acquire the runtime's existing lifecycle phase and admission transition;
2. uninstall ticker and wake triggers;
3. settle current runs/turns as above;
4. unhost the now-drained agent process handles;
5. detach the replica feed so no foreign commit changes the local proof point;
6. detach the executable projection while the connection remains retryable;
7. freeze `db/head-coordinate` from the still-live immutable database value and
   await `db/release-connection!`, which drains the remote writer; and
8. clear ambient connection/capability only after every prior step succeeds.

The response is closed data containing `quiesced?`, the final complete
coordinate, quiesced run ids, completed turn ids or count, and unhosted ids.
There is no `clean?` database fact. Keep the web listener alive only long enough
to flush this response; the operator then stops the already-drained pod through
the existing managed-process inverse.

If HTTP response flush races server shutdown, split the current
`client/stop-runtime!` inverse into an internal drained-runtime portion and the
final web close. Do not create a second teardown implementation: ordinary stop
calls the same ordered pieces, while the lifecycle route delays only its own web
server close until after response delivery.

### Operator clean-or-force policy

Under the existing `:stack` lock, `restart`, `down`, rebuild reconciliation, and
pod-only retained-branch restart call one operator stop function with an
explicit policy:

- when a compatible ready pod answers with a schema-valid quiesce result, retain
  that response as invocation evidence, stop the drained pod, then stop the
  writer when the operation requires it;
- when the pod is absent, there is no pod work to quiesce;
- when the request times out, the pod dies, the response is partial, or the
  coordinate is invalid, record no clean claim and use the existing signal
  fallback; the next cold boot runs unexpected recovery; and
- a branch pod-only restart never stops its external source writer or watcher.

Do not parse logs or call nREPL. The lifecycle request targets the exact URL in
the process/launch descriptor. The writer side reuses the managed-process
generation-matched terminal result required by subtree containment. That result
must carry the pre-release full coordinate and every typed release outcome; a
missing or failed result prevents the operator from labeling the writer drain
clean even if the JVM exited.

## Ordered implementation boundary

### Slice A — admission and run semantics

Owned source: `src/seon/runtime/admission.cljs`,
`src/seon/agent/loop.cljs`, `src/seon/agent/run.cljs`, and their focused tests.

- add `:quiescing`, an atomic begin-quiesce operation, and explicit predicates
  for new admission versus already-owned continuation;
- add `:quiesced` to the run reason schema and loop transition vocabulary;
- close only at the pre-turn or post-turn recurrence boundary; and
- add pure current-run/running-turn drain projections in the existing agent
  owner rather than a Promise inventory.

Exit: a quiesce request during a blocked turn cannot close it early; after the
body and turn close commit, the run closes `:quiesced`, its pointer disappears,
and no outcome message is created.

### Slice B — one pod quiesce inverse and web action

Owned source: `src/seon/client.cljs`, `src/seon/web/serve.cljs`,
`src/seon/web/router.cljs`, and focused lifecycle/router tests.

- factor the existing retryable stop inverse so the web action can drain runtime
  owners before closing its own listener;
- add the loopback-only operator action and closed EDN response;
- retain connection/capability and cleanup-required phase on every failure; and
- return the final full coordinate only after durable turn drain and before
  connection release.

Exit: direct handler proof shows refusal of a non-loopback peer, no new web or
agent work after `:quiescing`, response flush after remote-writer drain, and
retryable failure at every destructive stage.

### Slice C — operator composition and writer terminal proof

Owned source: `script/seon/dev/cli.clj`, `script/seon/dev/process.clj`, and the
existing writer/server terminal-result boundary. This slice follows the public
branch checkpoint and shares the containment mechanism; it must not overlap a
concurrent process-owner edit.

- one bounded EDN HTTP client invokes the exact pod action;
- one clean-or-force stop path is reused by restart/down/reconcile and pod-only
  branch restart;
- writer/server stop returns the pre-release attachment coordinates alongside
  release results; and
- the managed-process terminal result carries that typed value to the operator.

Exit: injected missing/stale/failed terminal results cannot become clean, while
a successful source restart returns one pod coordinate and one writer terminal
coordinate that agree before reopen.

## Failure matrix

| Boundary | Durable truth | Required behavior |
|---|---|---|
| Before admission changes | Ordinary runtime | No clean result; retry the lifecycle request. |
| Admission is `:quiescing`, before a turn starts | Current run may be open, no new turn admitted | Close the still-owned run `:quiesced` through `close-run!`. |
| Quiesce arrives during a running turn | Open run, running turn, only actually committed evals | Wait for the whole turn bracket. Never write `:quiesced` early. |
| Turn finishes; run-close CAS loses | Another owner settled or replaced current ownership | Re-read durable facts; never retract the replacement pointer. |
| A paused run exists | Open current run, no active turn | Close immediately as `:quiesced`; do not resume it. |
| Drain deadline expires | Open/running facts may remain | Return typed failure, retain closed admission, and let operator force-stop with no clean claim. |
| Feed detach fails | Durable agent work is drained; replica resource uncertain | Retain cleanup authority and connection; no final response. |
| Projection detach fails | Database remains attached; wrappers may remain | Retain connection/capability and cleanup-required phase; retry same inverse. |
| Remote-writer/Datahike release fails | Accepted RPC or local store release is unproved | Return failure; do not clear ambient ownership or claim a final drained pod. |
| Pod response is lost after drain | Database facts may already be clean | Operator records no clean response and may force-stop; cold recovery reads facts and writes nothing if ownership is already settled. |
| UDS close begins with an active JVM handler | Request was admitted before close | Preserve its response and join it; later requests fail admission. |
| Registry release fails | Exact registered database identity and error remain | Writer/server terminal result is failed; do not claim writer-drained. |
| JVM exits without a matching terminal result | Process is absent; release proof is absent | Restart may proceed only as forced/unproved; never label it clean or use it for exclusive promotion. |
| Reopen recovery finds no current/open ownership | Prior quiesce facts are sufficient | Recovery is an idempotent no-op; admission rearms only after publication/readiness. |
| Reopen recovery finds an orphan | Previous stop was unexpected or incomplete | Existing one-transaction recovery closes it `:crashed`/`:interrupted`; no eval or message is fabricated. |

## Focused proof plan

### Admission, loop, run, and turn

- atomic begin-quiesce has one owner and repeated callers join/observe it;
- every new-work boundary refuses in `:quiescing`, while wait/complete/pause/
  terminate/unhost and the quiesce owner remain callable;
- quiesce before turn-open writes no turn and closes the current run once;
- quiesce during a controllably blocked body leaves turn `:running` and run
  open until the body resolves;
- body result, eval rows, and turn `:done` commit before the `:quiesced` close;
- paused run closes immediately; superseded/CAS-lost ownership is not reclosed;
- `:quiesced` sends no parent/root outcome message; and
- repeated drain derives empty work and writes nothing.

### Client and web lifecycle

- the loopback action accepts no database/agent selector and rejects an actual
  non-loopback peer even with absent `Origin`;
- ordinary admitted POSTs return 503 after quiesce begins, but the lifecycle
  action itself remains reachable;
- ticker/wakes uninstall before drain, hosts unhook after turns settle, feed
  detaches before final coordinate freeze, and remote writer drains before the
  response resolves;
- failure injection at run close, feed, projection, and connection release
  retains the same capability/connection for retry; and
- response serialization requires the closed complete-coordinate schema.

### JVM and operator

- retain the existing UDS blocked-handler close test as a prerequisite;
- writer/server stop snapshots exact coordinates after request admission and
  handler join, then reports every release result;
- process proof covers successful, failed, missing, malformed, and stale-
  generation terminal results;
- restart/down/reconcile share one clean-or-force path; and
- retained branch pod restart quiesces only that pod and leaves source watcher
  and writer identities unchanged.

## Live default proof

Run only after the public default branch checkpoint, the process containment
owner, and focused tests are integrated under one source freeze. Leave ACME
untouched.

1. Reset and boot default through `bin/seon`. Record writer and replica
   `{database-id, branch, commit-id, t}`, open-run/running-turn counts, recovery
   anchor count, and process generations.
2. Start one real bounded turn whose body crosses a controllable Promise. Wait
   until its durable turn status is `:running`, then invoke `bin/seon restart`.
3. Prove restart waits. Release the body and read back its real eval/result,
   turn `:done`, run `:closed`, reason `:quiesced`, absent current pointer, and
   unchanged recovery-anchor count.
4. Capture the pod quiesce response and writer terminal result. Both carry
   valid complete coordinates on the same attachment; the writer point is equal
   to or a descendant of the pod point. No numeric `t` comparison crosses a
   branch or commit identity.
5. After reopen, prove writer and replica agree at one equal point descending
   from the terminal writer point, crash recovery wrote nothing, admission is
   available only after committed program publication, and a later message opens
   a fresh run and commits a database write.
6. Repeat with the pod killed before its quiesce response. Prove the operator
   records no clean result, cold boot creates exactly one unexpected-exit repair
   for genuinely open ownership, and an immediate second restart is idempotent.
7. Inject a JVM release failure in the isolated process fixture. Prove the
   operator cannot report clean writer drain from process absence and retains
   the matching terminal evidence for diagnosis.

Required evidence is typed operator output, the pod quiesce EDN response,
generation-matched writer terminal result, complete coordinates, run/turn/eval
datoms, recovery-anchor counts, and post-restart CLJ/CLJS read-back. A zero exit
status or passing unit-test count alone is not graduation evidence.

## Exit and follow-on

This slice graduates when planned restart preserves a complete accepted turn,
closes its run `:quiesced`, drains both write boundaries with operator-consumable
proof, reopens without unexpected recovery, and the forced-kill control still
uses the existing crash transaction.

Only then may restore/undo consume the same quiesce and writer terminal result.
This report does not authorize force-promotion, restore intent, branch-local
blob materialization, or admin mode.
