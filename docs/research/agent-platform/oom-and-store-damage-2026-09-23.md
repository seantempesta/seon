---
type: research
status: source diagnosis; runtime confirmation pending
created: 2026-09-23
---
# Default heap exhaustion and missing index nodes — 2026-09-23

## Boundary and evidence

Read-only diagnosis; only this report is written. No JVM, Datahike store open,
`bin/seon`, test request, collection, or cache mutation was run. The APFS clone is untouched.
Source citations describe the checkout inspected, not proven loaded code in pid 90963:
HEAD moved from `fcebf6f17` to `b6360b5b7` during inspection; several owners had WIP.
In particular, `db.clj` was dirty. Dump line numbers differ from current source.
Authorities: AGENTS.md and plan README §7, especially rows at README.md:401,406.

Preserved evidence is under `tmp/orchestrator/heap-2026-09-23/`:
- `histo.txt:4-12`: 15,792,384 byte arrays (4,176,616,192 B), 50,462,507 Datoms
  (2,018,500,280 B), 19,563,448 Symbols, 4,634,016 map-validator closures.
- `td1.txt:80`, `td2.txt:76`: C2 CPU 190,998.45 → 191,008.87 ms over ~69.87 s.
  This is only 10.42 ms additional C2 CPU; its cumulative 191 s does not explain this stall.
- `td1.txt:376-394`, `td2.txt:353-404`: projection content lookup/LRU and armed calls;
  `td2.txt:1578-1585`: `supplied-projection`. These prove executing paths, not retainers.
- Owner observation: old gen 10.26/10.74 GB, GC ~62 s/120 s, status unresponsive.
  This is a platform defect, not a reason to increase the 10.7 GB heap (`deps.edn:150`).
- Earlier missing-node failure before process death is corroborated by
  `docs/prds/agent-platform/landing/lane-digest-parity-2026-09-23.md:98-102`.
  Thus a final interrupted write alone cannot explain all observed damage.

No heap dominator dump, deletion receipt, or incident-time cache census was supplied.
The histogram establishes neither retaining roots nor post-GC liveness.

