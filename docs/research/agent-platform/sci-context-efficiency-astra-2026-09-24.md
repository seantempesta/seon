---
type: research
status: measured; read-only; implementation frozen
created: 2026-09-24
---
# Exact SCI contexts: measured costs and the smallest compositions

**907.428 ms / 3,305,660,288 allocated bytes** to derive a fresh base for **one override**.
**16.661–17.580 ms / 53,550,416–53,550,480 bytes** for the same warm reverse closure.
**0.006 ms / 16,312 bytes** for SCI's fork. The expensive work surrounds the fork.

Seon already composes database program rows, a carried projection, compiled JVM roots and SCI forks.
The principal approach is to retain those values and update their actual changed inputs, rather than reconstruct their populations.

## Measured evidence and limits

Measurements: 2026-09-24, approximately 14:01–14:13 UTC; MCP root `/Users/sean/src/seon`, cluster `default`.
JVM PID **90471**, started **2026-09-23T22:18:14Z**. Both `bin/seon status` and MCP `runtime_status` answered.
Broken first: status reported **16 error signatures, 34 errored receipts, 1 failed run, 1 failed test of unknown currency**.
Hook publication was **off**: the JVM runs archive **ce73846828a5cc32798ef630b5a574f777646f30**, not checkout HEAD **8a881e236**.
No restart, adoption, default Var replacement, program transaction, provider request, test gate or implementation edit was performed.
The independent researcher's report was not opened. Only this report is written.
All `src/` references below mean the installed archive at ce738468 unless explicitly labeled checkout.
SCI **fcbd8862800e638dc0f8f5521111f999279cbcd2**, Datahike **c79cd03a44427ac1734d917c7484c3e529c77716**,
Malli **8725a8cbd9d595f4a970ce53a2eefdbe7211b96d** were verified against their checkout HEADs and repository gitlinks.
Live Var metadata confirmed `seon.sci.eval/acquire!` at **eval.clj:2640**.
Default database commit **6ab48b28-dcd7-55ee-b68a-d8f7491face7**, basis **536872648**;
loaded-program commit **6ab41567-108f-58d6-b79e-4c4b6ccbe032**.
Digest maps contained **4,751 loaded / 4,752 branch functions**; the sole override was `my.agents.root/largest`.
Its reverse closure returned exactly `#{my.agents.root/largest}` and test `my.agents.root/largest-test`.

| Direct operation, measured in the running JVM | Wall ms | Allocated bytes | Scope |
|---|---:|---:|---|
| Loaded / branch function digest maps | 7.849 / 5.368 | 19,234,368 / 15,287,520 | Both complete maps |
| Reverse closure, first measured | 813.168 | 3,048,562,880 | One seed, one function, one test |
| Fresh `derive-base-ctx` | 907.428 | 3,305,660,288 | Bypasses only base memo; normal armed owners |
| Minimal `build-base-ctx` | 1.278 | 1,940,200 | Before acquiring program |
| Carried `projection-from-database` | 0.043 | 59,512 | Existing content |
| `call-with-projection` around nil | 0.013 | 21,952 | Binding overhead, not its callback |
| `base-ctx` memo hit, two calls | 3.321 / 2.981 | 6,033,104 / 6,032,832 | Returns owned fork and kernel atoms |
| Raw `sci/fork` of populated base | 0.006 | 16,312 | Shared namespace map |
| `fork-for-turn`, first / retained regeneration | 5.227 / 6.495 | 5,378,968 / 4,591,608 | Local contexts, agent id root |
| `turn-intern-values`, one scan | 3.470 | 4,550,064 | Result empty; scans inherited population |
| `acquire!`, unchanged program | 0.594 | 526,216 | Local base |
| `acquire-context!`, retained exp-mcp-check | 0.392 | 787,168 | Actual agent entrance, nil agent id |
| Fresh branch's first `acquire-context!` | 76.948 | 116,099,896 | Same program, no base rebuild |
| Warm reverse closure, three consecutive calls | 17.580 / 16.661 / 16.745 | 53,550,480 / 53,550,416 / 53,550,416 | Same immutable database |
| Warm identity query / file-reference query | 2.054 / 5.358 | 7,787,032 / 21,349,928 | 6,859 / 19,232 tuples |
| All declared edges, warm | 0.236 | 487,032 | 26 edges |
| Same declared-edge rules, target bound, first query | 803.382 | 2,991,929,680 | Empty result; binding alone does not fix cost |
| AVET calls / references / subjects for largest | 0.078 / 0.028 / 0.024 | 82,312 / 81,544 / 81,624 | 1 / 0 / 0 datoms |

