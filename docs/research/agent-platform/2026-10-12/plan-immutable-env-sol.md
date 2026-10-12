---
type: research
status: design round 3; implementation unproven
basis: 4ac288008; running archive 2319152dc6365747127acd3af1cc2de69cbfa644
---

# The environment at a commit: Sol

## Design

Make `seon.sci.eval/base-ctx` return a program value, not an acquired mutable context:
the immutable SCI namespace/binding map, immutable acquired declarations, projection,
arming decisions and platform identity. `(env commit-id)` resolves a Datahike value and
selects that content-keyed value; custody and the exact commit remain on the execution
handle. Materialize a disposable SCI source from the value and call `sci.core/fork`
once to create an agent's context. Keep that context across unchanged program heads;
at an idle boundary a changed program produces a replacement context carrying the
agent's private Vars and objects. Return and install the replacement in Flow state,
never reset the old context's environment. This is H5 option B. It removes the mutable
branch program copy, rather than rebuilding the whole interpreter on every turn.

The value is immutable in its **binding semantics**, not a recursively immutable JVM
object graph. SCI Vars and closures are runtime objects; compiled host functions may
perform effects. A commit reconstructs the admitted program, not arbitrary private
atoms, sockets or execution. Calling this the entire environment must preserve that
distinction: historical program + database is reproducible; historical private heap is not.

## Parts, identity and cost

| Part | Contents and home | Key / recomputation | Cost now / proposed floor |
|---|---|---|---|
| Commit | Immutable Datahike roots, stored facts, source/blob references | Store identity + commit id; `datahike.api/commit-as-db` | Historical materialization not measured here; index access, not store copying |
| Program source | SCI namespaces, aliases, imports, Var bindings; plain installed-symbol set and program snapshot | Platform identity + admitted program content + semantic projection + arming dials; excludes evaluation/task facts | Live warm-index rebuild 53.19 ms / 119.98 MB for 6,463 function rows; miss O(declarations + reached changed callers) |
| Projection/contracts | Existing named schema projection, Malli compiled schemas and retained wrappers | Projection content including registry scope; existing owners | Shared; no per-fork compilation; cold config derivation remains an existing defect |
| Agent context | One SCI fork, own kernel state, namespace, print session, private bindings, world | Agent identity in execution roster; advances at idle boundaries | Raw detached-source fork ~0.00019 ms / 992 bytes; complete acquisition floor 1–2 ms, to measure |
| Exact acquisition | Commit, connection, writer, sinks, loaded platform; execution handle | Current captured database value; no shared program cache entry | Current `base-ctx` hit 1.63 ms / 1.85 MB / 3 reads; eliminate copy and repeated dial/projection derivation |
| Private layer | Owned Var objects/bindings, actual atoms/closures/results, print session | Agent's context, never commit cache | Unchanged program O(1); replacement initially O(namespace bindings + private bindings), measured before optimizing |
| Successor | Predecessor end commit's db/program points; fresh private state | End commit, not main's later head | Lookup + one fork + Datahike branch pointer; branch I/O separate |
| Cache | Existing `seon.cache` base/overlay entries, storing program values | Content; empty overlay is base; eviction recomputes | Existing weight 12,288 × function count (~79.42 MB here) is an estimate, not retained-heap evidence |

Use a small exact-commit descriptor and a shared program value, not one complete copy
per commit. Data-only commits share the same source. Content revisions are a cheap
input identity; existing overlay-plan content digests coalesce equal definitions after
redundant writes. Projection content and dial values must participate too. Do not hash
a live context, SCI generation or printed source to identify it.

Commit-only API is scoped to one store and a compatible loaded platform. Current
host-bound functions intentionally remain compiled; after host reload, the same database
commit can execute different host roots. Therefore include the loaded host definition /
dependency identity in the runtime cache key. Cross-platform historical execution needs
the corresponding committed archive, not silently substituting today's compiled code.
No evaluator or platform is loaded into default to make history appear reproducible.

## SCI seam and acquisition

SCI pin `f455b3b4a6a4fe92cd2adde2e19821ab1fe5bece`: `sci.core/fork`
**swaps the source's generation** before making the child atom. The dig's shallow-copy
description omits this important mutation. `sci.impl.vars/owns?` makes later writes
contextual; `sci.impl.vars/update-binding!` keeps a Var's identity while changing its
binding in the executing context. A closure analyzed before a fork sees that context's
binding, as the probe below confirms. Forks do not clone an atom held in a Var root.

At cache publication, detach the finished builder's `:env` map and replace kernel
snapshot/installed-function atoms with their values. Discard the builder. Do not cache
its custody, result map, print atom, recorder, projection-state atom or runtime roster.
For each agent, give `sci.core/fork` a temporary source with a fresh atom containing
the detached map; its source-generation swap touches only that temporary atom. The
returned fork has a generation distinct from the program Vars' birth generations.
This uses SCI's isolation, adds no lock/check/fence and needs no SCI change initially.
Document this internal-map seam in the maintained fork before depending on it long term.

