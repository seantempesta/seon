---
type: research
status: completed
tags: [research, database, flow, orchestrator]
---

# Clean-or-force operator coordinator audit — 2026-07-15

## Result

The next operator seam is one coordinator in `seon.dev.process`, above the
generation-bound `stop!` inverse and below ordinary and retained-branch callers.
It first requests the pod's already-implemented loopback quiescence action,
then proves the exact pod containment generation absent. It stops the writer
only after pod absence and classifies the writer from the same containment
terminal plus the planned portable application result. Watcher drain is the
last process-only inverse. Every caller supplies a set of targets; the
coordinator, not the caller, owns their safe order.

The coordinator returns closed, fully namespaced data. A clean application
result and process-subtree absence are separate evidence. A missing, malformed,
failed, stale, or timed-out application result becomes `:forced` only after
subtree absence is proved. A missing or invalid containment result is
`:containment-uncertain`, retains the process record, and stops the transition.
An already absent target is `:absent`, never retroactively `:clean`.

The 15-minute pod turn bound does not conflict with the current 5-minute,
30-minute, or 30-second lifecycle lock values. `seon.dev.state/with-lock` uses
its timeout only while acquiring the kernel file lock; it places no deadline on
the transition after acquisition. Keep those fail-fast contention policies and
add one separate absolute stop deadline. The recommended initial deadline is
the selected pod turn bound plus 120 seconds. Every HTTP, retry, containment,
and writer phase consumes that same remaining budget. No phase resets the
deadline. A command waiting on a holder may time out without interrupting or
invalidating the holder's transition.

Implementation order remains:

1. freeze and prove the current anchored-containment work;
2. add the portable writer terminal envelope and generation-bound application
   capture specified in
   [[writer-terminal-result-transport-audit-2026-07-15]];
3. move the pod's current response shape into one effect-free portable
   `seon.runtime.lifecycle` `.cljc` schema owner, then make both `seon.client`
   and Babashka refer to it without copying the shape;
4. make `process/stop!` return the validated terminal data before clearing an
   absent generation;
5. add the bounded loopback EDN client and one `process/clean-or-force!` owner;
6. route every ordinary, retained-branch, rebuild, and reset stop through it;
   and
7. prove a source-frozen clean restart and both forced and uncertain cuts.

## Scope and shortest falsifier

This audit read commits `b9c39ac1`, `272de2f3`, `3c2671a1`, and `00ab56ea`,
the current containment work in `script/seon/dev/process.clj` and
`script/seon/dev/detach.py`, all ordinary and retained-branch stop callers, the
launch/config owners, focused tests, and maintained dependency source. It
changed no production source, roadmap, process, or ACME path.

The shortest falsifier is `seon.dev.cli/stop-development!`: it reverses
`[watcher writer pod]` and calls `process/stop!` directly. It never invokes
`/_seon/operator/quiesce`, never consumes the pod coordinate or JVM release
result, and discards process evidence. `reconcile-development!`, cluster reset,
branch close, and branch restart also call `process/stop!` directly. Therefore
the implemented pod action and writer stop response cannot yet make any public
operator transition clean.