Fresh branch `exp-astra-context-20260924` was created in **24 ms**, acquired once, and retired in **12 ms**.
Those two numbers are MCP prepl elapsed times; their allocation was not measured.
Its first entrance's inclusive profile: open-branch **8 ms**, fork-cluster-ctx **62**, database-effective-config **53**, acquire! **3**.
Fresh derivation's inclusive profile: acquire-program! **903 ms**, reverse-closure/gate-sets-in **815**, declared-reference-edges **784**,
db/q **830 ×13**, with-declarations **899 ×907**, pulls **39 ×441**, digest maps **12 ×2**, install-row! **6 ×2**.
`call-with-projection` totaled **1,808 ms ×2 inside a 907-ms operation**: these are nested inclusive counters, not additive self times.
The stage allocations above were measured directly; allocations for nested profile-only stages were not separately isolated.

SCI MCP, branch `exp-mcp-check`, namespace `scratch.astra-context-20260924`:
`(+ 1 2)` → **3**, **5 ms / 9,592,888 bytes**;
`(seon.id/digest 12 ["abc"])` → **"02f393ea9358"**, **5 ms / 9,684,520 bytes**;
`(dotimes [_ 100] (seon.id/digest 12 ["abc"]))` → nil, **9 ms / 18,467,136 bytes**.
These are the evaluation arm's counters, excluding acquisition before entry and some later result processing
(`src/seon/sci/kernel.clj:43,193`; `src/seon/sci/eval.clj:3406,3570`).
They do **not** support “53 MB for every contracted invocation.”

JVM probes used nanoTime and ThreadMXBean current-thread allocation; bytes mean cumulative allocation, **not retained heap**.
The bean was enabled. No GC was forced; JIT/cache/background variation remains. Most stage samples are single observations, not percentiles.
Profiler totals include concurrent threads and instrumentation of its own explanation (`src/seon/profile.clj:178,204`).
All large results were reduced to counts/scalars before returning. No operation here exceeded one second; the earlier 3.196-second figure was not reproduced.
The earlier 330 MB figure was not reproduced either: this fresh derivation allocated **3.31 GB**, while a warm new branch allocated **116 MB**.
The assignment's “every new commit rebuilds” premise is too broad: the installed revision has an unchanged-program fast path.

## Actual acquisition pipeline and its scaling

1. **Select branch and capture head.** `cluster.agent/acquire-context!` looks up [branch agent-id], opens/borrows the connection,
   carries its projection, then obtains its base (`src/seon/cluster/agent.clj:779,821,845,873`).
   A Datahike branch is shared immutable roots plus a roster entry, not a program copy
   (`reference-code/datahike/src/datahike/versioning.cljc:235`). Opening/config acquisition and interpreter construction are separate costs.
2. **Check program currency.** `acquire!` calls `acquired-database?`, checking loaded-source authority and selected attribute revisions
   (`src/seon/sci/eval.clj:2537,2555,2640`). Same program advances custody/projection without reinstalling definitions.
   The commit-id memo has 256 entries; the base memo has four (`:2336`).
   Its actual key is **[restricted revision basis, loaded commit, fault-recorder callable]**, not just branch commit (`:2466`).
   Selected attributes include the program partition, projection dependencies, configuration dials and adoption record (`:2358`).
3. **On a miss, start over.** `derive-base-ctx` obtains the projection, builds the minimal interpreter and calls `acquire-program!`
   (`:2438`). That owner reads all function/namespace populations, both function digest maps, global dependency joins,
   every namespace row, selected tests, resolver maps and namespace ordering (`:1881–2070`).
   It copies matching host roots and interprets differences plus reverse callers (`:2103–2120`).
   Cost is O(program rows + resolver rows + global joins + affected source), even for one override.
4. **Copy and arm.** `copy-base-ctx` forks SCI and supplies owned kernel atoms and custody (`:2484`).
   `install-first-party-namespaces!` actually copies JVM **roots** through `copy-host-var`; it does not install raw host Vars
   (`:189,1437`). Interpreted contracts are installed through `install-function-contract!` (`:708`).
   Agent acquisition with non-nil agent id calls `fork-for-turn`; a retained agent still scans/reconstructs its private layer
   (`cluster/agent.clj:895`; `sci/eval.clj:2239,2302`). MCP's nil-id retained base does not pay that per-turn reconstruction.
