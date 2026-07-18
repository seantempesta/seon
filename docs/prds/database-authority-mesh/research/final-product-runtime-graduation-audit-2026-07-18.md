---
type: research
status: complete
tags: [research, database, flow, agent, web]
---

# Final product and runtime graduation audit

## Conclusion

The architecture has strong evidence for every major runtime boundary, but the
current source is **not yet one exact graduated release**. The checkout audited
at `78694607faea22b2bbc7751acdb188e6d939b55a` is three production/test changes
ahead of the last complete maintained-test checkpoint and one production change
ahead of the last immutable source-free package and resource measurements.

The earliest missing contract is therefore an exact-current correctness
checkpoint: run the complete ClojureScript, writer, and operator doors from one
source freeze containing `4aa2f409`, then build one immutable package from that
same source identity. The five-minute execution-child retention change is only
a two-line behavior/test cut, but it changes process lifetime, post-load
reclamation, steady resident-child count, and package bytes. It **invalidates
the exact-source qualification** of the previous package/load proof. It does
not erase the older active-child memory measurements or the focused 17-test,
77-assertion and 55-second live retention proofs; those remain useful
comparative evidence.

After that checkpoint, the first genuinely missing product journey is the
complete canvas control matrix. A real cross-namespace canvas action and the
ordinary chat input work, but there is no retained exact-current browser proof
for input, select, toggle, form validation/failure, focus, and rapid submission
through the one reactive call/feed path. The final gate must not infer those
behaviors from renderer and transform unit tests.

## Audited source identities

- Current checkout: `78694607faea22b2bbc7751acdb188e6d939b55a`.
- Last complete maintained gates: `fa9e1eb9b184e39360009e79ef1fec1f1cd95430`
  recorded ClojureScript 1,140 tests/5,078 assertions, writer 219/1,821, and
  operator 278/1,570, all green.
- Last exact-current immutable package before the retention change:
  `706abf3dc2c48cb8e4ca23132862831e083147fb`, application digest
  `0d8bc9c2ff2088de3103f951d1bd3f94f96d2c80cb4f4ccf6a035aaa9f96197b`.
- Last current five-child resource record before the retention change:
  `cb770db083255e7bf808b57c4585bfcc20768f2e`.
- Query sharing proof: `bc3ba0a6e9ab207e25af6f2eee27ca013959e5c9`.
- Datastar fanout/backpressure proof:
  `117e7064f4d425af7037b5987f6768e3f9ed7d7f`.
- Five-minute retention implementation:
  `4aa2f409ef829d8749685174d213ded341d62644`.
- Current source differs from `9df21b23` in production code at
  `src/seon/execution/host.cljs`; the other executable-test change is
  `test/seon/execution/host_test.cljs`. The authority-density test repair does
  not alter production runtime behavior.

## Requirement audit