The second falsifier is temporal. `seon.client/quiesce-runtime!` honestly waits
up to `SEON_TURN_TIMEOUT_MS`, whose default is 900,000 ms. The containment
owner's current stop path has a much shorter bounded drain. Calling
`process/stop!` before the HTTP response would terminate a valid admitted turn;
using a five-minute HTTP read timeout would do the same. Conversely, giving
each retry a new 15-minute budget could wedge indefinitely. One absolute
deadline with a cleanup reserve closes both failures.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source grounding | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` | exact `reference-code/datahike`; `src/datahike/writer.cljc:40-73`; `src/datahike/connector.cljc:438-510` | Writer release has no internal deadline and joins admitted writer, secondary-index, out-of-band, and Konserve work. Only the typed terminal response proves its result. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` | exact `reference-code/konserve`; current Datahike release path | The operator never opens or closes Konserve independently. |
| Pod turn bound | ClojureScript `1.12.145`; current `seon.config/turn-timeout-ms` | `src/seon/config.cljs:1223-1232`; `seon.client/quiescence-deadline`; commit `3c2671a1` | A current admitted turn may legitimately consume 900,000 ms by default. New admission is already closed before the wait. |
| Pod lifecycle action | commit `3c2671a1` | `src/seon/client.cljs`; `src/seon/web/router.cljs`; `src/seon/web/serve.cljs`; focused client/router/serve tests | `POST /_seon/operator/quiesce` is loopback-peer-only, bypasses ordinary program admission, leaves HTTP alive for response flush, and returns one closed success/failure EDN value. |
| Portable pod lifecycle data | missing extraction from commit `3c2671a1` | current response registrations are only in `src/seon/client.cljs:2679-2700` | Babashka cannot require a `.cljs` application namespace. Move only the closed response data/schema into one `.cljc` owner; do not add state, a second coordinator, or a copied Malli approximation. |
| Writer terminal result | commits `272de2f3` and `00ab56ea` | `src/seon/db/{writer,server}.clj`; [[writer-terminal-result-transport-audit-2026-07-15]] | The in-process response is implemented; its generation-bound process transport is the prerequisite immediately before this coordinator. |
| Anchored containment | current uncommitted `seon.dev.process` and `detach.py` work, grounded by [[dead-leader-process-subtree-containment-2026-07-15]] | generation, owner/anchor/workload identities, adoption handshake, control socket, and atomic result | Only a matching requested result plus exact owner absence proves subtree absence. Missing owner/result evidence blocks replacement. |
| `babashka.process` | `0.6.25`, maintained SHA `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | exact `reference-code/babashka-process/src/babashka/process.cljc:420-451,680-713` | A `Process` deref waits for one child and shutdown hooks run callbacks, but neither carries Seon's application result or recovers a dead leader's descendants. Keep the persistent containment owner. |
| HTTP client | OpenJDK `26.0.1` `HttpURLConnection`, already imported by `seon.dev.process` | installed JDK source `java.base/java/net/{URLConnection,HttpURLConnection}.java`; current `process/http-ready?` | Connect and read timeouts are independent and redirects default on. The lifecycle client must set both explicitly, disable redirects, bound bytes, and disconnect in `finally`. No new HTTP dependency or `curl` subprocess is needed. |
| Lifecycle lock | current `seon.dev.state/with-lock` | `script/seon/dev/state.clj:50-76`; lock tests | Its timeout is acquisition-only. The held transition has no wall-clock bound and must receive a separate absolute deadline. |
| Launch and target identity | current `seon.launch`, `seon.dev.config`, and immutable process records | `src/seon/launch.cljc`; `script/seon/dev/{config,process}.clj` | Derive loopback port and runtime identity from the selected descriptor and exact live record. Never accept a caller URL, Host header, latest runtime, or ambient port. |

The repository does not pin `babashka.http-client`, and Babashka exposes no
source file for its built-in copy through var metadata. Adding that client only
for one local EDN request would introduce an unnecessary dependency boundary.
The JDK connection already used by process readiness supplies the required
timeouts, status, streams, and redirect control.

## One coordinator contract

Place the public reusable owner beside `process/stop!`; putting it in
`seon.dev.cli` would create a dependency cycle when retained branches consume
it. The request is data, and callers cannot choose order:

```clojure
(process/clean-or-force!
 {:seon.dev.process/configuration configuration
  :seon.dev.process/operation :seon.dev.process.operation/restart
  :seon.dev.process/targets
  #{:seon.dev.process/pod
    :seon.dev.process/writer
    :seon.dev.process/watcher}})

```

Register the request, process result, and aggregate result beside the existing
process schemas. Move the current pod response registration unchanged into one
effect-free `seon.runtime.lifecycle` `.cljc` data owner so `seon.client` and
Babashka validate the same value. The required process-side shapes are:

```clojure
;; Request
[:map {:closed true}
 [:seon.dev.process/configuration config/configuration-schema]
 [:seon.dev.process/operation
  [:enum :seon.dev.process.operation/down
         :seon.dev.process.operation/restart
         :seon.dev.process.operation/rebuild-readers
         :seon.dev.process.operation/rebuild-writer
         :seon.dev.process.operation/reset
         :seon.dev.process.operation/retained-close
         :seon.dev.process.operation/retained-restart]]
 [:seon.dev.process/targets
  [:set {:min 1}
   [:enum :seon.dev.process/pod
          :seon.dev.process/writer
          :seon.dev.process/watcher]]]]

