---
type: research
status: active
tags: [research, dependency]
---

# Upstream delta sweep and fork hygiene — 2026-07-31

The first run of the standing cadence in `AGENTS.md:121-128`. It asks two
questions the perf dig raised on 2026-07-31: **is our fork state coherent**,
and **what else already sits in our pins unnoticed** — the class that
`:fuse-index-roots?` belonged to (upstream May 2026, `#867`, a measured 5.3×
write-amplification win that nobody had read).

That commit is confirmed present in our pin (`2b8d2710`, reachable from
`reference-code/datahike` HEAD). It was never an upstream gap; it was an
unread feature. This sweep looks for its siblings.

## Method

Fork hygiene is derived, not remembered: every claim below comes from
`git` run inside each of the 106 submodules under `reference-code/`. The
roster generator is `tmp/fork-roster-2026-07-31.txt`; the two scans that
found the real defects were

```bash
# a pin reachable from NO ref is alive only via the super-repo gitlink
git submodule foreach --quiet 'c=$(git branch -a --contains HEAD | grep -v detached | wc -l); \
  if [ "$c" = "0" ]; then echo "$name AT-RISK $(git rev-parse --short HEAD)"; fi'

# local commits that no longer lead to the pin
git submodule foreach --quiet 'for b in $(git for-each-ref --format="%(refname:short)" refs/heads/); do \
  n=$(git rev-list --count HEAD..$b); [ "$n" != 0 ] && echo "$name $b:+$n"; done'
```

Every subagent claim reproduced below was re-checked against source before
being recorded; the three that carried the most weight (konserve's missing
multi-key backing protocols, `:keep-history?` defaulting on, GC running with
no cutoff) were falsified directly and survived.

## Part 1 — fork hygiene

### Verdict

**Coherent, after four repairs — and one genuine near-miss.** Nothing was
lost, no gitlink was stale, and no maintained fork was on a wrong branch in
the sense the owner feared (a lane quietly working on a divergent line). But
**four pins were reachable from no ref at all** — alive only because the
super-repo gitlink names them. A `git gc` inside any of those submodules
would have discarded work that `deps.edn` actively builds against.

The headline case was `datahike`: the pinned commit `9b3be9d5` and the four
commits under it existed on **no branch, local or remote**. `main` sat five
commits behind, and no remote branch contained the pin.

### Repairs made

All four are additive ref repairs *inside* the submodule. No commit was
created, no branch was deleted or moved backwards, no gitlink changed, and
nothing was pushed. Each pin SHA is byte-identical before and after.

| Submodule | Was | Repair | Why safe |
|---|---|---|---|
| `datahike` | pin on no ref; `main` 5 behind | fast-forwarded `main` → `9b3be9d5` | `main` was a strict ancestor |
| `clj-kondo` | pin on no ref (`v2026.07.24` + 2 Seon commits) | fast-forwarded `master` → `57252e07` | `master` was a strict ancestor |
| `core.async.flow-monitor` | pin on no ref (`v0.1.5` + 1 Seon commit); `main` diverged | created `seon` branch at the pin | purely additive; matches the `sci` fork's existing `seon` convention |
| `sharp` | pin on no ref, unreachable from `origin/main` | created `seon-pin` branch at the pin | purely additive; quarry-only checkout |

After the repairs the at-risk scan returns empty for all 106 submodules.

### Roster — the forks `deps.edn` actually consumes

`gitlink current?` is **yes for all 106 submodules** — `git submodule status`
reports zero `+`/`-`/`U` prefixes, so no super-repo commit was needed and
none was made.

