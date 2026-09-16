# In-process test recording: the cost is the store's commit floor, not `record-tx`

Date: 2026-09-17. Read-only research lane, branch `steward-platform`,
probes on `default` (pid 95853) through `mcp__seon__eval_clj` jvm mode.
Falsified premise: `seon.test.runner/record-tx` is NOT where the 5.9/8.0 s goes.

## Measured on default (store `data/store`, branch `cluster-default`, file backend, keep-history)

| probe | measured |
|---|---|
| `seon.test/run` one small test, in-process, end to end | 13 378 ms, then 14 406 ms (2nd run, warm) |
| `reach-digests` for that test (warm index) | 8.4 ms |
| `reach-memberships` | 0.08 ms |
| `absent-program-identities` over its 814 closure members | 317 ms |
| `record-tx` building the whole transaction data (pure) | 263 ms — 8 forms, 3 maps, 5 retractAttribute, 814 `:seon.test/reach` refs |
| **`seon.db/transact!` of ONE re-asserted, unchanged datom (empty delta)** | **7 585 / 6 980 / 3 855 / 6 029 / 7 089 ms** |
| same empty delta through raw `datahike.api/transact` on the same connection | 8 935 / 4 639 ms |
| same empty-delta shape on a FRESH file-backed Datahike store (`tmp/probe-store-*`, keep-history, persistent-set) | 46.1 / 48.2 / 48.0 / 47.8 ms |
| store footprint | 12 043 MB, 101 506 konserve keys under `data/store` |
| other writers during the probe | 1 transaction in 5 s (idle); the floor was still 7 089 ms |

Test subject: `seon.render.page-settings-test/settings-without-overrides-use-the-declared-pair`,
closure = 814 functions; database has 4 885 `:seon.fn/sym` and 1 765 `:seon.test/sym` rows.

## Verdict

A transaction that adds NO datom costs 3.9–8.9 s on `default`'s store and 46 ms
on a fresh store of the same shape — a ~100x floor that every write on this
cluster pays, not only test recording. `seon.db` is innocent (raw
`datahike.api/transact` is the same), contention is ruled out (idle sample),
and the whole of `record-tx` accounts for ~0.3 s of a ~13 s run
(`src/seon/test/runner.clj:1717`, `:1862`; caller `src/seon/test.clj:197`).
The earlier 699 ms / 86 results at the gate was a fresh run-root store —
consistent with the 46 ms fresh floor, so nothing regressed in the code path;
what differs is the STORE this cluster has accumulated (12 GB, 101 506 keys).
The check that reads absence of signal here is the one nobody has: no bound is
declared on a commit, so a store degrades a hundredfold in silence.

Secondary, real but second order (all inside `record-tx`, `src/seon/test/runner.clj:1717-1846`):
- `reach-memberships` is re-derived at `:1754` even when `carried-reaches` is
  already present from `commit-results!` (`:1870`) — duplicated derivation
  inside the writer's transaction function;
- `absent-program-identities` (`src/seon/cluster/source.clj:281`) does one
  `db/pull` per distinct closure member — 814 lookup-ref pulls, 317 ms, on
  every recording of every test, with 0 absent identities in the steady case;
- `:seon.test/reach` is retracted (`:1841`) and re-asserted whole every run:
  814 identical ref datoms per test per run, even when `:seon.test/reach-digest`
  is unchanged — retract/assert churn that writes nothing new.

## Plan, simplest first (not implemented)

1. **Measure a reset.** Orchestrator reforks `default` (the store is 12 GB with
   101 506 keys; a lane never resets it) and re-runs the empty-delta floor. If it
   returns to ~50 ms, the defect is unreclaimed store growth and its owner is
   konserve GC / the [TARGET] root maintenance portfolio, not the runner.
2. **If the floor survives a reset**, bisect the commit itself against
   `reference-code/datahike/src/datahike/writer.cljc` and the konserve file
   store: per-commit index flush vs. root/branch-meta serialization vs. fsync,
   each timed separately.
3. **Write only the delta, independent of 1-2.** Skip the `:seon.test/reach`
   retract+reassert when the stored `:seon.test/reach-digest` is unchanged;
   pass the carried reaches through instead of re-deriving them at `:1754`;
   resolve absent identities with one query over the closure syms instead of 814
   pulls. Together ~0.6 s per in-process run, and they stop the index churn that
   feeds growth.
4. **Do not** derive closure membership at read time yet: `:seon.test/reach`
   is the recorded evidence the gate's selection reads; deriving it at read time
   is a separate ruling, and it is not what costs the seconds.

## Class regression and gate

The class is "an execution surface with no declared bound": a commit has none,
so a 100x degradation is invisible. One regression, on the canonical
file-backed fixture store: transact an empty delta and a one-datom delta, and
fail when either exceeds a declared bound (`seon.test-support` style, loud and
named); it must fail, not skip, when the store or the bound is absent.
Namespaces to gate when the delta work lands: `seon.test.runner-test`,
`seon.test-test`, `seon.db-test`, plus `bin/test --platform`.