;; One component result. The named refs are closed shared schemas.
[:orn
 [:absent
  [:map {:closed true}
   [:seon.dev.process/id qualified-keyword?]
   [:seon.dev.process/classification
    [:= :seon.dev.process.classification/absent]]]]
 [:watcher-clean
  [:map {:closed true}
   [:seon.dev.process/id [:= :seon.dev.process/watcher]]
   [:seon.dev.process/classification
    [:= :seon.dev.process.classification/clean]]
   [:seon.dev.process/terminal :seon.dev.process/containment-terminal]]]
 [:pod-clean
  [:map {:closed true}
   [:seon.dev.process/id [:= :seon.dev.process/pod]]
   [:seon.dev.process/classification
    [:= :seon.dev.process.classification/clean]]
   [:seon.dev.process/terminal :seon.dev.process/containment-terminal]
   [:seon.dev.process/application-result
    :seon.runtime.lifecycle/quiesce-response]]]
 [:writer-clean
  [:map {:closed true}
   [:seon.dev.process/id [:= :seon.dev.process/writer]]
   [:seon.dev.process/classification
    [:= :seon.dev.process.classification/clean]]
   [:seon.dev.process/terminal :seon.dev.process/containment-terminal]
   [:seon.dev.process/application-result :seon.db.protocol/terminal]]]
 [:forced
  [:map {:closed true}
   [:seon.dev.process/id qualified-keyword?]
   [:seon.dev.process/classification
    [:= :seon.dev.process.classification/forced]]
   [:seon.dev.process/reason qualified-keyword?]
   [:seon.dev.process/terminal :seon.dev.process/containment-terminal]
   [:seon.dev.process/application-result {:optional true}
    [:or :seon.runtime.lifecycle/quiesce-response
         :seon.db.protocol/terminal]]
   [:seon.dev.process/application-error {:optional true} :string]]]]

;; Aggregate result
[:map {:closed true}
 [:seon.dev.process/operation qualified-keyword?]
 [:seon.dev.process/classification
  [:enum :seon.dev.process.classification/clean
         :seon.dev.process.classification/absent
         :seon.dev.process.classification/forced]]
 [:seon.dev.process/deadline-ms pos-int?]
 [:seon.dev.process/results [:vector :seon.dev.process/stop-result]]]

```

Each closed stop result contains the process id and exactly one classification:

- `:absent`: no managed record, readiness artifact, or ownership conflict was
  present. It carries no fabricated terminal data;
- `:clean`: a requested, generation-matched containment terminal proves
  absence and the process's required application proof is valid;
- `:forced`: requested or unexpected containment still proves absence, but the
  required application proof is missing or invalid. Include a namespaced
  reason and every available terminal/application value;
- `:containment-uncertain`: this is not a normal returned success. Throw an
  `ExceptionInfo` whose data contains that classification, the completed
  prefix of stop results, and the retained current record. Do not continue to
  another process or clear evidence.

Watcher requires only requested containment absence. Pod clean additionally
requires the exact successful `::client/quiesce-runtime-response`. Writer clean
additionally requires the portable completed terminal whose server response
has `stopped? true` and all release rows validated. A workload-exit trigger is
always `:forced` with reason `:unexpected-exit`, even if a shutdown hook happened
to publish a successful application value.

Overall classification is `:absent` only when every requested component was
already absent, `:forced` when any component is forced, and otherwise `:clean`.
Component rows preserve the difference between clean and absent. This result
proves a stop, not an entire clean restart. After reopen, restart graduation
still verifies the same attachment and an equal or dependency-verified
descendant coordinate; UUID order and numeric `t` comparison alone are not
ancestry proof.

## Bounded loopback EDN client

Add one private `bounded-edn-post!` in `seon.dev.process`; do not use `curl`, a
new socket, or a second lifecycle namespace.

1. Under the already-held lifecycle lock, read the selected descriptor's exact
   HTTP port file and the exact pod record. Require the record's containment to
   be live immediately before the request. A missing record is `:absent`; a
   record/endpoint ownership conflict is uncertain, not an HTTP failure.
2. Construct only
   `http://127.0.0.1:<descriptor-port>/_seon/operator/quiesce`. The caller never
   supplies a URL.
3. Use `HttpURLConnection`; set method `POST`, connect timeout to at most 1,000
   ms, read timeout to the remaining clean-response budget, redirects false,
   `Content-Type: application/edn; charset=utf-8`, `Accept: application/edn`,
   and a zero-length body.
4. Accept status 200 as the success candidate. Parse 409, 500, and 503 through
   the same bounded EDN path so their typed failure is retained. Any other
   status is a typed HTTP failure. Read at most 1 MiB from either response or
   error stream; oversize and premature I/O are failures, never truncation.
5. Use `clojure.edn/read` with a `PushbackReader`, reject tagged literals,
   require EOF after the first form, and validate the exact closed
   `:seon.runtime.lifecycle/quiesce-response` schema shared with the pod. Do not
   accept the first prefix of a multi-form body.