Dependency seams: Datahike gitlink `131ca6360d09762aa509a72712005fd396264574`
(`deps.edn:25` local root); Konserve `8cd9144f4338c1fbdb5e531bc996189d71a86fc5`
(`deps.edn:31-32`); Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d` (`deps.edn:15`).
Below, DH = `reference-code/datahike/src/datahike/`; KS = `reference-code/konserve/src/konserve/`;
M = `reference-code/malli/src/malli/`. First-party paths start at `src/seon/`.

## Heap candidates, ranked by source evidence

1. **Malli registry generations remain reachable through reused schemas — strongest structural retention path.**
   `schema.clj:2900-2904` retains old compiled schemas in each new registry.
   `schema.clj:597-602` compiles with the whole lazy registry as `:registry` in options;
   M `core.cljc:1437` retains options, and M `registry.cljc:81-95` retains all compiled siblings.
   Therefore new registry → reused schema → old options/registry → old siblings is a strong path.
   Sealing with `fast-registry` (`schema.clj:616`) does not rewrite schemas' captured options.
   This can retain successive generations beyond the projection LRU's count; it fits the Malli population,
   but does not by itself explain all 50 M Datoms. **A1/Malli owner fix:** narrow each compiled schema's
   resolver to its actual dependency closure, sharing unchanged compiled products; do not retain whole
   prior registry populations through options. Prove recursive refs still work before adopting that change.
   Target O(live schemas + reference edges), not O(adoptions × registry). **Probe:** P2, without compilation.

2. **Per-projection wrapper cache grows with reloads/context callables — proven unbounded container.**
   `schema.clj:382-416` is an atom of entries, with no eviction. `instrument.clj:642,795`
   keys include `original` and `policy`; values close over compiled contracts, projection and policy.
   New callable objects can add entries even when contract content is unchanged. A retained projection
   can therefore retain old bodies and record-mode callbacks/worlds. Not a wrapper-on-wrapper chain:
   `instrument.clj:761-763` checks reuse and unwraps with Malli's `-f->original`.
   **A1 owner fix:** keep compiled contract products shared by contract inputs; let each installed
   callable own its wrapper, rather than memoizing every callable generation inside the projection.
   Target O(live installations + distinct contracts). **Probe:** P2; count originals per symbol across adoptions.

3. **Contexts/held connections multiply DB/index populations; test cleanup is present, leakage unproven.**
   `cluster/agent.clj:907-925` retains DB, projection, connection and SCI context in context-state.
   `sci/eval.clj:2470-2473` stores a DB in each kernel snapshot; base-context LRU is four entries
   (`sci/eval.clj:2326,2339`). `test.clj:1460,1486,1848` acquires and releases execution handles;
   `cluster/agent.clj:766-779` attempts connection release, branch retirement and context removal,
   preserving cleanup failures. `cluster/store.clj:575` calls `d/release`.
   The save candidate is one named branch per cluster, not a new retained branch per save:
   `cluster.clj:2690-2704,2769`; its execution handle releases in `finally`.
   **B2/B4 owner fix if P3 grows:** repair the escaping handle's existing release-after-exit scope;
   keep only the current owned context per branch/agent. Do not add a cleanup scheduler.
   Cost should follow live agents/branches, not completed tests. **Probe:** P3 between already authorized runs.

4. **Native caches are bounded but replicated; entry count is not a byte budget.**
   DH `connections.cljc:3,98-110,123-126` holds active connections with reference counts and removes releases.
   DH `index/persistent_set.cljc:543-546` creates a separate node cache per connection;
   `:store-cache-size` defaults to 1,000 nodes, branching factor 512 (`config.cljc:24`,
   `index/persistent_set.cljc:461-471`). Many retained connections can retain many independently decoded Datoms.
   The extra generic Konserve wrapper cache was already removed (DH `store.cljc:22-29`).
   Schema metadata cache is 1,024 entries (DH `schema_cache.cljc:8`, `config.cljc:15`);
   query results have 64 buckets AND shallow weight 1,000,000 (`query.cljc:2457-2505`).
   Search memo is five DB hashes and an inner datom cache (`db/search.cljc:17-26`), not an unbounded DB map.
   `CachedStorage` also appends freed addresses without a bound (`index/persistent_set.cljc:445-450`):
   secondary growth to measure, not an explanation for millions of validator closures.
   **A2 owner fix:** first repair excess connection lifetime; if legitimate siblings duplicate node populations,
   share the dependency's immutable node-read cache by physical store, keeping writer state per connection.
   Do not add a Seon cache or destroy valid cached work. **Probe:** P3's cache/queue counts per connection.

5. **Projection LRU bound exists; capacity and transitive retention must be distinguished.**
   `db.clj:1257-1274`: wrapped core.cache LRU thresholds 24 and 4; `:cache-context` declaration revisions,
   commit identity and declaration content are inputs (`db.clj:1276-1333,1375,1458-1464`).
   Keys retain declaration Datoms; realized entries retain projections. Unrelated declaration revisions reuse;
   content misses scan/sort declaration datoms. A small bound does not bound the object graph in items 1–2.
   `defonce` also means editing the policy does not resize an already allocated LRU.
   History: `d32a6b2d7` replaced strong DB-object keys and changed 32 → 24; `ec1b53b38` replaced weak
   value keys with speculative revision keys; both preceded the reported 04:00Z process start.
   Thus these particular old-key fixes are not an explanation to assume for this JVM.
   **A1/A2 owner fix:** remove the retaining edges in items 1–2; if actual capacity differs, apply the declared
   policy at its existing cache owner preserving valid entries. **Probe:** P1; do not clear caches to diagnose.

6. **Materialized DB release omission is not a general primary-index disposal mechanism.**
   DH `versioning.cljc:473-501` materializes but registers no primary-DB lifetime pin;
   DH `writing.cljc:298-319` releases only owned secondary resources. Ordinary primary DB release is a no-op.
   `cluster/boot.clj:157`, `cluster/registry.clj:505` already release their materializations.
   **A2 owner fix if a missed secondary close is found:** add the existing release in that acquisition's
   `finally`; primary heap reclamation requires dropping retaining references, not another release call.
   **Probe:** P3 for connection roots; inspect `:datahike.db/materialized-secondary-indices?` only on held values.

7. **C1 cells and durable test results are weak candidates.**
   `profile.clj:52-69` indexes cells with a WeakHashMap-backed set; cells contain counters and identity,
   not DBs or results. Wrappers own cells, not vice versa. No C1 disposal cache is needed.
   `test/runner.clj:190-202` converts expected/actual to printable values; completed member evidence
   is not an arbitrary live-result registry. In-flight thread stacks can still hold raw events/worlds.
   **C1/B4 fix only if confirmed:** remove an escaped execution capture at its owner; keep numeric profile
   and declared test evidence. **Probe:** `(count (seon.profile/cells))`, P3, and inspect a completed
   returned member's value classes without printing payloads. Persistent growth alone does not prove cells are roots.

## Store damage: proven absence; deletion mechanism not proven

Konserve hashes logical keys: KS `impl/defaults.cljc:46-47`, not literal `<node-uuid>.ksv`.
Python independently reproduced hasch 0.4.100's UUID encoding (magic byte 7 + UUID UTF-8,
SHA-512, `uuid5` masks), read from its installed JAR; no Clojure runtime was invoked.
Clone census took 0.033 s: 29,724 files, all `.ksv`; neither logical filename nor hashed filename exists:
- `6ab361f1-9fef-4ef7-8017-b6cb7e11c40c` → `22d21a04-b183-530f-9184-8e3a723aa1ea.ksv`.
- `6ab36206-bc18-4738-811f-3441d28d8765` → `003faaac-21b7-5fef-a6ed-fb807371dda1.ksv`.
Neither has a `.new` or `.backup`. Absence proves no surviving file, not whether it was deleted or never written.

**Interrupted final write ranks below pre-existing reachability/persistence damage.** KS
`filestore.clj:122-154,302-308` stages and atomically renames each ordered write, syncing directories;
`impl/defaults.cljc:87-118` provides the single-write equivalent. DH `writing.cljc:505-551`
writes nodes, schema metadata, commit, then mutable head. Multi-key publication is ordered, not atomic.
An interrupted correct write should leave orphans or the previous head, not a head with unwritten children.

**Collection callers:** `cluster/registry.clj:470` invokes DH GC; maintenance calls it at
`maintenance.clj:760,833,850`. `schedule.clj:57-61` still seeds Sunday 03:00 UTC collection:
this contradicts §7's ruled “no cleanup job,” but its default cadence does not explain Wednesday ~04–05Z.
Stored schedules can differ (`schedule.clj:66-68`); no firing receipt is in the supplied evidence.
Tests also explicitly collect, e.g. `test/seon/cluster/registry_test.clj:399-405` and
`test/seon/blob_publication_test.clj:143-173`; their existence does not prove they collected default.
No source publication/adoption caller or Seon caller of DH `start-background-gc!` was found.

**The zero window is ancestor retention, not a license to delete current heads.** Registry cutoff
is newest captured head minus largest declared window (`cluster/registry.clj:486-515`).
DH `gc.cljc:23-81` marks each current head's primary/temporal roots regardless of age, follows parents
only in range, and `:branches` is included. `gc.cljc:126-169` holds the reachability permit and uses
the minimum of captured time and in-flight safe point; DH `writing.cljc:447-455` guards writes and
explicit-parent publication. KS `gc.cljc:25-33` spares objects at/after that safe point.
The obvious “sweep uses now and deletes in-flight nodes” hypothesis is contradicted by this source.
However, an old DB held only in a reader/context is not a roster head and is not pinned by materialization.
With window 0 its old-only nodes can be swept. That explains an old reader failure conditionally,
but does not explain damage to a genuinely retained current published head without another defect.

**Defect class and owner fixes:** A2/Datahike owns durable index referential integrity; classify this
incident as missing referenced storage objects, with GC attribution unresolved. Protect any intentionally
retained execution snapshot using the existing branch lifetime until actual exit; do not add stamps or a
shadow pin registry. B3 scheduling owns removing the ruled-out automatic collection seed and reconciling
its installed schedule. If receipts establish a current-head/in-flight deletion, repair DH's mark/publication
boundary with the offending node and exact head as a regression, not a larger retention window.
Missing-node failures must panic with full cause when DB recording is unavailable; no retry, swallowed
failure, or fabricated healthy status. Recovering default does not repair or prove the preserved store.

## Cheap read-only confirmation after recovery (not run)

Use host JVM eval with explicit root/cluster; no SCI context mutations, `require`, GC, test, or collection.
Run each census once between already-authorized operations; time it and report >1 s as a defect.
P1: `(mapv (fn [v] (let [c @(var-get v)] {:entries (count c) :class (str (class c))}))
[#'seon.db/projection-cache #'seon.db/value-projection-cache])`; inspect the LRU object's declared threshold field too.
P2: from P1's realized delays only, choose one projection `p`; count `@(:seon.schema.projection/compiled p)`
and group its `:seon.instrument/wrapper` / `:seon.instrument/declared-wrapper` keys by second element.
For an existing schema `s` in `(malli.registry/schemas (:seon.schema.projection/registry p))`, evaluate
`(count (malli.registry/schemas (:registry (malli.core/options s))))`; a full old population confirms the edge.
P3: `(mapv (fn [[id {:keys [conn count]}]] [id count (when conn
(let [s (:storage (:store @conn))] (mapv #(some-> (get s %) deref clojure.core/count)
[:cache :pending-writes :freed-addresses :freed-set])))]) @datahike.connections/*connections*)`.
Also count/group keys of the existing execution handle's `:seon.agent/context-state`; completed test branches
should disappear. Compare connection IDs with `(datahike.api/branches (seon.cluster.boot/connection "default"))`.
Store confirmation first needs existing maintenance firing/collection receipts and their captured heads;
a read-only census on rebuilt default cannot reconstruct who deleted files in the old store.
No whole-store “cheap probe”: a later authorized isolated regression must test reader retention and interrupted publication.