`seon.sci.eval/derive-base-ctx` and `derive-overlay-ctx` may mutate their unpublished
builders; acquisition never mutates a published value. Turn `acquire!` into acquisition
of a value plus a returned replacement handle. Its equal-program case updates the
handle's commit/projection data without touching shared snapshots. Move recording of
acquisition refusals into the existing entrant's custody; cached diagnostics are data,
not a cached side effect. Lazy install state belongs to the executing fork, through
`seon.sci.kernel/program-function` and `ensure-installed!`, never to the frozen source.
Separate current immutable declarations from fork-local installation additions; do not
carry duplicate full-program atoms simply to preserve the old API.

## Private state and advancement

Keep the same agent context when content is unchanged. When it changes, acquire the
new program value, fork once, and carry the current private layer as real objects.
Initially reuse the selection semantics of `seon.sci.eval/regenerate-agent-context!`:
inherited bindings replaced; owned private bindings retained; published/adopted symbols
take their new program bindings; deleted program declarations disappear. Preserve Var
identity so retained private closures call continuing Vars. Preserve alias/ref changes,
assigned namespace and print session alongside roots, not only `def` names.

Change regeneration to **return** the new context: delete its final resets of the old
`:env`, base bindings, installed functions and program snapshot. Flow's owner installs
the returned handle before the next evaluation. Callbacks and test worlds receive that
handle explicitly; no callback retains a branch-base atom as a surrogate for the owner.
No change occurs during a running evaluation; its captured context remains its basis.
The one read → evaluate → record → print path remains the only evaluation entrance.

The existing private-layer walk is the simplest correct first version. Deleting its
diff outright would lose private definitions and closures: the flow skill's proposed
"no regeneration diff" cannot be claimed satisfied just by removing the branch copy.
Only replace the walk after measurement, using SCI's contextual binding data and the
evaluation's observed definitions; no second analyzer or guessed namespace ownership.

## Chain and eviction

Persist the predecessor's end commit as the successor's fork point. The successor's
connection forks that point through Datahike; its SCI source is the program value already
used by the predecessor if its final recording did not change program content. Otherwise
derive the end commit's source once. New agent means new private layer; passing a private
atom to a successor is an explicit live value transfer, never implied by ancestry.
Resuming the **same** live agent retains its private layer; restarting after process
loss starts fresh and historical result handles answer gone.

This supersedes `hybrid-agent-world.md`'s earlier "successor forks main too" choice.
The end commit must remain reachable until successor branch creation: create the successor
branch before deleting the predecessor's branch, then Datahike retention owns history.
Do not manufacture a parented main merge or serialize SCI to retain the point.

Use `seon.cache/lookup` and its configured Caffeine byte budget unchanged. Weigh once:
namespace/binding structures, declarations, interpreted source/closures and wrapper
estimates; record retained heap with one and many shared sources in an isolated check.
Charge each source conservatively for its reachable structures initially; overcounting
shared projection data affects hit rate, not correctness. Avoid a new shared-reference
accounting cache. Existing caller-held contexts survive cache eviction; a miss rebuilds
from stored program facts and konserve source bytes. Eviction releases only the cache's
reference, never closes a live execution or releases a caller's materialized db value.
Flow graphs, channels, connections, process hooks and stores stay in their owned roster;
private heap and interruption/deadline state stay live. Rebuild their construction from
facts when needed; never restore interrupted execution. Program-owned mutable `def` roots
need an explicit construction meaning before they can be shared across agents.

## Reuse / change / delete

- Reuse `sci.core/init`, `fork`, `namespace-state`, `sci.impl.vars/context-binding`,
  `update-binding!`; `seon.cache/lookup`; `seon.db/content-revisions`;
  `seon.schema/projection-from-database`; `seon.sci.eval/program-changes`, `overlay-plan`,
  `derive-base-ctx`, `derive-overlay-ctx`; `datahike.api/branch!`, `commit-as-db`.
- Change `seon.sci.eval/base-ctx`, `acquire!`, `fork-for-turn`,
  `regenerate-agent-context!`, `seon.cluster.agent/acquire-context!`,
  `seon.test/run`, `seon.turn.world/world`, and kernel snapshot/install readers at their callers.
  `seon.flow/start-flow!` and end scheduling carry the predecessor point.
- Delete branch-copy use of `seon.sci.eval/branch-ctx`, `copy-base-ctx`,
  `fork-cluster-ctx` on this path; mutable base snapshots and reset-based regeneration.
  Remove obsolete two-fork expectation in `seon.cluster.agent-test/a-fresh-agents-environment-costs-two-forks`.
  H3's `program-identity` LRU and H2's `remember-revisions!` already disappeared; don't propose deleting them again.

