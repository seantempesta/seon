---
type: research
status: completed
tags: [research, component, database, cljs]
---

# Custom dependency freshness audit — 2026-07-15

## Question and result

Which maintained or forked dependencies do Seon's `:writer` and `:cljs`
aliases actually select, are those exact revisions public, and what compatible
set should default and ACME use for simultaneous experiments?

Do not move every dependency to its repository's default branch. Freeze one
tested compatibility set:

1. merge upstream Konserve `0.9.359` commit `060b7bb0…` into the existing
   `df6818d…` legacy-header compatibility line and publish the resulting full
   SHA;
2. finish the separately owned Proximum `v0.1.26` guarded-force port and cold
   Git preparation;
3. merge current Datahike upstream head `85c40aee…` into the guarded-force
   descendant of local `069a807e…`, retaining the fork's existing fixes and
   declaring `src-secondary` as a Git dependency path, then publish that full
   SHA;
4. retain the current Shadow CLJS `4e72595f…`, superv.async `3e6ed755…`, and
   partial-cps `1e119b03…` revisions; and
5. move the root `:writer` and `:cljs` coordinates together and bind the final
   identities to the writer/runtime manifest before rebuilding either target.

The order is a correctness dependency, not release tidiness. Datahike upstream
commit `d0106458…` fixes `delete-database` so it awaits physical deletion and
explicitly requires Konserve `>= 0.9.357`. The current Konserve compatibility
fork is based on `0.9.356`. Datahike upstream commit `83868775…` also fixes the
concurrent garbage collector so it stops at the store's safe point instead of
deleting objects from a commit that is still flushing. Seon's reset/restore
work and future background collection need both fixes.

## Scope and method

This was a read-only audit at Seon revision `5b4545b4`. It changed no source,
dependency coordinate, Git checkout/ref, remote, pod, or ACME process.

The selected coordinates came from root `deps.edn`; `acme/deps.edn` contains
only downstream source and no dependency overrides. Effective dependency trees
were checked for both aliases. Exact local sources were read from
`reference-code/datahike`, `reference-code/konserve`, and
`reference-code/shadow-cljs`. The selected superv.async and partial-cps sources
are not mirrored under `reference-code/`; their public commit records and
patches were inspected directly. GitHub repository, branch, commit, compare,
and tag endpoints established public reachability and divergence. Clojars
artifact metadata established current published versions.

The Git dependencies actually selected by either alias are exactly Datahike,
Konserve, Shadow CLJS, superv.async, and partial-cps. Proximum is included below
because the writer currently selects its Maven release and the active Datahike
integration makes its final Git revision part of the same compatibility set.
No other Git fork is selected by `:writer` or `:cljs`.

## Exact ledger

| Dependency | Selected now | Public and fork state | Upstream state | Decision |
|---|---|---|---|---|
| Datahike | Both aliases pin `417649383c65e13f15ea41d394fb1ed742477965` | Public at `seantempesta/datahike`, branch `sync-upstream`; the fork's default `main` is older at `6e2d9bee…`. Local guarded-force commit `069a807e…` and protected test-only commit `eb3e2239…` are not public | `replikativ/main` is `85c40aee…`; Clojars latest is `0.8.1732`. Upstream is three commits ahead of the selected fork and lacks 31 fork commits | Merge upstream into the `069a807e…` line; do not replace the fork with upstream or publish either local commit as the final coordinate |
| Konserve | Both aliases pin `df6818d43ea3363a808cd051c0d68917f1b987a9` | Public at `seantempesta/konserve`, branch `sync-only`; embedded version is `0.9.356-seon.1`. Fork default `main` is older at `ac25cdd7…` | `replikativ/main`, tag `0.9.359`, and Clojars `0.9.359` are `060b7bb0…`; upstream and fork each have three commits absent from the other | Merge `0.9.359` into the compatibility line before the Datahike merge |
| Shadow CLJS | `:cljs` pins `4e72595f57618f5c43388ad13d5136cd3bede566` | Public at `seantempesta/shadow-cljs`, branch `sync-upstream`; four fork commits ahead of upstream. Fork default `master` remains upstream | Upstream `master` is `8236315a…`, release `3.4.11`; there are no newer upstream commits | Keep the selected SHA until its four Git-consumer/exact-selector fixes land upstream |
| superv.async | `:cljs` pins `3e6ed755f83634c9e9bbb58707f9446420d32ce9`; `:writer` resolves upstream Maven `0.3.50` transitively | Public at `seantempesta/superv.async`, branch `wasm/lazy-watchdog`; two fork commits ahead of upstream. Fork default `main` remains upstream | Upstream `main` is `fe1596ae…`, tag and Clojars release `0.3.50`; no newer upstream commits | Keep the selected CLJS SHA. It is intentionally a pod-host fix, not a writer override |
| partial-cps | `:cljs` pins `1e119b03ea908ad925b98f9ba0a26371c65441e3` | Public `simm-is/partial-cps` `main`; this is the repository head, not an unpublished fork | Clojars latest is `0.1.60`; no tag exists. The older divergent `fix/sequence-retention` branch is not based on current `main` | Keep the selected SHA; do not substitute the divergent sequence branch without a separate need and rebase/proof |
| Proximum | `:writer` selects Maven `0.1.25` | Local guarded-force `fb6572c…` is not public and is based on `v0.1.25` | Upstream tag/Clojars `0.1.26` is `c1235796…` | Follow the separately owned forward-port/publication plan; final SHA is not yet knowable |