5. **Per evaluation.** `evaluate` scopes the environment, carries projection/custody, checks acquisition, reads the form,
   scans intern roots before evaluation, derives definition rows afterward, then scans changed private bindings and admits/renders the result
   (`sci/eval.clj:3234,3320,3411,3439,3524,3574`).
   `turn-interns` first builds **all** intern values, only then filters generation ownership (`:363–415`).
   This is O(all visible bindings) work on an arithmetic expression.
   The call-preparation hook separately examines direct Var calls; its snapshot already has revision/commit memoization
   (`src/seon/call_preparation.clj:551,608,1451`). Do not replace that with another listener/cache.

## Root causes, ranked

**One pattern: deriving a small answer by reconstructing or rechecking a much larger surrounding world.**
Existing defect class: [a one-form resume takes seconds](../../seon/issues/a-one-form-resume-turn-takes-seconds.md).

1. **Whole-program dependency preparation dominates misses.** One-function closure pays for 6,859 identities and 19,232 file-reference tuples,
   plus rules that cost 784 ms in fresh derivation. The existing indexed frontier is cheap; global preparation precedes it
   (`src/seon/fn.clj:1520,1531,1556`). A target-bound rule query still took 803 ms: “add an input” is not a demonstrated fix.
   Dependency query-result caching makes the declared-edge query cheap when warm, but Seon still decodes/validates/materializes large relations.
   The two warm population queries alone allocated 29.14 MB; the complete warm closure allocated 53.55 MB.
   Allocation has not been subdivided into query execution, decoding and contract validation; attributing all of it to arming would be unsupported.
2. **Invalidation granularity is broader than executable content.** Attribute revisions prove that some relevant input changed, not that every
   declaration needs rebuilding. Equal content restored later still has a newer revision. No delta continuation exists in the miss path
   (`sci/eval.clj:2377,2466,2640`). The four-entry cache can evict an otherwise valid base.
   Datahike revisions are valid only within their connection/generation (`reference-code/datahike/src/datahike/query.cljc:2999`).
   Keep that fast rejection test; follow it with changed-identity/content comparison rather than treating it as a content digest.
3. **Global private-state discovery is repeated without a change.** A scan allocating 4.55 MB returns zero private values here;
   ordinary successful evaluation executes several related scans (`sci/eval.clj:411,426,474,3439,3526`).
   Raw fork costs microseconds; regeneration is Seon's own O(program bindings) work.
4. **Contract context selection remains per call.** Host wrappers inspect supplied arguments/projection and retain just their latest projection wrapper
   (`instrument.clj:551,744–811`), so alternating different projection objects can recompile content-equivalent contracts.
   This is a source-proven risk, not the measured cause of the 53.55 MB closure.
   The measured carried projection and empty projection binding are cheap. Large inclusive projection totals must not be described as rebuilding totals.

## Target composition: one host base, branch differences, retained private state