| Requirement | Authoritative evidence | Freshness / exact source identity | Verdict | Exact next gate |
|---|---|---|---|---|
| Maintained ClojureScript gate | Runtime roadmap records 1,140 tests/5,078 assertions at `fa9e1eb9`; focused host proof after the retention cut passes 17/77. | Complete result predates `4aa2f409`; no complete CLJS result is recorded for `78694607`. | **Incomplete** | Freeze `78694607` or its reviewed successor and run the complete `bin/test-cljs`; retain artifact/source digest and full counts. |
| Maintained JVM writer gate | Runtime roadmap records 219 tests/1,821 assertions at `fa9e1eb9`. Query-sharing repair later passed its focused 1 test/51 assertions. | Production writer source is unchanged after the complete gate, but final graduation requires one common exact-source checkpoint rather than compositional inference. | **Incomplete** | Run `bin/test-writer` in the same source freeze as CLJS/operator and record the shared artifact identity. |
| Maintained operator gate | Runtime roadmap records 278 tests/1,570 assertions at `fa9e1eb9`. | Operator source is unchanged, but the current runtime policy changes the process lifetime the operator must observe. | **Incomplete** | Run `bin/seon test operator` against the same frozen artifact and retain the result with the package manifest. |
| Browser root page | Real browser checkpoints rendered root without console errors; current-package proof rendered root from the relocated package. | Strong proof through `706abf3d`; `4aa2f409` changes child retention used by root rendering, so the final exact-current repeat is absent. | **Incomplete** | Open root from the new immutable package, wait beyond 30 seconds, trigger a relevant database change, and prove one warm morph with no console/core fault. |
| Browser ordinary agent page and chat input | Browser created an ordinary agent, submitted through the visible Datastar chat form, observed input clear and running-to-idle morph, and received the exact reply. Source-free package also ran a real agent and read it back after restart. | Mechanisms are proven before `4aa2f409`; no exact-current package browser repeat. | **Incomplete** | From the exact-current package create an agent, submit through the visible input, observe terminal reply, stop/destroy it, and prove the five-minute policy does not retain an unwanted child. |
| Browser `/data` | Headless Chrome loaded `/data` with a complete morph and no page/console errors; current package served `/data`. | Proven through `706abf3d`; current package identity is stale. | **Incomplete** | Include `/data` in the exact-current package browser matrix and retain console/network evidence. |
| Browser `/agent/{id}/debug` | Headless Chrome rendered root debug; server-side gzip decoded the complete prompt; current complete browser checkpoint reported no page errors. | Proven on earlier frozen artifacts; no post-`4aa2f409` immutable-package repeat. | **Incomplete** | Load a real ordinary-agent debug page from the exact-current package after work and after restart; verify prompt/turn reconstruction and no render error. |
| Canvas rendering and cross-namespace action | `fdb1e718`/roadmap evidence: Chrome rendered `my.interaction.view/view`, posted through `my.interaction.actions/save!`, committed the exact value, and the open feed morphed without reload or console error. Focused canvas proof is 9/33 and execution-runtime 13/71. | Production canvas/feed code is unchanged after that proof, but the proof is not from the current package. | **Incomplete** | Repeat one cross-namespace action from the exact-current immutable package and read the committed fact after restart. |
| Complete canvas input/control behavior | Transform/call tests cover the standard Datastar post and pure-data decoding. The canvas source audit explicitly says unit tests do not prove the real control behavior and lists input/select/toggle/form, invalid/rejected/rapid, focus, and layout gates. | No retained live proof covers that full control matrix on any final release. | **Missing** | Browser-drive button, text input, select, toggle, multi-control form, native invalid input, rejected call, rapid repeat, focus preservation, and final database facts through the one canvas call/feed path. |
| One Datastar subscription identity and shared render | Sixteen equivalent root sockets normalized to one subscription and shared one render plus one 27,185-byte event. Unit tests cover same-database active/completed render reuse. | Exact at `117e7064`; production Datastar source is unchanged afterward. The mechanism is proven, but not yet composed into the final package checkpoint. | **Proven** | Recheck the metric once during the exact-current package fanout sample; investigate only if it regresses. |
| Gzip negotiation and delivery | Configured remote gzip used one Bun-native compression stream; 27,185 bytes compressed to 2,535 bytes, heartbeat followed on the same connection, Chrome morphed it, and `gzip;q=0` refused gzip. Focused proof 15/58. | Transport source predates but is unaffected by `4aa2f409`; exact-current package composition remains unproven. | **Proven** | Include identity, gzip, and `gzip;q=0` in the new package matrix without redesigning the feed. |
| Feed reconnect across pod restart | Same browser tab survived full supervised restart and posted/morphed without reload; server-side retry also reconnected and received a complete event. | Strong behavioral proof on prior frozen source; final package after retention policy absent. | **Proven** | One exact-current package restart/reconnect is sufficient confirmation. |
| Fanout under backpressure | Twenty 1 MiB events to a non-reading client retained one newest pending event, replaced 18 obsolete values, and drained in at most 2.465 ms. Eight gzip feeds later shared one render and event. | Exact at `117e7064`; host retention change does not alter the direct-stream pending-value owner. | **Proven** | Retain as regression evidence; run one bounded final-package non-reader sample only if the package/transport bytes changed. |
| Database-driven selective rerender | Unrelated attribute caused zero render; learned relevant attribute caused exactly one render. The 55-second retained-child repeat produced one render and one accepted write in 31.2705 ms. | Live proof is explicitly post-`4aa2f409` and current for the affected behavior. | **Proven** | Preserve metric snapshot with the final package browser run; no new cache or renderer is justified. |
| Real multi-agent execution and isolation | Four simultaneous agents ran in distinct children and completed exact replies; another failure-load sample kept siblings and pod healthy. Latest current-resource sample kept root plus four task children and all exact replies. | Strong before `4aa2f409`. Active execution isolation remains valid, but post-work idle/reclamation semantics changed from 30 seconds to five minutes. | **Incomplete** | Run 1/2/4 task agents from the exact-current package; prove overlap, exact outcomes, one failed child isolation, and distinguish explicit stop/destroy from five-minute idle retention. |
| Child crash breaker and recovery | Immutable package ran exact `(js/process.exit 1)` twice, retired both children, prevented a third attempt, kept pod responsive, then completed later work in a fresh child. Non-cooperating loop evidence also recorded recovery blobs and one root message. | Crash mechanics predate `4aa2f409`; timeout only changes healthy idle retirement, not exit settlement. Package identity is nevertheless stale. | **Proven** | One bounded exact-current package crash plus later successful work confirms packaging; do not repeat the full exploratory matrix. |
| Bun pod and JVM writer restart/read-back | Relocated package changed both generations, read committed `42`/later exact replies through Datastar, and kept the package byte-identical. Writer-only recovery retained healthy readers and public work resumed. | Exact package proof is `706abf3d`, before current host bytes. | **Incomplete** | Restart the new read-only package, reconnect the existing browser/feed, and read back the pre-restart agent result. |
| Ambiguous transaction delivery and listener restoration | Live accepted-response-loss probe returned `recovered-commit? true` with one fact; focused tests cover frozen redelivery, reconnect coalescing, and listener restoration. | Database client/writer source unchanged after proof. | **Proven** | Retain as composed evidence; final restart journey should show ordinary continued work, not recreate the fault probe. |
| Grown database/transcript | Fifty turns/400 evals with 16,384-character fields reproduced and fixed the result-weight failure; restart served a 75,408-byte healthy complete patch; focused proof 19/77. | Production grown-transcript owners unchanged after this proof; exact-current package was built after the fix but before `4aa2f409`. | **Proven** | Run one grown-page feed from the exact-current package to confirm artifact composition; no new grown fixture is required absent regression. |
| Two autonomous clusters on one JVM writer | Default plus sibling cluster had distinct databases/pods/ports, isolated writes and agents, shared one writer, restarted/closed only the sibling, and reopened config-free. Current ACME proof likewise shared one writer and isolated restart. | Runtime/operator owners unchanged after the evidence; current package did not repeat multi-cluster after `4aa2f409`. | **Proven** | Include a short exact-current two-cluster concurrent read/write sample if the final package supports the cluster operator; otherwise retain the source-frozen operator evidence explicitly. |
| Source-free immutable package | `706abf3d` records application digest `0d8bc9c2…`, no watcher/dev runtime, external mutable state, real agent, browser root/data/gzip, restart/read-back, clean down, and stable tree digest. Earlier release also ran from recursively read-only storage. | **Stale**: `src/seon/execution/host.cljs` changed at `4aa2f409`, so package bytes and process lifetime no longer match current source. | **Contradicted** as an exact-current claim | Build a new package from the frozen current source, relocate it read-only, deny producer checkout/dev tooling, run the product matrix, verify the tree before/after, and shut down normally. |
| One/two/four-child memory and load | Release `4073c7fa…` measured fixed 827.3 MiB, one/two retained below 250 MiB, four children 174.1–222.3 MiB, complete about 1.66 GiB. `cb770db0` strengthened this with root plus four task children and about 1.72 GiB. | Active-child footprints remain relevant. The five-minute default changes how many healthy children remain resident after work and invalidates old cleanup/steady-state conclusions. | **Incomplete** | On the new package measure cold fixed, 1/2/4 active, and post-work at 30 seconds, five minutes, and explicit stop/destroy; report physical footprint/peak, JSC/JVM heap, CPU, event-loop delay, GC, and child registry/OS counts. |
| Query-cache reuse across pod and independent Bun clients | Direct warm Datahike p50 0.0188 ms; full Bun/UDS/JVM p50 1.033 ms and p99 2.936 ms; 32 simultaneous calls yielded one owner/31 hits; eight Bun processes yielded one owner/seven joined callers. | Exact at `bc3ba0a6`; authority-density test repair is current and production query owners did not change. | **Proven** | Preserve as baseline; only rerun if final package shows latency/resource regression. |
| Cleanup and bounded retained resources | Prior four-agent drives showed children leaving registry/OS after 30-second idle grace; package shutdown drained children/pod/writer. | The current default is five minutes. The old automatic-reclamation timing is no longer current behavior. | **Contradicted** for the old 30-second expectation | Define evidence by state: active, five-minute idle, dormant after timeout, explicit stopped/destroyed. Prove each returns the expected registry, OS, database-reference, and memory state. |

## Earliest ordered gates

1. Freeze the current reviewed source and pass all three complete maintained
   gates. This is the earliest unsettled contract because every later exact
   artifact claim depends on it.
2. Build one source-free immutable package from that identical source identity.
3. In that package, run the compact browser matrix for root, agent, data,
   debug, cross-namespace canvas action, reconnect, restart, and read-back.
4. Add the genuinely missing complete canvas-control matrix rather than
   inferring it from transform tests.
5. Run exact 1/2/4-child execution/failure and resource samples under the new
   five-minute policy, including explicit reclamation and post-timeout state.
6. Reconcile the already-proven gzip, fanout, backpressure, grown-database,
   query-sharing, crash, multi-cluster, and ACME evidence with that exact
   package. Repeat a gate only where source identity or changed semantics make
   the existing evidence stale.

The final graduation claim is valid only when those results name one source
commit, one release manifest/application digest, and the same immutable package
tree before and after the complete runtime journey.
