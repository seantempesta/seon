---
type: research
status: active
tags: [research, runtime, architecture]
---

# Fresh-boot 271s — root causes and owner design (2026-07-23)

Read-only investigation for
[[../../../seon/issues/fresh-boot-271s-rederives-build-computed-state]].
All timestamps re-mined from
`logs/operator-predfix/pod/823236b9-ad3a-434b-a5d2-39c171bd1671.log` plus the
sibling `writer/580c6ada…`, `watcher/1aca9924…`, and `host/f91afa1d…` logs of
the same predfix run. Every structural claim is VERIFIED from source with
file:line; time attributions inside an unlogged window are marked ESTIMATED.

## 1. Re-measured timeline (VERIFIED from logs)

| t (UTC) | Δ | Event |
|---|---:|---|
| ~20:26:30 | — | fresh reset begins (271s back from ready; ESTIMATED start) |
| 20:26:54 | ~24s | shadow watcher spawns (cluster wipe + operator prep before it) |
| ~20:27:08 | ~14s | `:client`/`:execution`/`:test` builds complete (11.5–13s, parallel) |
| 20:27:25 | — | **writer JVM ready in 0.2s** (booting 20:27:24.970 → ready 20:27:25.158) |
| 20:27:50 | ~25s | pod bundle load begins→`router installed` (Node requires 547 files; every `schema/register!` runs its admission gate — §3) |
| 20:27:54 | 4s | pod `-main`; "boot phase: opening database session" |
| 20:27:55→20:29:16 | **81s** | SILENT synchronous work (one heartbeat total — the event loop is blocked): `database-initialization` = `index-core!` + `index-schemas` + sort + a full discarded `build-projection` (§2, §3) |
| 20:29:16.8→20:29:32.8 | **16s** | 97 initialization pages stream to the writer (~160ms/page; issue said ~8s — measured 16s) |
| 20:29:32.8 | — | "database session acquired" |
| 20:29:32.8→20:30:07.5 | **35s** | UNLOGGED gap: `reconcile-config!` + `ensure-initial-agent!` + config acquisition (§4) |
| 20:30:07.5→20:30:15.0 | 7.5s | committed acquisition pages (2373 schema rows + 925 contracts) |
| 20:30:15.0→20:31:00.9 | **46s** | `committed-projection` = `schema/build-projection` (§3) |
| 20:31:00.9→20:31:01.3 | 0.4s | instrumentation of 925 fns |
| 20:31:01.7 | — | pod ready |

Corrections to the issue's breakdown:

- The "~80s outside the pod log" is NOT writer-side work. The writer boots in
  0.2s and store open is instant on a fresh reset. The outside time is
  ~24s wipe/operator prep + ~30s shadow server spawn + parallel builds +
  ~25s pod bundle load + host/web-render JVM boots (host log has no
  timestamps; its sci base-load of 182 namespaces overlaps the window).
  Investigation item 3 (writer cheap wins) mostly dissolves: there is no
  embedding backfill or store-open cost visible on this run.
- Fresh boot runs the full projection construction TWICE: once inside the
  81s window as a discarded validation gate
  (`src/seon/client.cljs:1844-1857` — result bound to `_`), once in
  admission (`src/seon/runtime/admission.cljs:298`). The 46s admission
  construction is a rebuild of a value the same process computed ~80s
  earlier from the identical population.

## 2. The ~81s window: what `database-initialization` computes (VERIFIED)

`open-database-session!` on a fresh database calls `database-initialization`
(`src/seon/client.cljs:1834-1868`) BEFORE any page is sent:

1. `index-core!` (`client.cljs:1727-1786`): for each of the ~2400+ compiled
   first-party vars (`first-party-fn-vars` macro output, `client.cljs:1117`),
   `var->fn-row` (`client.cljs:1432-1510`) — reads the var's source file text
   from the digest-guarded `program-sources.edn` sidecar (already consumed:
   `load-program-sources`, `client.cljs:1206-1236`), extracts the exact defn
   form, parses arglists character-by-character
   (`arglists-from-source`, `client.cljs:1356`), and compiles the var's
   `:malli/schema` via `(-> ms m/schema m/form pr-str)` (`client.cljs:1457`).
   Plus one `ns-row` per compiled ns with a full ns-form parse
   (`client.cljs:1300-1354`).
   - Known per-var waste: `extract-form-at-line` (`client.cljs:1246-1258`)
     `str/split-lines`s and re-joins the ENTIRE file text for EVERY var in
     that file — O(file-size × vars-in-file).
2. `index-schemas` (`client.cljs:1788-1819`): linear, cheap.
3. Deterministic strip + sort (`client.cljs:1840-1843`) — cheap.
4. A full `schema/build-projection` over the complete population as a
   validation gate (`client.cljs:1844-1857`, result discarded) — the SAME
   quadratic construction that later takes a measured 46s in admission.

ESTIMATED split of the 81s: ~46s the discarded `build-projection` (measured
independently at admission on the identical population), ~35s the
`var->fn-row`/`ns-row` indexing. Falsifier: one REPL timing of
`(index-core! configuration)` alone on the live pod.

### What the P1b sidecars already carry vs what boot re-derives

- `out/client/program-sources.edn` (5.7MB): `{resource-name → full source}`,
  digest-guarded via `SEON_PROGRAM_SOURCE_PATH`/`SEON_PROGRAM_SOURCE_DIGEST`
  (verified at load, `client.cljs:1209-1228`). Boot ALREADY consumes this —
  no file-system re-reads.
- `out/client/program-inventory.edn` (314KB): symbol NAME lists only
  (`:seon.dev.program-inventory/public-exports`, `first-party-private`,
  `internal-terminals`). It does NOT carry rows: no source extracts, specs,
  docs, arglists, ns rows.

So boot consumes precomputed BYTES but re-derives all STRUCTURE (form
extraction, arglists, spec form strings, ns parses) every fresh boot — and
the output is already deterministic by construction (wall-clock attrs
stripped, rows sorted, `client.cljs:1821-1843`), i.e. it is a pure function
of the build artifact and could be a build sidecar.

## 3. The 46s (×2) pig: `build-projection` is quadratic (VERIFIED structure)

`schema/build-projection` (`src/seon/schema.cljc:656-846`) runs, per
projection build over N=2373 schema forms + M=925 contracts:

- Pass 1: `internal/assert-compilable-schema!` per form with ONE shared
  registry — linear, fine (`schema.cljc:706-710`).
- **Per-row `assert-complete-contract!` for every schema
  (`schema.cljc:714`) and every contract (`schema.cljc:725`) — 3298 calls.**
  Each call (`schema.cljc:435-560`) internally recomputes from scratch:
  - `predicate-symbols` by `(mapcat predicate-symbols-in)` over the values
    of ALL forms (`schema.cljc:452-455`) — a full recursive walk of every
    registered form, per call;
  - `bound-forms` = `walk/postwalk` over EVERY form
    (`schema.cljc:106-107` via `:470`) — a second full-population walk,
    per call;
  - a fresh `mr/composite-registry` + `mr/fast-registry` per call
    (`schema.cljc:487-489`);
  - a transitive reference walk that re-`m/schema`s each referenced form
    with a per-call `visited` set (`schema.cljc:496-540`) — shared
    references are recompiled across calls.

  That is O((N+M) × total-form-bytes) ≈ 3298 full-population walks —
  the quadratic term.
- Pass 3: `(doseq [k …] (m/schema k options))` — full compile, linear.
- Pass 4: `schema-dependencies` re-`m/schema`s every form AGAIN
  (`schema.cljc:737-742`), and `function-dependencies` compiles every
  contract via `m/function-schema` (`schema.cljc:753-761`).