6. Disconnect in `finally`. An HTTP exception or invalid response does not
   prove pod absence; proceed immediately to the generation-bound containment
   inverse and classify forced only if that inverse proves absence.

One retry is allowed only when the first request returns a schema-valid typed
failure while the endpoint and exact containment remain live. It uses the same
absolute deadline and may not extend it. Do not retry a read timeout,
connection loss, malformed response, or ownership change. The pod already
retains retryable cleanup capability; the operator supplies bounded policy,
not another Promise registry.

The lifecycle lock plus exact live record prevents another managed generation
from replacing this pod between selection and the request. The later matching
containment terminal remains the final generation proof. Do not widen the
already-implemented route with a second token or authentication mechanism in
this slice.

## One deadline, separate from lock acquisition

Derive the turn bound from the selected pod environment using the same positive
integer/default rule as `seon.config/turn-timeout-ms`; default to 900,000 ms.
At coordinator entry compute one monotonic deadline:

```text
deadline = now + selected-turn-timeout-ms + 120,000 ms

```

The 120-second reserve covers one pod response flush and five-second pod
containment grace, one 30-second writer application grace, one process-only
watcher drain, bounded connects, and scheduling variance. The exact shutdown
graces remain generation/spec data as specified by the writer transport audit;
the reserve does not rewrite them.

The HTTP clean-response budget may consume the selected turn bound plus 30
seconds, but must leave the rest for hard containment. Every later wait receives
`deadline - now`. If no time remains, request the already-bounded containment
inverse without granting another application wait; if containment itself cannot
publish its terminal within its retained generation policy, classify uncertain
and stop. Never convert an expired operator deadline into subtree absence.

Current lock waits remain appropriate because they answer a different question:

| Caller | Lock wait | Meaning after this change |
|---|---:|---|
| `up`, `restart`, reset | 1,800,000 ms | A contender may wait up to 30 minutes to acquire `:stack`; the holder still follows its own stop/build deadline. |
| `down`, config apply | 300,000 ms | A contender may give up after five minutes; it does not kill or cancel the holder. |
| retained branch open/close/restart | 30,000 ms | A concurrent branch command fails fast after 30 seconds; the holder may still finish a legitimate 15-minute branch-pod turn. |

Do not raise every lock wait to the turn bound and do not use lock timeout as
cancellation. A timed-out contender reports which lock it could not acquire;
the active holder remains the sole lifecycle owner.

## Caller ordering

The coordinator normalizes any target set to pod, writer, watcher order. It
never stops the shared writer for a retained branch.

| Public path | Exact composition |
|---|---|
| `down` | Under `:stack`, call once with pod + writer + watcher. Pod quiesce and absence precede writer TERM/application capture; watcher drains last. Print classification, not an unconditional clean claim. |
| `restart` | Under `:stack`, call once with the complete set, retain its result, then rebuild/reconcile. After readiness, verify attachment and coordinate relationship before reporting a clean restart. Do not call a second direct stop path. |
| live `up` / reconciliation | If readers must move for a build, call with pod + watcher in that order, then build. If the built writer digest changed, call writer-only before starting the new writer. A pod never reads partially rebuilt output, and the old writer remains available until its replacement artifact is known. |
| cluster reset | Under `:stack`, call pod + writer. Delete the explicitly selected database only after both subtrees are proved absent. A forced application classification is visible but the user's explicit reset authorization permits deletion; containment uncertainty forbids deletion. Watcher may remain and is reconciled against the newly built client artifact. |
| retained branch restart | Under that target's `:branch` lock, call pod-only with `:retained-restart`, then reopen the same retained intent. Never request source writer or watcher stop. |
| retained branch close | Under `:branch`, call pod-only with `:retained-close`; require proved pod absence before reading target head, releasing attachment, deleting the branch, or cleaning private paths. Forced pod absence permits explicit close but is not a clean claim; uncertainty retains the branch intent and forbids destructive writer requests. |
| startup unwind | Keep the existing exact inverses. An interrupted start did not enter the public planned-quiesce contract; unwind proves absence but never fabricates a clean operation result. |

`reconcile-development!` currently stops watcher before pod. Reverse that pair:
quiesce and remove the pod first, then stop the watcher, then build. Otherwise
an admitted pod can continue while its client-output owner is already gone.

## Evidence retention