| Submodule | Pin | Branch state | Dirty | Action |
|---|---|---|---|---|
| `datahike` | `9b3be9d59` | detached, now anchored on `main`; 95 ahead / **28 behind** `upstream/main` | clean | **FF'd `main`**; upstream merge filed |
| `konserve` | `b5c99bc02` | detached at `codex/konserve-0959-port`, published as `origin/seon-0.9.359-legacy-header`; `main` 12 behind | clean | none — published and anchored |
| `proximum` | `9846d3e79` | detached at `codex/guarded-force-v126`, published as `seon/seon-guarded-force-v126` | clean | none |
| `sci` | `1305a90a1` | **on branch `seon`**; 6 ahead of `origin/master`, 5 unpushed vs `fork/seon` | clean | none — the cleanest fork in the tree |
| `clj-kondo` | `57252e079` | detached, now anchored on `master`; 2 **unpublished** Seon commits | clean | **FF'd `master`**; publication filed |
| `core.async` | `dc35f3e0d` | detached at `v1.10.874-alpha3` on `origin/dev-flow-alpha`; 12 behind `origin/master` | clean | none — see note below |
| `core.async.flow-monitor` | `fbff84246` | detached, now anchored on `seon`; 1 **unpublished** Seon commit | clean | **branch created**; `deps.edn` comment is false — filed |
| `malli` | `801380769` | detached on `master`; 55 behind upstream; artifact pins `0.20.0`, vendor is `0.20.1+10` | clean | already filed (existing note) |
| `reitit` | `106fc4c7a` | detached at tag `0.10.1` = the pinned artifact; 2 behind (docs only) | clean | none |
| `persistent-sorted-set` | `e1a17bbe7` | detached on `main`; `0.4.137` = resolved artifact | clean | none |
| `datalog-parser` | `08a32d8f2` | detached; `0.2.37` = resolved artifact | clean | none |
| `superv.async` | `3e6ed755f` | detached, published as `origin/wasm/lazy-watchdog`; `main` 2 behind | clean | none |
| `partial-cps` | `1e119b03e` | detached on `main` = `origin/main` | clean | none |
| `http-kit` | `238a85cc5` | detached; deliberate fork override | `.cpcache/` only | none — build artifact |
| `datastar-clojure` | `1cef624e9` | detached at `v1.0.0-RC7`; **23 behind** (`rc11`) | clean | see Part 2b |
| `rewrite-clj` | `60782e501` | detached on `main` | clean | none |
| `transit-clj` | `8d2d217e9` | detached on `master` at `v1.1.357`; artifact pins **`1.0.333`** | clean | drift filed |
| `timbre` | `b72cc6529` | detached at `v6.5.0` = pinned artifact | clean | none |
| `shadow-cljs` | `c98bf60f7` | on `sync-upstream` | clean | none — CLJS is off |

`hasch 0.4.100` is a live dependency (`deps.edn:38`) with **no
`reference-code/` checkout at all** — it is the content-hashing function
under every Datahike node address, and it cannot currently be read.

### Dirty working trees

Six submodules are dirty; **none holds work at risk**. Five are build
artifacts or generated caches (`aider-polyglot`, `mvm`, `re-bench`
`__pycache__/`; `http-kit` `.cpcache/`), and two are third-party quarry
noise: `inspect-ai` has a moved nested-submodule pointer, `pdf.js` a CRLF
line-ending artifact in one test fixture. No Seon-authored, uncommitted
source exists inside any submodule.

### Stray branches with unmerged commits

Twelve submodules carry local branches holding commits the pin does not
contain. Ten are stale upstream-tracking branches in quarry checkouts
(`transformers` `+164`, `babashka` `+43`, `mem0` `+14`) — noise, not work.
Two are ours and are **reported, not merged**, because none fast-forwards:

- **`datahike`** — `fix/cljs-get-else` `+5` (CLJS Promise API, `get-else`
  and multi-group join fixes; CLJS is off, so this is quarry),
  `codex/public-proximum-force` `+3` and `sync-upstream` `+1` (a
  test-coverage commit `eb3e2239` that both branches share).
- **`proximum`** — `codex/guarded-force-branch` `+1` (`fb6572c`), which is a
  pre-rebase duplicate of the pinned `9846d3e`, differing by a 44-line test
  deletion. Superseded, not lost.

Three of these branches are checked out in live worktrees under
`tmp/dependency-worktrees/` (`datahike` ×3, `konserve` ×1, `proximum` ×2).
Those worktrees are intact; the branches cannot be touched while they exist,
which is correct.

### One note on `core.async`

The pin sits on `origin/dev-flow-alpha`, not `master`, and this is
deliberate: `master` **removed flow** (`c63dfee remove flow in master`). The
12 "behind" commits are release plumbing and docs on a branch that does not
have the namespace we depend on. Do not "catch up" this fork.

## Part 2a — in the pin, never adopted

The tonight's-class findings: features our pins already ship that Seon's
`src/` never references.