**Is Malli compile time the pig?** Partly-verified answer: the structure
proves ≥3 full linear compile passes plus the 3298-call quadratic assert
loop; instrumentation of all 925 fns takes 0.37s once the projection exists
(log, 20:31:00.9→20:31:01.3), which shows per-item Malli work against a warm
shared registry is milliseconds — so the dominant cost is the per-call
recomputation (full-population walks + fresh registry per call), not any
single compile pass. The exact split is UNMEASURED; the implementing lane's
first action is a REPL timing of `build-projection` with the per-row assert
loop no-opped (ten-minute probe, decisive).

The same `assert-complete-contract!` also runs once per `register!` at
namespace load time (`schema.cljc:632`) with the growing candidate
population — a second quadratic (~N²/2) paid inside the ~25s pod bundle-load
window and inside every JVM/host load of the same portable namespace.

## 4. The 35s unlogged gap (VERIFIED boundaries, cause ESTIMATED)

Between "database session acquired" (`client.cljs:2265-2267`) and
"committed projection acquisition started" (`admission.cljs:287-289`) the
only work is `client.cljs:2268-2314`:

1. `reconcile-config!` (`client.cljs:1939-2005`): resolve routes + seed
   skills tx-data + AI rows, then one provenance-scoped
   `state/reconcile!`, then `agent/reconcile-host-coordinates!` and
   `agent.ctx.admin/migrate-plan-surface-default!`;
2. `acquire-configuration!` + `prove-launch-configuration!` +
   `db/install-configuration-context!`;
3. `ensure-initial-agent!` (root + initial agent birth writes);
4. `acquire-resumable-agent-ids!`.