All five selected Git SHAs resolve through their declared public HTTPS
repositories. Public reachability is therefore not the current failure. The
failure is that mutable fork default branches do not identify the maintained
lines, and the Datahike/Konserve pair is behind correctness fixes that must be
composed with Seon's fork commits rather than selected instead of them.

## Dependency-specific findings

### Datahike must advance after Konserve

The selected Datahike fork is 31 commits ahead and three behind current
upstream. Its fork-only work is not incidental: it contains cold Git prep,
selective CLJS Promise wrapping, query and recursive-rule corrections, bounded
query/pull execution, exact transaction-basis handling, writer-failure
completion, branch lifecycle, secondary-Proximum repairs, and the query
dependency projection used by Seon. Replacing it with upstream would regress
those mechanisms.

The three upstream-only commits are:

- `d010645829229e9d211dfd9ac68d958feb6c9f62` — await store deletion and require
  Konserve `>= 0.9.357`;
- `838687752f0f1539ed0610c4a9b2ee331b431901` — use the Konserve safe point for
  concurrent garbage collection; and
- `85c40aee8a8662d757fcd69f85c5477ff36e605f` — document the one-process writer
  model the safe-point implementation requires.

The local `069a807e12310f1004022dd9909accabb92ab4c0` commit is a direct child of
the selected SHA and integrates guarded Proximum force. It is the correct
integration line, but it cannot be the release coordinate until it also
contains the upstream commits, uses the final Proximum SHA, exposes
`src-secondary` through Datahike's own `:paths`, passes complete dependency
tests, and is public. The protected `eb3e2239…` checkout is a separate
test-only child of `417649…`; retain its bounded-join and recursive-pull
coverage in the eventual tested line, but do not confuse that checkout head
with a production pin.

### Konserve `0.9.359` is required, not optional freshness

The current fork combines upstream `0.9.356` with shared CLJ/CLJS decoding of
legacy one-byte metadata headers. Upstream `0.9.357` through `0.9.359` fix
three deletion contracts:

- memory, file, and tiered `delete-store` honor `:sync?`, and tiered deletion
  no longer loses the backend completion;
- Node filestore asynchronous deletion actually executes and reports errors;
  and
- `:frontend-only` tiered deletion does not delete the shared backend.

The changes are directly relevant to cluster reset, delete/recreate proof, and
the future tiered read-cache boundary. They do not replace the legacy-header
reader: upstream and the compatibility fork are divergent, so the final
Konserve commit must contain both. This is the first coordinate to settle.

### Shadow CLJS is already current plus required fixes

The selected Shadow line contains upstream `3.4.11` and four additional
commits: preserve exact selector symbols after hash promotion, prepare Java
classes for cold Git consumers, report exact selector matches, and fail when
an exact selector matches nothing. Those fixes support Seon's focused
`bin/test-cljs` contract and no-source Git preparation. Upstream has no later
commit or release, so changing this coordinate would add risk without adding a
fix.