### Datahike

Seon's entire Datahike configuration is three keys
(`src/seon/cluster/store.clj:164-174`): `{:store {:backend :file :path :id}
:writer {:backend :self} :schema-flexibility :write}`. Everything else in
`config.cljc:62-76` is running at its default, unexamined.

| Feature | What it does | Relevance | Verdict |
|---|---|---|---|
| `:keep-history?` | **Defaults `true`** (`config.cljc:21`); doubles the index set with temporal EAVT/AVET and multiplies per-commit writes | write amplification | **MATTERS** — never set anywhere in `src/`, `config/`, or `resources/`; an unexamined default, not a decision |
| `gc-storage!` cutoff + `start-background-gc!` | `gc.cljc:83,119,148` — plain `gc-storage` reclaims only *deleted-branch* garbage (`doc/gc.md:20-24`); the cutoff form and the concurrent collector are what actually evict | disk growth | **MATTERS** — `registry.clj:293` calls the no-cutoff form |
| `:store-cache-size` | konserve node LRU threshold, default 1000 (`config.cljc:24`) | read amplification | MATTERS — the real read-path cache under a file store, untuned |
| `:index-config {:diff-buf-size}`, `:fuse-index-roots?` | the measured 5.3× win | write amplification | already owned by `file-store-commits-pay-five-times-the-fsyncs-they-need.md` |
| `:commit-graph? false` | biggest single win (9.9×) | write amplification | **IGNORE** — already ruled inadmissible; `registry.clj:161-166` branches from commit ids |
| `metrics`, `explain`, `query-stats`, `fork-database` | per-attribute datom counts and index sizes; planner plan view; byte-faithful point-in-time store copy | operability | MATTERS — zero references; these are the missing operational tools |
| `seek-datoms`, `index-range`, `index-page` | ordered/paged index scans without a query | render/feed paging | MATTERS — cheaper than `q` for feed-style paging |
| `query-attribute-dependencies`, `query-dependency-plan` | conservative attribute deps of a query *without executing it* | render invalidation | MATTERS — this is the principled basis for block invalidation |
| `q-with-evidence` family, `shallow-weight-within`, `acquire-q!` | per-call cache/resource evidence and bounded structural weight | budgets | MATTERS — 1-11 hits each in `src-old/`, **zero** in `src/`; Seon now hand-rolls weight budgets in config that the pin exposes first-class |
| `:db.secondary/only` | value lives only in a covering secondary index; primary holds a hasch hash (`CHANGELOG.md:50`) | **data loss** | **MATTERS / hazard** — the bridge emits it (`src/seon/schema/datahike.cljc:243`) but no schema declares a secondary index; see issue |
| `:search-cache-size` | accepted and env-wired but **has no consumer in the pin** — only ever `dissoc`ed (`connector.cljc:146-147`) | — | IGNORE — a vestigial no-op knob; do not tune it |
| `:attribute-refs?`, `:crypto-hash?`, `:index :hitchhiker-tree`, `:initial-tx`, valid-time family, `merge-db`, cross-database `dh://` refs | — | — | IGNORE — invasive, or no Seon domain |

### Konserve

The structural finding: **three of the four features Seon itself authored
upstream cannot execute on Seon's own backend.**

