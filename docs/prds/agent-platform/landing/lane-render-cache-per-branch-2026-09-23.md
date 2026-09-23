---
type: evidence
status: committed 66c113d93 (fix schedule #24l)
created: 2026-09-23
---

# The render cache belongs to the branch it was derived in

Defect: [lane-page-key](lane-page-key-2026-09-23.md), out of scope item 2.
`seon.render/shared-cache` kept one atom under `:seon.render/cache` in the context's
environment reference. That atom was copied into every context acquired from a cluster.

## How the cache was shared (probed, not assumed)

* `seon.env/of` on a cluster handle reads the live environment reference. Default's
  `:seon.turn.loop/cluster` carries `:seon.sci.eval/projection-state`, and it is
  identical to its ctx's state. Probe (default, read-only): `:same-state true`,
  `:env-of-handle-cache-same true`.
* The isolated branch of `seon.cluster.agent/acquire-context!`
  (`src/seon/cluster/agent.clj:878`) forks with
  `{:seon.env/environment (or (env/of handle) (env/of source-ctx))}`.
  `seon.sci.eval/fork-cluster-ctx` builds the fork's new reference from that
  environment value, and extra members are carried. The fork's new state therefore
  held the parent's cache atom.
* The acquired handle keeps a snapshot, `:seon.env/environment (env/of ctx)`. Every
  test member acquired from default, and every `with-database` fixture acquired from a
  member, inherited default's atom. `render-pass` does
  `(swap! cache assoc ::packages packages)`, so a fixture pass replaced default's
  packages.

## Change (`src/seon/render.clj`)

* `branch-scope` (new, contracted): selects Datahike's own connection identity,
  `:datahike.cache/connection-id` (`[store-id branch]`,
  `reference-code/datahike/src/datahike/store.cljc:44`) and
  `:datahike.cache/generation` (`connector.cljc:376`), from the `:cache-context` of the
  ctx's custody connection (or custody db). Datahike pin `fbd1ad2d1`. A reopened
  branch gets a new generation. A speculative value has an empty scope.
* `shared-cache`: a cache records the scope it was allocated for, as those two Datahike
  keys inside its own map. A ctx whose scope differs never reads or writes an inherited
  cache. It allocates `(atom scope)` in its own environment reference. Forks on the
  same branch share one cache. A fork's cache is collected with its context, so no
  registry or eviction exists. The `:seon.render/cache` member (`seon.env.edn:53`)
  keeps its shape, so no schema change was needed.
* No cross-branch reuse. Invocation entries are content-keyed, but their read evidence
  was checked against another lineage's database. Nothing proves that reuse, so it is
  dropped.

## Regression (`test/seon/render_cache_test.clj`, `seon.render-cache-test`)

`a-fixture-branch-rendering-root-leaves-the-parent-branch-packages-unchanged`:

1. The member handle is given the live environment reference, as default's cluster
   handle has it.
2. It renders `root` (`render-pass`).
3. A fixture is acquired from it through the production entrance and gets a message
   only it has. It then renders `root`.
4. Asserts: distinct scopes and caches; one branch reuses its cache; the fork's package
   is at the fork's basis and shows different content; the parent's cache is identical
   and its package is unchanged; no parent package carries the fork's basis.

Scratch root `tmp/render-cache-root` (working tree over `b2b10271b`; default was never
touched):

| run | result |
|---|---|
| `bin/test-check … --test seon.render-cache-test/…` after publication | run `7554223f8716`: executed 1, pass 11, fail 0, 12,355 ms request |
| same, repeated | run `8070616d4dea`: reused 1 (recorded green), 1,219 ms |
| direct body, this change ×3 | 11/11 each; 4,990 / 2,808 / 2,695 ms (branch acquire 597 ms) |
| falsification: HEAD `shared-cache` swapped in (scratch JVM only), same body | **3 failures**: `(not (identical? parent-cache (render/shared-cache fork-ctx)))`, `(= parent-package (root-package parent-ctx))`, the fork basis found in the parent's packages |
| restored this change | 11/11 |

The first draft forked with `with-database` from a member whose handle held only an
environment snapshot. That draft passed on HEAD too, so it proved nothing. It was
replaced by the version above, which exercises the live-reference path.

Neighbours (run `ef16c3f830ca`, 7 executed, 68 pass, 9 fail, 2 error): every behavioral
assertion about the cache passed. That includes `web-context-test` line 61
(`identical?` repeat) and the page-key regression's assertions. The reds are:

* duration bounds on `with-server` fixtures (5.4–13.4 s; census A7, pre-existing per
  the page-key note);
* a fixture `{:db/doc …}` write refused as `unowned-entity` (writer rule);
* `seon.sci.eval/explained-evaluation` refusing `:seon.profile/snapshot` (the
  wrapper-profiling lane's uncommitted `eval.clj` work);
* `seon.schedule-test` program row without `:seon.program/definition-digest`.

None of these names the cache. Limit: this set was not re-run with HEAD's
`shared-cache`, so their pre-existence is judged from their messages.

## Hot-path timings, parent (HEAD `shared-cache`) versus this change

The same scratch JVM had its Var swapped. Default could not run the new code: the
rules forbid `load-file` and redefs in default.

| probe | parent | this change |
|---|---|---|
| default `/agent/root` GET, unchanged head (parent only, curl ×8) | 1,770 first after writes; then 7–18 ms | not loaded (RESET/adoption needed to observe) |
| scratch `/agent/root` GET, unchanged head (curl ×10) | 222 first, then 33–39 ms | 308 first (a fresh per-branch cache: one re-derive), then 36–46 ms |
| scratch: message write, then GET ×3 (×4 rounds) | write 49–60; GET 672–770, then 36–45 (one 331 outlier during a turn) | write 49–65; GET 550–711, then 34–50 |
| `shared-cache` per call, armed | — | 14.9 µs (unarmed 6.3 µs; `branch-scope` armed 5.2 µs; contract checks dominate) |
| HEAD `shared-cache` per call, unarmed | 0.05 µs | — |

Unchanged-head GETs have no regression beyond noise (median 36.5 versus 39 ms, with
several ms of spread in both). An unchanged-head GET does not call `shared-cache` often
enough to show in the armed profile. The scratch root's 33–46 ms floor, against
default's 7–18 ms, belongs to the scratch process, not to this change: parent and this
change match on the same process.

## TIMINGS (over 1 s)

| operation | wall ms | justification / phase |
|---|---|---|
| scratch root boot `bin/seon --root tmp/render-cache-root start` | 97,050 (ready 70,206) | **DEFECT >10 s**: from-zero boot with the dependency-class cache missed (working-tree `deps.edn`); extended `docs/seon/issues/from-zero-boot-takes-minutes.md` |
| `refresh-source!` of 2 paths (1 changed test ns) | 12,206 | **DEFECT >10 s**: extended `docs/seon/issues/a-three-file-changed-path-publication-takes-a-minute.md` |
| `bin/test-check` executed, one member | 7,639 (pre-publication body) / 12,355 (post-publication, cold) | **DEFECT >10 s** on the cold run: request = member branch acquisition + two cold `root` page derivations + recording. Body alone is 2.7–5.0 s |
| regression body | 2,695–5,990 | two full `root` page derivations (≈1.3–1.5 s each warm: 9 render invocations per page through SCI, `derive-page` 9.3 s over 6 calls) plus one branch acquisition (52–597 ms). A per-branch cache cannot reuse across branches, so the fork's derivation is inherent. Risk: the cold first run is at the 5 s default bound |
| neighbour run `ef16c3f830ca` | 63,992 | 7 members, `with-server` fixture cost (census A7), not this change |
| scratch GET after write | 550–770 | page derivation after an input change; parent equal |
| scratch turn woken by the probe messages | 11,312 (`open-turn`) | **DEFECT >10 s**, turn owner: extended `docs/seon/issues/a-turn-spends-seconds-before-its-provider-call.md` |

Cache hits and misses: the repeated `test-check` was a recorded-green reuse (1,219 ms).
The unchanged-head GETs were served retained (no derivation). The first GET after
installing each version was a miss: the fresh cache for this change, and the
invalidating MCP eval for the parent.

## Out of scope (other owners)

1. `src/seon/cluster.clj:366` (wrapper-profiling holds it): `mcp-projection-error` is
   called with a nil failure, so a successful jvm eval whose value fails projection
   reports as an exception. Extended
   `docs/seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md`.
2. `src/seon/sci/eval.clj:3193` (wrapper-profiling, uncommitted): the
   `:seon.profile/snapshot` contract refuses an `IdentityHashMap`, which breaks the
   debug inspection page (`web-context-test:175-177`, HTTP 500).
3. `render.clj` lint noise, pre-existing and untouched: unused requires `seon.error` and
   `seon.print`, and unused bindings at `:932-933`.

## Resources

RESET NEEDED: no for correctness. Default keeps HEAD's `shared-cache` until the
orchestrator's next adoption. Its live-page nondeterminism against web tests remains
until then. Scratch root `tmp/render-cache-root` (pid 6201) is stopped by this lane
before the report.