## Phases: red first, one loadable slice each

1. **Program value contract and fork source** (`seon.sci.eval`, owned schemas/vocabulary).
   Add a red entrance proof: two agents of one content share the program value; each
   redefines an inherited Var, and a previously analyzed closure sees its own root;
   reacquiring the original commit returns the original program bindings. Exercise a
   changed schema/dial and redundant program write in the same identity table. Producer
   schemas use named Malli contracts; compile against packaged projection.
2. **One acquisition** (`seon.cluster.agent`, test request worlds, `seon.turn.world`).
   Tighten the existing two-fork proof to one fork and no branch program copy, with
   distinct custody per agent and zero fresh-agent history reads. Run existing
   `seon.sci.eval-test/acquired-source-context-forks-own-their-lazy-program-state` too.
3. **Replacement at idle** (`seon.sci.eval`, owner Flow state consumers).
   Extend `seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks`:
   actual private atom/closure/print state survives two program advances and a return to
   an earlier commit; old context's program bindings remain unchanged; next evaluation
   sees the returned handle. Include published own Var, deletion and alias changes.
4. **Chain point** (end/start owners and attempt schema).
   Red end-to-end proof: predecessor changes a function, ends, main advances differently;
   successor executes predecessor's definition and reads its end facts, with fresh private
   state. Repeat after cache eviction on a disposable store; no main substitute. Source
   branch deletion after successor fork must leave successor readable through collection.
5. **Miss equivalence and weight** (existing acquisition/benchmark/cache tests).
   Red table compares same-commit behavior before/after owned cache eviction and raw-store
   reopen, including projection, contract refusal and interpreted callers. Count one
   derivation shared by many acquisitions, one fork per new agent, zero work on unchanged
   live reacquisition. Record allocation and retained heap; cold/warm rows separately.

These are implementation tasks to file with red tagged proofs, not green evidence
from this document. Each phase uses the ordinary REPL and platform gate where host-bound;
root runs broad tiers. No source or schema changed in this planning lane.

## Evidence, limitations and unsure

MCP root/default JVM scratch probe `result/e521d366a3921`: `sci.core/init {}`;
evaluate `(def x 1) (defn read-x [] x) (def cell (atom 0))`; hold `frozen = @(:env ctx)`;
fork `(assoc ctx :env (atom frozen))`; child evaluates `(def x 2) (swap! cell inc)`.
Base returns `[1 1]`, child `[2 1]`, Var object identity equal, source map unchanged.
1,000 forks 0.43125 ms; 100 minimal inits 1.691875 ms. This proves binding sharing and
the atom limitation, not private-layer advancement through Seon's SCI entrant.

Real-program probe `result/ec2fe003d610c`: explicit default connection, loaded program,
`seon.profile/measure` on `derive-base-ctx`, then `base-ctx`, then 1,000 detached-source
forks. Build 53.19 ms / 119,981,376 allocated bytes / 27,069 calls / 13 reads; hit
1.63 ms / 1,848,512 bytes / 380 calls / 3 reads; forks 0.19 ms / 992,024 bytes.
Envelope 159.3 ms / 127.7 MB evaluated allocation; generation/source map unchanged.
Warm indexes, no cache clearing, no historical reopen, no retained-heap measurement.
Earlier form `result/e036caf37fa68`: rebuild 51.47 ms, hit 1.60 ms, forks 0.19 ms.

Two mistaken probes returned data: non-root JVM agent refused (`result/e01bffed7bbfa`)
and wrong `seon.profile/measure` arity refused (`result/e0ab65c66d81f`). Corrected above.
Runtime health degraded: 11 untested-hot warnings, one errored receipt, one unowned
namespace; no reset/restart. Shared dirty task/context/turn/schema files are foreign.
Full-tree `clj-kondo --lint src script test --cache false`: 0 errors, 1,559 warnings,
6,898 ms. Documentation-only lane gate; no runtime tests reaching a changed definition.
Datahike pin `61bca1edd6bcef079f5c47e4f5f75df30549934d`; stored revision ownership exists.
The benchmark's latest H5 proof measured 14.5 ms / 9.1 MB for whole acquisition;
its separate cold SCI leg 937.99 ms / 1,785.832 MB remains distinct from our warm rebuild.

Unsure: semantics of arbitrary program `def` initializers; retention for an explicitly
requested old commit after GC; complete replacement-handle propagation to captured
callbacks; literal deep immutability versus SCI's mutable contextual marker/built-ins.
Require proof of inherited `alter-var-root`/metadata behavior and shared built-in semantics
in the SCI fork before promising stronger isolation. No additional guards proposed.