No log line exists inside this span — an R42 progress-observability gap in
its own right (predfix's boot heartbeat covers the outer phases only).
Which step dominates is UNKNOWN; the design below instruments before fixing.

## 5. What U9 deletes anyway (do not optimize)

Per [[u9-deletion-plan-2026-07-23]]: the `:execution` child build (one of the
three parallel watcher builds — its 11.5s is shadowed by `:test`'s 12.95s, so
near-zero wall win), the self-host engine + bootstrap cache, and the child
release plumbing. None of the four measured pigs above is doomed code:

- `schema.cljc`/`build-projection` is portable `.cljc` and is the R29
  acquisition path every future JVM claimant/web-render tier runs — fixing
  it pays on all tiers forever.
- The corpus rows themselves survive (the database program population); the
  interim producer (`index-core!` in the Bun client) eventually moves
  tier-side, which is an argument FOR a build sidecar (a JVM producer cannot
  introspect CLJS runtime vars at all — the sidecar is tier-neutral).
- `reconcile-config!`/paged initialization survive (landed paged-init
  design, `src/seon/db/protocol.cljc:278-320`).

## 6. Design — three mechanisms, ranked by (time saved / risk)

### D1 — De-quadratic `build-projection` and `register!` (strengthen the one owner)

- Root cause class: derive-at-boot done with accidentally quadratic
  structure inside the one legitimate derivation.
- Mechanism: `assert-complete-contract!` already receives a request map;
  extend it to ACCEPT the precomputed `compiled-forms`, `registry`,
  `compile-options`, and the full-population `predicate-functions` that
  `build-projection` has ALREADY computed before the loop
  (`schema.cljc:684-700`), instead of recomputing all four per call.
  Memoize the transitive reference walk across the per-row loop (one
  `visited`/results map owned by the projection build, keyed by
  [reference role admission-source] — a loop-local accumulator, not stored
  state). `register!`'s per-registration call keeps its current signature
  (candidate population is genuinely growing there) but shares the same
  entry — no second assert path.
- Staleness/correctness guard: none needed — pure refactor of a pure
  function; the projection `fingerprint` (`schema.cljc:832-836`) must be
  byte-identical before/after, and the existing generative/admission suites
  plus one before/after fingerprint-equality regression prove it.
- Expected saving (ESTIMATED, falsified by the probe in §3): admission
  construction 46s → seconds; the boot-frame gate's ~46s share of the 81s →
  seconds; a real slice of the ~25s bundle load and of every host/writer
  namespace load. **~85-95s total.**
- Size: one namespace (`src/seon/schema.cljc` + `schema/internal.cljc`),
  no schema change, no wire change. Small-to-medium diff, low risk.

### D2 — One projection per fresh boot (reuse, fingerprint-guarded)

- Root cause class: the same pure derivation computed twice in one process
  (boot-frame gate at `client.cljs:1844-1857`, admission rebuild at
  `admission.cljs:298`).
- Mechanism (R21 cache = keyed derivation, never a second authority): keep
  R29 acquisition exactly as is — admission still pages the committed rows
  back from the writer (7.5s, the authority check). After acquisition,
  compute the acquired population's fingerprint
  (`projection-from-rows` input → the same canonical fingerprint the
  boot-frame projection already carries) and, when it equals the retained
  boot-frame projection's `:seon.schema.projection/fingerprint`, ADMIT the
  retained projection instead of reconstructing. Mismatch (any agent-turn
  row, any drift) falls through to the existing full construction — the
  cache can only skip work, never change the answer. Hot-reload publications
  (which had no boot-frame projection) are untouched.
- Staleness guard: the fingerprint covers forms + contracts + admissions +
  pure-predicate symbols (`schema.cljc:831-836`); the acquired rows come
  from the immutable database value at the acquisition basis, so equality is
  a proof, not a heuristic. Note the boot-frame gate currently builds with
  core-only admissions while admission derives admission sources from row
  provenance (`schema.cljc:397-…`, `admission.cljs` provenance pattern) —
  the fingerprint therefore only matches when the fresh-boot population is
  all-core, which is exactly the fresh-boot case; any divergence falls
  through safely.
- Expected saving: with D1 landed this is seconds, not 46s — its real value
  is keeping fresh boot O(one construction) as the population grows.
  Without D1 it saves ~46s. Small diff (admission + a retained value on the
  client boot frame), low risk because mismatch = status quo.

### D3 — Build-computed program rows sidecar (boot consumes, digest-guarded)

- Root cause class: boot re-derives structure the build could compute — the
  program population is already a deterministic pure function of the
  artifact (`client.cljs:1821-1843` strips wall-clock attrs and sorts).
- Mechanism: extend the existing build-stage publisher
  (`script/seon/dev/program_artifact.clj` — already a
  `:shadow.build/stage :flush` hook with atomic publish + sha256 digest,
  `:97-128,162-166`) to emit `program-rows.edn`: the exact
  `:seon.ns`/`:seon.fn`/`:seon.schema` row vector, computed from Shadow's
  analyzer data (`seon.client.indexing/analyzer-fn-inventory` already reads
  it) + the same program-sources map. Boot's `database-initialization`
  consumes it through the same digest-guard shape as `load-program-sources`
  (`client.cljs:1206-1236`); the initialization value already stamps
  `:seon.execution/artifact-digest` (`client.cljs:1863`,
  `protocol.cljc:282`), so a sidecar row-set is admissible exactly when its
  digest is included in the running launch descriptor's execution digest —
  the artifact digest machinery in `script/seon/dev/artifact.clj`
  (`source-input-digest`, `digest-paths`) is the existing guard, no new
  authority.
- Parity risk (the one real risk): `:seon.fn/spec` is today the CLJS
  `(-> ms m/schema m/form pr-str)`; a JVM-side producer must emit the
  byte-identical string. Since R30 forces pure-data EDN `:malli/schema`
  forms, `m/form` on JVM should agree, but this needs a standing parity
  regression (build-row vs runtime-`var->fn-row` equality over the full
  population, run at the build gate) before the runtime path is removed.
  Alternative cheap fallback if parity fights back: keep `var->fn-row` but
  fix its two measured inefficiencies (cache `str/split-lines` per FILE
  instead of per VAR in `extract-form-at-line`, and compile specs against
  one shared options map) — ESTIMATED to recover much of the ~35s for a
  ~15-line diff.
- Expected saving: the non-projection remainder of the 81s window
  (~30-35s ESTIMATED) plus it hands the future JVM initialization producer
  a tier-neutral source of rows (a JVM tier cannot run `var->fn-row` over
  CLJS vars at all).
- Size: medium (build script + boot consumer + parity regression). Ship
  AFTER D1/D2 re-measurement — if the residual window is ~10s, the
  fallback line-cache alone may be the right cut.

### D4 — Instrument, then fix, the 35s gap (R42 obligation first)

- Mechanism: add the missing boot-phase log lines around
  `reconcile-config!`, `ensure-initial-agent!`, and
  `acquire-resumable-agent-ids!` (`client.cljs:2268-2314`) — the same
  progress-observability contract predfix added elsewhere. One more fresh
  reset then names the dominant step; suspects in likelihood order:
  `state/reconcile!` per-row wire round-trips over the desired
  routes/skills population, skill seeding
  (`my.skills/seed-skills-tx-data` reads the skills dir), initial agent
  birth's context writes.
- Saving: up to ~30s, UNKNOWN until measured. Tiny diff to instrument.

### Non-items (measured healthy or inherent)

- Writer boot: 0.2s. Store open: instant. No embedding work observed.
- Initialization paging 16s at 64 rows/page: linear wire work with per-page
  receipts; could coarsen via the existing `page-rows` config fact, but it
  is 6% of the total — not worth touching before D1-D4 land.
- Shadow builds (~13s parallel) + JVM process spawns + cluster wipe (~24s):
  inherent dev-loop costs; U9 removes the `:execution` build but the wall
  clock is set by `:test`.

## 7. Recommended target and unit specs

Arithmetic from the measured spans, after D1+D2+D4 (D3 deferred to
re-measurement): pod-log span 187s → ~45-60s (indexing ~35s − line-cache
gains + paging 16s + seconds of projection + the de-quadratic bundle-load
share), total fresh reset → pod ready **271s → ~110-130s**; with D3 (or its
fallback) and a fixed 35s gap, **~75-95s**. Recommended owner-blessed
target: **fresh reset → pod ready ≤ 90s, pod-log span ≤ 45s** — measured,
not guessed, with the R42 stall breaker unchanged.

Sol lane specs (in order):

1. **projfast** — D1 + D2 in `src/seon/schema.cljc` /
   `schema/internal.cljc` / `runtime/admission.cljs` / the boot frame
   handoff in `client.cljs`. First action: the ten-minute REPL probe timing
   `build-projection` with the per-row assert loop no-opped (falsifies the
   quadratic attribution before any edit). Gates: fingerprint byte-equality
   regression, existing schema/admission suites, one fresh-reset live proof
   with per-phase timings.
2. **bootgap** — D4 instrumentation + one fresh reset + fix of the named
   dominant step; owns `client.cljs` boot-phase logging and whichever owner
   the measurement names. Include the `extract-form-at-line` per-file
   line-split cache (measured O(file×vars) waste, ~15 lines).
3. **rowsidecar** (dispatch only if post-1/2 re-measurement shows the
   indexing window still >15s) — D3 in `program_artifact.clj` +
   `database-initialization` consumer + the CLJS/JVM row-parity regression.

## 8. Issue updates

`docs/seon/issues/fresh-boot-271s-rederives-build-computed-state.md` should
be corrected on two evidence points when next touched: the 97 pages take
~16s (not ~8s), and the "~80s writer boot + paged init" outside-log span is
actually wipe + shadow + pod bundle load + host JVM (writer itself is 0.2s).
New defect worth its own note if not fixed by lane 1: the discarded
duplicate `build-projection` in `database-initialization`
(`client.cljs:1844-1857`) — a second full construction of a value the
process rebuilds 80s later.