The npm `shadow-cljs` package remains `3.4.10` as the CLI shim while the JVM
build classpath uses the Git fork based on `3.4.11`. That is an intentional
two-surface arrangement recorded in `deps.edn`, not evidence that the Git pin
is stale.

### superv.async and partial-cps are already at the intended heads

The superv.async fork adds two commits over upstream `0.3.50`. The final one
starts the stale-exception watchdog only when the first exception is tracked,
so merely loading the namespace creates no perpetual timer in a
wasm-rquickjs/wstd host while preserving exception surfacing once needed.
There is no later upstream commit. The JVM writer has no equivalent host
constraint and continues to use upstream `0.3.50` through Datahike/Konserve;
the explicit custom SHA belongs only in the CLJS pod basis.

The selected partial-cps `main` contains the CLJS `cljs.core/await` breakpoint
fix and the optional core.async bridge, and it is the public repository head.
The repository's `fix/sequence-retention` branch contains a useful async
sequence retention correction, but it predates and diverges from six current
`main` commits. The inspected Seon/Datahike/Konserve call sites use
`async+sync`/await transformations, not partial-cps's async-sequence
transducer. There is no demonstrated Seon failure that justifies replacing
the current head with that branch.

## Final coordinate and simultaneous-cluster gate

The final exact Konserve, Proximum, and Datahike SHAs are intentionally unknown
until their merges/port and tests finish. A conservative admission sequence is:

1. create the Konserve descendant of `df6818d…` and `060b7bb0…`; run its full
   CLJ and CLJS storage-layout plus deletion gates; publish it;
2. complete and publish the Proximum `v0.1.26` guarded-force descendant with
   cold checked-in-Java preparation;
3. rebase or merge Datahike `069a807e…` with upstream `85c40aee…`, select the
   final two dependency SHAs, retain the protected regression coverage, run
   the full Datahike CLJ/CLJS/secondary gates, and publish it;
4. update both root aliases in one Seon commit, remove the checkout-local
   `src-secondary` path, and verify cold public-Git dependency preparation;
5. build default and ACME from the same frozen root revision and require their
   manifests to report identical Datahike, Konserve, Shadow, superv.async, and
   partial-cps identities plus the same writer digest; and
6. only then run simultaneous cluster reset/restart/MCP experiments.

ACME does not need duplicate pins. `bin/acme` projects a separate artifact
flavor and process namespace, while `acme/deps.edn` contributes only downstream
source. The root aliases remain the single dependency authority for both
targets. Simultaneous experiments are therefore supported by the intended
operator boundary once the shared compatibility set is published and rebuilt;
copying coordinates into ACME would create drift rather than isolation.

## Sources and reproducible observations

Primary public sources inspected:

- [Seon Datahike fork](https://github.com/seantempesta/datahike) and
  [Datahike upstream](https://github.com/replikativ/datahike)
- [Seon Konserve fork](https://github.com/seantempesta/konserve) and
  [Konserve upstream](https://github.com/replikativ/konserve)
- [Seon Shadow CLJS fork](https://github.com/seantempesta/shadow-cljs) and
  [Shadow CLJS upstream](https://github.com/thheller/shadow-cljs)
- [Seon superv.async fork](https://github.com/seantempesta/superv.async) and
  [superv.async upstream](https://github.com/replikativ/superv.async)
- [partial-cps](https://github.com/simm-is/partial-cps)
- [Proximum upstream](https://github.com/replikativ/proximum)

Decisive local/public checks:

```bash
clojure -X:deps tree :aliases '[:writer]'
clojure -X:deps tree :aliases '[:cljs]'

gh api repos/<owner>/<repo>/commits/<full-sha>
gh api repos/<owner>/<repo>/commits/<full-sha>/branches-where-head
gh api repos/<fork>/compare/<upstream-owner>:<branch>...<full-sha>
gh api repos/<fork>/compare/<full-sha>...<upstream-owner>:<branch>
```

The GitHub commit requests succeeded for all selected public SHAs. They failed
for local Datahike `069a807e…` and `eb3e2239…`, confirming those two are not
yet public. No mutation was required to establish any result.