Do not add a lifecycle database fact, clean bit, log parser, result registry, or
second result directory. Evidence lives in one immutable returned value during
the transition:

- `process/stop!` returns the complete normalized terminal before deleting the
  exact absent generation's private directory;
- `clean-or-force!` accumulates component rows purely and returns the closed
  vector;
- on uncertainty, `ExceptionInfo` carries the completed prefix plus the current
  retained record, while the on-disk process evidence remains untouched;
- branch callers retain their existing lifecycle record until every destructive
  inverse succeeds; and
- source-frozen graduation records exact returned values and reopened live
  coordinates in the PRD evidence, not in a new runtime authority.

If the operator crashes after clearing an absent generation but before the
caller records the return, the next start has absence but no clean claim. The
ordinary cold recovery path is idempotent and may no-op. Do not reconstruct
cleanliness from logs or PID absence.

## Exact focused tests

### `test/seon/dev/process_test.clj`

- bounded client derives only the descriptor-owned loopback URL, uses POST,
  disables redirects, sends EDN headers, and accepts a single schema-valid 200
  form;
- 409 typed failure is retained and receives at most one same-deadline retry;
  500/503, connect failure, read timeout, redirect, oversized body, blank body,
  malformed EDN, tagged literal, trailing second form, and wrong schema all
  select the forced path only after containment proves absence;
- exact pod record disappearance between selection and request never targets a
  replacement; managed ownership conflict is uncertain;
- target sets always execute pod, writer, watcher regardless of set order;
- pod clean requires both successful quiesce data and requested containment;
  successful HTTP plus unexpected process exit is forced;
- writer clean requires requested containment plus captured, digest-valid,
  generation-valid portable EDN with `stopped? true`; every failure cut from
  the writer terminal audit is forced after absence;
- missing/malformed/stale outer containment result retains the record, returns
  no later component action, and exposes the completed prefix in exception
  data;
- all-absent, clean-plus-absent, and any-forced overall classifications are
  exact;
- a fake monotonic clock proves retry and every later wait consume one absolute
  deadline rather than resetting it; and
- a 900,000 ms turn configuration yields a 1,020,000 ms transition budget,
  while injected short bounds make the test deterministic without sleeping.

### `test/seon/dev/cli_test.clj`

- `down` calls the coordinator once with the complete target set under the
  300,000 ms acquisition wait;
- `restart` calls it once before reconcile under the 1,800,000 ms acquisition
  wait and reports forced evidence honestly;
- reconciliation calls pod + watcher before build and calls writer-only after
  build exactly when the writer digest changed;
- no ordinary caller invokes `process/stop!` directly; and
- reset deletes only after pod/writer absence, permits an explicit forced reset,
  and refuses deletion on uncertainty.

### `test/seon/dev/branch_test.clj`

- restart and close call the same coordinator with pod only while holding the
  30,000 ms acquisition wait;
- the source watcher and writer receive no stop or lifecycle request;
- close emits no release/delete request before pod absence;
- forced pod absence may complete explicit close but is not called clean; and
- uncertainty retains intent, target branch, and private paths.

### Retained writer and pod gates

- retain the writer/server application-result tests specified by
  [[writer-terminal-result-transport-audit-2026-07-15]];
- retain the client/router/serve tests from `3c2671a1`, adding a real bounded
  HTTP fixture that blocks a current turn, proves the coordinator still waits
  beyond five minutes under an injected clock, then flushes the complete EDN
  before process drain; and
- run the combined process/CLI/branch gate only after containment and writer
  terminal source are frozen. Do not run overlapping CLJS suites in a live pod.

## Live graduation matrix

The source-frozen default checkpoint must record:

1. a blocked real turn survives planned restart until its body returns;
2. the pod response lists its terminal turn/run evidence and final complete
   coordinate;
3. exact requested pod and writer containment generations become absent;
4. writer terminal application data is captured and every release row is true;
5. restart reopens the same attachment at an equal or Datahike-verified
   descendant coordinate and admits later work; and
6. no unexpected-recovery mutation is required for the clean control.

Then inject three controls:

- missing or failed pod/writer application evidence with valid containment
  absence returns `:forced`, permits ordinary replacement, and makes no clean
  claim;
- workload exit before the request returns `:forced` with unexpected-exit
  evidence and runs the existing cold recovery path; and
- missing/corrupt containment evidence returns uncertainty, retains the exact
  record, refuses replacement, and performs no branch delete or database reset.

Only after these integrated controls pass can restore/undo, crash replacement,
or eval-child cutover consume this stop contract.