**Build once per loaded JVM program**, then update changed declarations on reload.
Construct a base with every admitted namespace's resolver facts and host bindings, including private declarations.
Install actual host Vars where their definitions **and transitive compiled dependencies** agree with the branch.
SCI supports host Var call positions (`reference-code/sci/src/sci/impl/analyzer.cljc:62,1783`); the live scratch-context probe
`{:namespaces {'seon.id {'digest #'seon.id/digest}}}` returned the expected digest.
`sci/init` creates the context; `sci/fork` creates a new env atom over persistent namespace data
(`reference-code/sci/src/sci/core.cljc:331,345`). The official [SCI API](https://github.com/babashka/sci/blob/master/API.md)
documents init/fork/namespace injection; the pinned source supplies the exact guarantees used here.
`copy-ns` copies public Vars' roots, omitting private ones by design; it is not a complete program/resolver importer
(`core.cljc:445,492`). `merge-opts` mutates the existing env through `init-env!`, so use it only on an owned fork,
never as an immutable-base replacement (`impl/opts.cljc:335–383`).

**Complete namespaces independently of interpretation selection.** The live branch reported `seon.note` aliases **{}** with **21 interns**.
Aliases/imports are installed only for selected namespaces; `_namespace-rows` is ignored in the host installer
(`sci/eval.clj:1453,1953,2103`). Install aliases/refers/imports for all namespace rows once, then update changed resolver facts.
Refers must point into the receiving context's bindings. Macro/resolver changes invalidate analysis of their affected declarations.

**At each idle turn boundary, capture one head, projection and effective config.**
If the exact commit/loaded program is unchanged, return the retained context.
Otherwise use the writer's effective tx-data to collect changed declaration identities, component owners and resolver/schema dependencies;
compare their stored definition digests against the held host-program map. Remove equal rows from the override layer; retain explicit deletions.
Compute incoming closure from old **and** new edges, including ordinary calls, references, declared invokers, capabilities,
unresolved file references, macros and namespace bindings. Interpret only the affected closure; reuse all unaffected bindings and validators.
The existing gate frontier and four declared-edge rules define semantics to preserve (`fn.clj:1458,1531`), not permission to drop rare edges.
Target work is O(changed datoms + reached edges + changed source); a genuinely global dependency change legitimately reaches the whole program.

**Use the existing diff owner, but state its limits.** `program/digest-map` supports scoped identities (`src/seon/program.cljc:449,510`).
`program/changed-identities` uses history/since to include deleted declarations but currently refuses changes outside functions/tests
and scans digest history (`:578–619`). Extend that owner to the declared program partition; do not add a parallel diff registry.
`since` is a time predicate on indexes, **not a transaction-prefix index** (`reference-code/datahike/src/datahike/db.cljc:729`;
`db/search.cljc:138`). Thus history recovery is bounded O(relevant retained history), not guaranteed O(delta).
The proportional live path consumes writer reports already delivered by `d/listen`; missing reports require explicit recovery.
Cross-branch comparison requires proven common ancestry, not comparing unrelated basis-t values
(`reference-code/datahike/src/datahike/versioning.cljc:191`). Cache derivations by the content they read or dependency evidence;
never writer metadata stamps or a second mutable program mirror.

**Merge changes cluster rows, not the JVM.** After the combined branch passes the existing tested-head acceptance gate,
the cluster's delta against the host includes the newly accepted rows; agent-private objects do not merge.
Use existing `prepare-merge!`/`accept-merge!` (`src/seon/cluster/source.clj:736,786`).
At subsequent idle boundaries, agents apply that cluster delta to their retained contexts.
Config overrides remain validated data on the environment; the current owner already reads config from the database/projection
(`src/seon/config.clj:752`). Separate live dials from arm-time policy: only changes affecting a wrapper require rearming.
Changing a rendering/budget dial must not recopy every program binding.

**Reload requires a JVM-wide boundary.** Before `require :reload`, fence admitted evaluations, determine the changed host declarations
and their callers, and prepare older clusters' interpreted replacements. After reload and arming, publish the new host base/adoption record,
then advance eligible contexts at idle boundaries. Raw JVM Vars are globally mutable; capturing yesterday's root still calls today's indirect Vars.
Older clusters must interpret the closure differing from the new host. If that includes a host-bound declaration,
the simultaneous realities cannot be guaranteed by SCI: refuse the incompatible reload/adoption by name or retain a separate process.
This is the isolation requirement in `docs/seon/architecture/agent-runtime.md:34–47`, not solved by a cache key.

**Retain the agent fork; update program bindings, never rediscover private objects.**
Private defs, atoms, results and lexical captures live only there. A changed committed definition wins on an identity collision and reports it
(`docs/seon/architecture/agent-runtime.md:28`). Accepted evaluated roots transfer without replaying initializers.
But native fork alone fails the desired Var-call behavior: base f=1; fork defines private-call→f; redefining f to 2 then 3
returned **[2 1] then [3 1]**. The private atom remained identical and base still returned 1.
Owning f's SCI Var **before analyzing private-call**, using `sci/bind-root!`, returned **[2 2] then [3 3]** with the same isolation.
Cause: analysis captures a Var; copy-on-write replaces an inherited Var, not references already captured
(`reference-code/sci/src/sci/impl/utils.cljc:362`; `impl/analyzer.cljc:1789`).

The remaining design choice needs independent review, not an invented guarantee. Exactly three priced options:

| Option | Guarantee and engineering estimate | Sacrifice |
|---|---|---|
| Pin a private context's program until explicit re-evaluation | Simplest constraint; ~0.5 day | No transparent adoption into private closures |
| **Recommended: lazily own referenced bindings before analysis** | ~1–2 days, several ≤100-line slices; reuse SCI copy-on-write and update only reached bindings | Must prove aliases, Var values, dynamic resolve, recursion, macros and callbacks; the toy proof is insufficient |
| Eagerly own all program Vars once per agent | ~0.5–1 day plus same behavior tests; direct Var references have stable owned cells | O(program) agent creation; violates the cheap-fork target |

The recommended route belongs at SCI's common symbol-resolution owner (`impl/resolve.cljc:205,250`), if Seon's existing analysis cannot
supply the complete reference set. No installed “own-on-reference” hook was found. Do not misuse the direct-call preparation hook:
it excludes computed callees/self-reference and cannot repair previously captured values (`core.cljc:310–319`).
Ordinary lexical captures of function *values* remain captured values; preserving them differs from keeping Var dereferences current.
SCI permits protocol-only deftype: the live isolated probe returned **42**; `definterface` refused resolution.
These are not equivalent to generating a JVM class (`impl/deftype.cljc:314,524`). Class admission/host-bound facts must stay per declaration.

## Contracts, deletion and staged proof

Malli already acquires input/output validators when creating the wrapper, then validates values on calls
(`reference-code/malli/src/malli/core.cljc:2202–2220`).
Reuse compiled schemas/validators by contract content, referenced schema/predicate content and policy; pair wrappers with the actual callable identity.
Keep per-context fault recorder/custody separate from that immutable compiled content. At evaluation entry acquire this contract world once,
and pass it through; do not infer it anew from arbitrary arguments on every leaf call.
Input validation still happens on every call and prevents body entry; output/guard validation still happens after it.
`instrument/compiled-wrapper` already composes `m/-instrument` and throws from the report callback (`:638–689`).
Private contracted defs must arm immediately after successful admission and before exposure, without waiting for row settlement:
the current arm seam is installation (`sci/eval.clj:708,1038`), whereas MCP only evaluates (`script/seon/dev/mcp.clj:518`).
The existing [entry study](three-entry-defects-2026-09-24.md:157) documents the resulting unarmed private function.
I rechecked that producer path, not a new private-def admission; no false claim of a fresh end-to-end contract proof.

Proposed slices are estimates, not implemented patches:

| Slice / existing owner | Delete / add source lines | Measurable proof |
|---|---:|---|
| First: filter generation ownership before building intern-value maps, `sci/eval.clj:382` | ~20–35 / 12–20 | Local equivalent probe: **3.431 ms / 4,550,192 B → 0.946 ms / 980,416 B**, both {} |
| Stop global identity/file-reference materialization in reverse closure, `fn.clj:1531–1597` | ~45–65 / 40–70 | Same closure, cold and warm; retire **53.55 MB** warm population work; prove all four declared-edge rules and deleted seeds |
| Host base + changed-content continuation, `sci/eval.clj:1881,2438,2640` | ~150–250 / ≤90 per slice | One no-op/data change installs zero rows; one override visits only its actual closure; compare against **907 ms / 3.31 GB** |
| Retained agent context after binding-isolation proof, `sci/eval.clj:2218–2334` | ~90–115 / 25–50 | Remove **6.495 ms / 4.59 MB** unchanged-turn reconstruction; atom identity and two updates |
| Contract content reuse and immediate private arming, `instrument.clj:774–803`, `sci/eval.clj:3524` | ~20–40 / 25–50 each | Alternate two equivalent projections without new compilations; invalid private input never enters body |

The first prototype still scans namespace entries: it is an allocation reduction, not the final O(private changes) design.
Do not skip scanning merely because the top-level form is not def: invoked code may intern/redefine Vars.
Retiring all scans requires complete definition/mutation observation at the SCI owner; no such general journal was verified.
The closure slice must not assume the target-bound rule query is fast: the measured 803-ms failure rules that shortcut out.
The later slices remove regeneration, redundant program maps/digest scans and broad revision misses; none introduces a second renderer, registry or scheduler.
No aggregate line-saving promise is made before reviewing actual diffs; each slice remains below ~100 added lines.
Future proof must include deletion/re-addition, restored equal content, schema/config changes, aliases/refers/macros, host-bound refusals,
two successive base advances, old-cluster indirect calls and private atom identity. No production before/after improvement is claimed.

## Reproduction forms (committed here because only this file may be written)

Use MCP JVM mode, explicit root/cluster above, namespace/session `scratch.astra-context-20260924` / `astra-context-20260924`.
This local harness returns the value plus timing/allocation; do not return contexts/databases through MCP:

```clojure
(let [d @(seon.cluster.boot/connection "default")
      p (seon.db/carried-projection d)
      loaded (#'seon.sci.eval/loaded-program d)
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      m (fn [label f]
          (let [b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
                t (System/nanoTime) v (f)]
            [v {:stage label :ms (/ (- (System/nanoTime) t) 1e6)
                :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) b)}]))
      mark (seon.profile/begin)
      [ctx result] (m :derive #( #'seon.sci.eval/derive-base-ctx
                                d {:seon.sci.eval/loaded-database loaded}))]
  {:measurement result
   :installed (get-in ctx [:seon.sci.eval/acquisition :seon.sci.eval/installed])
   :profile (:seon.profile/lines (seon.profile/explain mark 18))})
```

Other timed expressions under the same harness: `(#'seon.sci.eval/function-digests loaded)`, same with d;
`(seon.fn/reverse-closure {:seon.db/db d :seon.fn/seeds #{'my.agents.root/largest}})` three times;
`(seon.sci.eval/build-base-ctx p)`; `(seon.schema/projection-from-database d p)`;
`(seon.schema/call-with-projection p (fn [] nil))`; `(seon.sci.eval/base-ctx d)` twice; `(sci.core/fork base)`;
`(seon.sci.eval/fork-for-turn {:seon.sci.eval/ctx base :seon.db/db d :seon.agent/id "root"})`, then with returned ctx as `:seon.sci.eval/agent-ctx`;
`(#'seon.sci.eval/turn-intern-values ctx)`; `(seon.sci.eval/acquire! {:seon.sci.eval/ctx base :seon.db/db d})`.
For real entrance, get instance through `(#'seon.cluster/mcp-instance "default")`, assoc its `:seon.store/store` onto
`(:seon.turn.loop/cluster instance)`, then call `acquire-context!` with nil agent and `{:seon.agent/branch :exp-mcp-check}`.
The disposable branch used exactly that call after MCP branch/create; branch/retire released it.
For queries, time the exact forms at `fn.clj:1527,1564,1570`; the bound variant was
`[:find ?caller :in $ % ?target :where (declared-edge ?caller ?target)]` with d, `@#'seon.fn/declared-reference-rules`, and largest's eid.
Use `(fn [] ...)`, not `#(...)`, around a query containing %, since the reader treats % as an argument even inside quoted data.

Filter-first prototype, locally bound ctx; compare with `turn-intern-values` using =:

```clojure
(let [generation (:sci/generation @(:env ctx))]
  (into {}
    (mapcat (fn [[n entries]]
              (keep (fn [[local v]]
                      (when (and (sci.impl.utils/var? v)
                                 (= generation (:sci/generation (meta v))))
                        [(symbol (str n) (str local))
                         (if (sci.impl.vars/hasRoot v) @v @#'seon.sci.eval/absent-intern)]))
                    entries)))
    (sci.core/namespace-state ctx)))
```

Closure proof: init b; evaluate `(defn f [] 1)`; fork a; optionally run
`(let [v (sci.core/resolve a 'user/f)] (sci.core/bind-root! a v @v))`;
evaluate `(def cell (atom 7)) (defn private-call [] (f))` in a; retain cell locally;
evaluate `(defn f [] 2)`, then `[(f) (private-call)]`; repeat with 3; compare atom with identical? and evaluate f in b.
Both variants were run on disposable local SCI contexts, never default's Vars.
Probe mistakes were corrected: nonexistent `seon.id/keyword`, wrong digest arity, %-reader arity, and an unresolved direct Var literal
for the referred running-instances name. These error results are excluded from performance baselines.
No named probe defs or local scratch contexts were retained; ordinary caches may retain derived bases. The empty scratch namespace and MCP session remain.
Existing exp-mcp-check private definitions were not redefined. Own source/test line delta **0/0**; no publication, reload, browser, merge or test-suite proof is claimed.
File-level Markdown validation passed with no violations; automatic repository-wide lint still reported 57 foreign citation issues, left untouched.