| Feature | What it does | Verdict |
|---|---|---|
| `multi-assoc` ordered batches (#151) | one batched write, seq order = apply order | **MATTERS** — Datahike's fork already builds the ordered batch correctly (`writing.cljc:518-528`) and it **silently falls back** to per-key writes |
| `PMultiWriteBackingStore` / `PMultiReadBackingStore` | the backing-level protocols batching requires | **MATTERS — the missing link**: `defaults.cljc:632-635` gates on `satisfies?`, and `filestore.clj` implements **neither** (verified) |
| `PReadMissSafe` (#148) | skips the existence probe: one round trip per read | IGNORE today (filestore deliberately opts out), MATTERS the day we touch object storage |
| tiered `:frontend-only` (#149) | read-through cache over a read-only backend | IGNORE until a second peer exists |
| per-write metadata channel (#144) | `{:immutable? true}` marking | IGNORE — fully adopted by our Datahike fork |
| `:backend :tiered` + `:memory` frontend | `store.cljc:349-419`; `datahike/config.cljc:190-193` literally recommends the shape | MATTERS — supported end-to-end, never tried |
| filestore `:config {:sync-blob? :lock-blob? :in-place?}` | defaults to fsync **plus** an OS file lock on every blob write | covered by the existing fsync issue; `:sync-blob? false` is already ruled out |
| **`konserve.cache` read-through API** | Datahike calls `kc/ensure-cache` (`store.cljc:33-35`) and then **never reads through it** | **MATTERS** — an LRU allocated per store and never consulted; the real cache is Datahike's own `CachedStorage` |
| `add-write-hook!` | public post-write callback carrying `:api-op :key :value :old-value :meta :kvs` | MATTERS (low) — the plumbing is live in our fork; an unclaimed extension point for wake/audit |
| per-key lock reclaim (#145), `-get-in` probe elision (#147), `:sync?`-honouring delete (#152) | automatic | IGNORE — free, already benefiting |

### SCI

Five of the six Seon-authored commits at our pin are genuinely called. The
sixth is not:

| Feature | Verdict |
|---|---|
| `:sci.impl/symbol` in analysis ex-data (`impl/resolve.cljc:333`) | **AUTHORED-THEN-UNUSED** — zero hits in `src/`; the offending symbol is today recoverable only by regexing SCI's message string, while `eval.clj:373-377` already stores the raw `ex-data` |
| `namespace-bindings` / `namespace-state` / `namespace-interns` / import nil-masks | adopted and load-bearing (`eval.clj:439,525,882,884`; `loop.cljc:123,152`) |
| `sci/copy-ns` | low-MATTERS — `eval.clj:166-169` hand-rolls the `clojure.test` copy via `ns-publics`, missing the protocol-aware copying `copy-ns` gained in 0.14.55 |
| `sci/stacktrace`, `format-stacktrace` | low-MATTERS — agents debugging their own programs get no SCI frames today |
| `:unrestricted`, `:allow`/`:deny`, `:load-fn`, `sci/future`, `sci/pmap` | IGNORE — Seon gates at `seon.sci.admit` with a curated `:namespaces`; a spawned thread would escape the `:interrupt-fn`'s ownership |

Two facts worth pinning down so they are not undone: the pin contains the
0.13.53 string-type-hint sandbox-escape fix, so **downgrading SCI is a
security regression**; and `enable-unrestricted-access!` now throws
(`core.cljc:669`), so there is nothing to migrate.

### core.async flow

| Feature | Verdict |
|---|---|
| `::flow/input-filter` (`flow.clj:229`) | **MATTERS** — zero adoption; the library's only sanctioned way for a proc to stop reading an input. Seon has a bespoke `CountedDroppingBuffer` (`flow.clj:525`) but no read-set control at all |
| `::flow/out-ports` (`flow.clj:221`) | MATTERS — the sanctioned exit from a graph, with `transition` lifecycle for free; Seon's SSE egress hand-rolls `mult`/`tap` |
| `:chan-opts :xform` (`flow.clj:85`) | MATTERS — half-adopted; `flow.clj:391` sets `:buf-or-n` only. An edge transducer moves filtering off `:compute` transforms |
| `::flow/cast` + `:signal-select` (`flow.clj:161,187`) | low-MATTERS — both unadopted, so **no proc can receive a broadcast**; `flow.clj:195` strips `::flow/casts`. A real gap for graph-wide quiesce |
| `flow/futurize` | low-MATTERS — `flow.clj:500` hand-rolls exactly this |
| `:ping-map-fn`, `:compute-timeout-ms`, `::flow/in-ports`, var-processes, workload tags | adopted, load-bearing |
| `map->step`, `lift*->step`, `:mixed-exec`, `async/go`, `pub`/`sub` | IGNORE — deliberate; Seon refuses `:mixed` outright and every proc carries real state |

## Part 2b — upstream, beyond the pin

Network was available; every fork below was fetched successfully.

### Datahike — 28 commits behind, 95 ahead

The largest and most consequential delta. Our fork diverged at `85c40aee8`.
The upstream 28 are overwhelmingly **query-engine correctness**, in a family
we have no equivalent for:

- `cf8b75df` (#912) variable occurrences are equality constraints, not just
  projection sources — and `60f2f0a7` (#913) the rest of that family: nested
  scopes, rule bodies, repeated attributes, history replay;
- `3342c643` (#883) systematic engine fixes — scope leaks, fail-loud parity,
  schema state validation, search cache;
- recursive-rule fixes `5f859c00` (#915), `6d5f602d` (#918), `b5ef35e2`
  (#899); anti-join and planner fixes `e4e26c68` (#905), `d95785fa` (#904),
  `c4d19929` (#903), `61f436d8` (#887);
- `437d6401` (#923) `get-else` left-outer semantics; `779724b6` (#921)
  composite tuples under `:attribute-refs?`.

Features that touch surfaces we use: `11426b97` (#881) **`:db.type/store-ref`
— blobs and out-of-line values, tracked by GC**, which is directly the
transport law's "bulky payloads as blobs" seam; `ac70ef3a` (#861)
attribute-value constraints and a default value-size resource model;
`fabf4b41` (#862) secondary indices owning their external-engine query-spec
(the Proximum seam); `f8176958`/`44c59fd1` (#890, #891) graph BFS-distance
and lowest-common-ancestor sets; `999fffa8` (#896) float/double array value
types. `16c1ab9a` (#886) is a CLJS async storage seam — irrelevant while
CLJS is off.

This needs a real merge, not a fast-forward. `sync-upstream` exists but is
stale (one commit, `eb3e2239`).

### Everything else

| Fork | Behind | Assessment |
|---|---|---|
| `konserve`, `proximum`, `persistent-sorted-set`, `superv.async`, `partial-cps` | **0** | At their fork tips. Nothing to adopt. |
| `sci` | 5 | `d5b9c7d` constant node fusion and `c0c5077` Clojure 1.13 destructuring are the only substantive ones; both are perf/compat, neither touches the `:interrupt-fn` contract. Low priority. |
| `core.async` | 12 | **Do not adopt** — `master` removed flow. |
| `reitit` | 2 | Docs only (a Pedestal Swagger CSP note). Nothing. |
| `malli` | 55 | Mostly docs, cljs, and clj-kondo output. Substantive: `76d158dc` lazy `:ref` validator, `7bcf4a7a` arity extraction before cljs instrument, `16961e2b` `mi/collect!` when `*ns*` is unmapped, `78b8ac28` experimental `:validate` schema for custom errors. The vendored-vs-pinned drift is already filed. |
| `datastar-clojure` | 23 | RC7 → rc11. Substantive for us: `4176081` reworked `sse-gen` closing and error management, `5e789c2` explicit UTF-8 in brotli, `9967822` custom and double flushing — all in the SSE write path the render pipeline rides. Worth a look when the render wave next touches egress. |
| `hyperlith` | 18 | Quarry only (not a dependency), but the direction is instructive for our render pipeline: `895915a` move to lockstep and stable broadcast order, `3a5846e` remove render-on-connect as it bypasses lockstep, `f689ded` treat multiple node morph as separate patch events. |

## Issues filed

| Issue | Severity |
|---|---|
| `publish-the-fork-commits-two-pins-carry-alone.md` | friction |
| `datahike-fork-is-28-commits-behind-upstream.md` | friction |
| `konserve-filestore-cannot-execute-the-batch-write-datahike-builds.md` | friction |
| `keep-history-is-on-by-default-without-a-decision.md` | friction |
| `storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md` | friction |
| `secondary-only-attributes-have-no-covering-index.md` | friction |
| `flow-has-no-read-set-control-and-a-hand-rolled-egress.md` | friction |
| `datahike-allocates-a-konserve-cache-it-never-reads.md` | cleanup |
| `sci-analysis-ex-data-carries-a-symbol-nothing-reads.md` | cleanup |
| `vendored-transit-clj-drifts-from-the-pinned-artifact.md` | cleanup |

## What the cadence should do differently next time

The two scans in *Method* are the whole hygiene half, and they take seconds.
They belong in a script rather than in an agent's head — a pin reachable
from no ref is a mechanical fact, and the four this run found had been
accumulating silently. The adoption half does not mechanize: it needed
reading each fork's changelog and config surface against `src/`, and its
yield (`:keep-history?`, GC without a cutoff, a dead multi-key path, an LRU
nobody reads) came from exactly that reading.
