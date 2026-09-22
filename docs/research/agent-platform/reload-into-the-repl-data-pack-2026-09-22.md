---
type: reference
status: read-only research; citations verified at a342298e5
created: 2026-09-22
tags: [agent-platform, reload, repl, clj-reload, tools-namespace, publication, lane-b1]
---

# Reload into the REPL — what already exists, and what Seon built instead

Owner question (2026-09-22): "What is the most efficient way to reload files into a
REPL using Clojure libraries that already exist, and did we create a worse version?"

## Summary (ten lines)

1. `require :reload` recompiles exactly the named namespaces; `:reload-all` walks
   *upstream* deps, never dependents (`core.clj:6064-6079`, docstring `:6200-6204`).
2. Neither flag removes stale interns, resets `defonce`, or re-identifies
   protocols/records; both "eval on top" (clj-reload README:442).
3. `clojure.tools.namespace` is **not vendored and not on the classpath**; `clj-reload`
   **is vendored** (`reference-code/clj-reload`, submodule `61c6fa7`) but is **not in
   `deps.edn`**, so it is present as source and absent as a dependency.
4. clj-reload = mtime scan + parsed `ns` graph + topological unload/load, keeps
   `defonce` and `^:clj-reload/keep` vars, per-ns unload/reload hooks, reloads only
   already-loaded nses (`core.clj:206-268,270-303,334`; README:452-466).
5. Seon does **not** hand-build its reload set: `development-namespaces`
   (`cluster.clj:2006-2035`) computes the reverse closure over the stored
   `:seon.ns/requires` facts. That part is correct and better than a re-parse.
6. Seon's per-namespace load is `(require ns :reload)` in topological order
   (`cluster.clj:1980-1984`, order from `reload-order` `:1925-1949`), plus explicit
   `ns-unmap` of deleted identities (`:2096-2102`) — the one thing Clojure omits.
7. What Seon adds that no library owns: per-declaration indexing into program rows,
   schema declaration transactions, contract re-arming, projection advance, and the
   adoption record (`cluster.clj:2049-2091,2105-2140`).
8. What Seon does worse: the reload decision is fused into a whole publication —
   snapshot, manifest, seal, digest, toolchain check — and `instrument/apply!`
   re-arms **every loaded Var**, not the changed ones (`instrument.clj:781-784`).
9. The hook does not drive the live JVM directly: it spawns a Babashka `bin/seon init
   --dev` process per edit (`bin/seon-hook:1528-1545` → `bin/seon:24-26` →
   `operator.clj:166-171` → `cluster/boot.clj:434`), which then calls `refresh-source!`.
10. **Verdict: not a worse reloader — a worse *envelope* around a correct reloader.**
    Keep (a) `require :reload` over the stored graph; delete the publication envelope
    per B1 §5 rows 2, 6, 7, 8, 12. No new library needed.

**Evidence limit.** `default` was mid-`reset --force` for this whole session: `bin/seon
status` answered with `:seon.boot/missing-layers [… :seon.boot/cluster-connection …]`
and two `eval_clj` attempts refused with `"Cluster database connection is not
acquired."` (`cluster/boot.clj:205`). §5's counts are therefore **static evidence from
the `ns` forms on disk**, not a Datalog read of `default`. That is a typed unknown, not
a pass; the Datalog in §5 is written but unrun.

## 1. What Clojure itself gives

| Fact | `file:line` |
|---|---|
| `load-lib` picks `load-all` for `:reload-all`, `load-one` for `:reload` | `reference-code/clojure/src/clj/clojure/core.clj:6064-6079` |
| On a load exception, the ns is removed only if it was undefined on entry | `core.clj:6083-6087` |
| `:reload` "forces loading of all the identified libs even if they are already loaded" | `core.clj:6201-6202` |
| `:reload-all` "also forces loading of all libs that the identified libs directly or indirectly load" — i.e. **upstream**, not dependents | `core.clj:6203-6205` |
| Supported flags are exactly `:as :reload :reload-all :require :use :verbose :refer :as-alias` | `core.clj:6119` |

Cost model: recompiling a namespace = evaluating every top-level form in its file. A
docstring edit to a 3,000-line file costs that file's compile, plus each dependent's.

Not handled by either flag: a var deleted from the file stays interned and callable;
`defonce` re-runs are skipped but the surrounding state is re-evaluated; a re-evaluated
`defprotocol`/`deftype`/`defrecord` gets a **new** interface identity, so existing
instances stop satisfying it; dependents are never reloaded, so a dependent holding a
direct `Var` reference or an inlined macro expansion keeps the old code.

**Verdict:** `require :reload` is the correct primitive and the cheapest one; it is not
a reload *strategy*, because it neither chooses the set nor cleans up.

## 2. `clojure.tools.namespace`

**Not vendored** (`reference-code/` has no `tools.namespace`) and **not a dependency**
(`deps.edn:13-110` lists no `org.clojure/tools.namespace`). No `src/`, `test/`, `bin/`
or `.claude/` reference exists. Cited here by name only: `clojure/tools.namespace`,
`clojure.tools.namespace.repl/refresh`, `refresh-all`, `set-refresh-dirs`,
`c.t.n.track`, `c.t.n.dir/scan-dirs`, `c.t.n.file/read-file-ns-decl` (project README).

Its shape, per the clj-reload author's comparison (`reference-code/clj-reload/README.md:448-466`),
which is itself a secondary source and is labelled as such: both tools track file
mtimes and reload in topological order; tools.namespace reloads **every namespace it
can find** (not only loaded ones), its **first refresh reloads everything**, it does
not support split namespaces or top-level `require` forms, it reports no set so a
`:after` hook must rebuild the whole system, and **`defonce` does not survive** because
`remove-ns` destroys the namespace wholesale.

**Graph accuracy vs Seon.** Seon's `:seon.ns/requires` is written by the canonical
analyzer from the parsed `ns` form (`src/seon/fn.clj:296-303`) and stored as an indexed
set of symbols (`resources/seon/schemas/seon.ns.edn:25`, `:requires [:set {:seon.db/index true} :symbol]`).
tools.namespace re-parses the same `ns` forms off disk each refresh. The *content* is
the same relation; Seon's is **already a queryable index** and needs no scan, while
tools.namespace's is **fresher for files Seon has not yet published**. Seon's is more
accurate for the adopted program, less accurate for un-indexed edits — which is exactly
why the reload must run after publication, as it does today.

**Verdict:** nothing here Seon needs; its unload semantics are strictly more destructive
than Seon's `ns-unmap`-of-deleted-identities, and it would reset `defonce` state the one
JVM depends on.

## 3. Other existing tools

**`clj-reload`** (vendored, `reference-code/clj-reload` at `61c6fa7`, `.gitmodules:9-11`;
951 lines total). `init` takes `:dirs`, `:files`, `:no-unload`, `:no-reload`,
`:unload-hook`, `:reload-hook`, `:output` (`core.clj:146-183`). `scan` compares
`lastModified` against `:since`, computes `dependees` (`parse.clj:122-131`), takes the
`transitive-closure` of the changed set (`parse.clj:133-151`), topologically sorts both
directions, and filters to namespaces actually in `*loaded-libs*` (`core.clj:206-268`).
`ns-unload` calls `before-ns-unload` then removes the ns (`core.clj:270-286`);
`ns-load` reloads from the file, patched when vars are kept, then calls
`after-ns-reload` (`core.clj:287-303`). Keeps: `defonce` works out of the box,
`def`/`defn`/`deftype`/`defrecord`/`defprotocol` survive with `^:clj-reload/keep`
(README:232-234), with dedicated keep methods per form (`keep.clj:50,64,91`). Zero
runtime dependencies.

**`virgil`** — recompiles Java sources into a running REPL. Irrelevant: Seon has no
first-party Java. **`clojure.tools.build`** — an AOT/jar builder, not a REPL reloader;
already a dependency for `:build` only (`deps.edn:134,143,159`).

**nREPL `refresh` middleware** (`cider-nrepl`'s `refresh` op) — a transport wrapper over
tools.namespace; it adds nothing to the algorithm and Seon speaks prepl, not nREPL.

**Cursive / Calva "load file"** — `load-file` of one buffer, "eval on top", no dependent
tracking (the exact failure clj-reload's README:442 names). Editor-bound; not drivable
from a hook.

**`clojure.repl/source`** — a source *reader*, not a reloader. Not applicable.

**Verdict:** for "edit a file, dependents reload sub-second", `clj-reload` is the best
existing library, and it **can** be driven from the hook through the prepl — it is a
plain function call, `(clj-reload.core/reload)`, returning `{:unloaded [...] :loaded [...]}`
(`core.clj:334-350`). But Seon already has the two things clj-reload computes (the
dependency graph, and the changed-file set) as *facts*, so adopting it would replace a
Datalog query with an mtime scan of every source file. Its real advantages over bare
`require :reload` are unload/reload hooks and var keeps — neither of which Seon
currently uses.

## 4. Seon's mechanism, measured against them

Path today: hook → `bin/seon init --dev default --changed PATHS` → prepl →
`seon.cluster.boot/request!` → `cluster/refresh-source!` → `full-source-refresh!` →
`development-source-refresh!`.

| Step | `file:line` | Owned by a library? |
|---|---|---|
| bb process spawn per edit | `bin/seon-hook:1528-1545`; `bin/seon:24-26` | no — avoidable |
| prepl request into the live JVM | `script/seon/operator.clj:166-171,224` | no |
| `:init --dev` → `refresh-source!` | `src/seon/cluster/boot.clj:416,434-436` | no |
| toolchain/resource precondition | `cluster.clj:2153`, called `:2197` | no |
| source snapshot (walk + digest every input) | `cluster.clj:1616-1626,1644-1646`, used `:1833,1899` | **clj-reload does this as an mtime scan** |
| manifest build | `cluster.clj:1863` (`seon.fn/build-manifest`) | no |
| publish: seal row, input digest, upserts | `cluster/source.clj:339-354,355,524,549` | no |
| changed identities from digests | `cluster/source.clj:159-188` | no |
| schema declaration transaction | `cluster.clj:2053-2063` | no |
| per-declaration program-row indexing | `cluster.clj:2064-2077` | **no library owns this** |
| reload set = reverse closure over `:seon.ns/requires` | `cluster.clj:2006-2035` (query `:2021-2026`) | equivalent to `parse/dependees` + `transitive-closure` |
| topological order | `cluster.clj:1925-1949` | equivalent to `parse/topo-sort` |
| `ns-unmap` of deleted identities | `cluster.clj:2096-2102` | **clj-reload does this by `remove-ns`** |
| `(require ns :reload)` per namespace | `cluster.clj:1980-1984` | `clojure.core` |
| projection advance | `cluster.clj:2106-2107` | no |
| `instrument/apply!` re-arm | `cluster.clj:2117-2123`; `instrument.clj:781-784` | **no library owns this** |
| adoption record transaction | `cluster.clj:2133-2140` | no |

**Does Seon do anything the libraries do not?** Yes, and it is the majority of the
value: per-declaration program rows, schema declarations, contract re-arming, the
projection advance, and a durable adoption record. None of that is a reload concern and
no Clojure library owns it.

**Does Seon do anything worse?** Yes, four things.
- The reload is fused to a *whole-tree* publication: snapshot, manifest, seal,
  aggregate digest and toolchain check run on every edit, including a no-change
  request (264 ms) and a repeated docstring edit (2,723 ms) — `plan/README.md:216`.
- `instrument/apply!` sweeps **every loaded Var** rather than the changed identities;
  its docstring already scopes the intent ("Arm loaded Vars whose contract or
  referenced declarations changed", `instrument.clj:782-784`) but the request carries
  no changed-identity member (`instrument.clj:791-796`).
- A process is spawned per edit instead of one prepl request into the live JVM.
- `development-namespaces` is called **twice** — over the previous and the published
  database — and unions the results (`cluster.clj:2091-2092`).

**The claim that the reload set is hand-built is false.** `development-namespaces`
queries `:seon.ns/requires` (`cluster.clj:2021-2026`) and loops to a fixed point
(`:2019-2035`). It is the stored-graph version the owner wants. Correcting this here
rather than repeating it.

**Concern separation.** (a) *Reload loaded namespaces* — `reload-order`,
`reloadable-namespace?`, `load-development-definitions!`, `development-namespaces`
(`cluster.clj:1925-2035`): ~110 lines, pure Clojure, a library's job. (b) *Index changed
declarations and re-arm contracts* — `development-source-refresh!`'s transactions and
`instrument/apply!` (`cluster.clj:2049-2140`): Seon's own. They live in **one function
with one entry point**, `refresh-source!` (`cluster.clj:2170-2224`), whose only
publication-free path is a commit-id comparison (`cluster.clj:2045-2046`). **Yes, Seon
has conflated them** — and the plan already names the split: "`publish!` takes the
target branch as a request member and never implies the process-wide `require :reload`
at `cluster.clj:1984` (`refresh-source!` split)" (`plan/README.md:182`, cut 1.3d
commit 3).

## 5. The reload set from facts

The Datalog that yields it, written against `default` but **unrun** (see the evidence
limit above) — the fixed-point loop is `cluster.clj:2019-2035`; one expansion step is:

```clojure
(seon.db/q '[:find [?name ...]
             :in $ [?required ...]
             :where [?ns :seon.ns/requires ?required]
                    [?ns :seon.ns/name ?name]]
           (seon.db/db (seon.cluster.boot/connection "default"))
           '[seon.id])
```

Iterate to a fixed point over the newly added names to get the reverse closure.

**Static equivalent, run at `a342298e5`** over the same relation the analyzer records
(the `:require` clauses of each `ns` form in `src/`, `fn.clj:296-303`), 107 namespaces:

| Changed namespace | Direct dependents | Reverse closure (the reload set) |
|---|---:|---:|
| `seon.id` | 24 | **90 of 107** |
| `seon.db` | 54 | **74 of 107** |
| `seon.cluster` | 4 | **5 of 107** |

This is what `development-namespaces` computes today — the same closure, from facts
instead of a parse. The number is the real cost driver: a `seon.id` edit recompiles 90
namespaces whatever library drives it. clj-reload would compute the identical set (its
`dependees` + `transitive-closure`, `parse.clj:122-151`) after an mtime scan of every
file. **No library makes this set smaller.** Sub-second for a leaf edit is achievable;
sub-second for a `seon.id` edit is a compile-cost question, not a mechanism question,
and no evidence here shows it has been measured per-namespace.

## 6. Verdict and the smallest change

**Did we build a worse version?** No — not a worse *reloader*. The reload core
(reverse closure over stored requires, topological order, `require :reload`, `ns-unmap`
of deleted vars) is sound and is what an existing library would do, minus the file
scan. What was built worse is everything wrapped around it: a whole-tree publication,
a process spawn, a blanket contract re-arm, and a doubled closure computation. The
2,723 ms docstring number is the envelope, not `require`.

**Smallest change — keep `require :reload`; do not adopt a library.** clj-reload buys
mtime scanning (Seon has digests), hooks (unused) and var keeps (unused), at the cost of
a new dependency and `remove-ns` semantics that would reset the one JVM's `defonce`
state. `require :reload` over the stored graph is already the cheaper path.

Then, in the plan's own order:
- **Delete from `cluster.clj`:** `source-snapshot` `:1616`, `current-source-snapshot`
  `:1644`, `require-publication-resources!` `:2153`, the `build-manifest` call `:1863`
  (B1 §5 rows 2 and 7).
- **Delete from `cluster/source.clj`:** the seal row, `publication-input-digest!`
  `:339`, `populate-upserts!` `:524`, `upsert!` `:549`, the aggregate
  `:seon.source/digest` (B1 §5 row 6 — **RESET NEEDED**).
- **Narrow adoption** (B1 §5 row 8): commit-id compare on every request; one
  `development-namespaces` call, not two (`cluster.clj:2091-2092`); `reload-order`
  refuses rather than falling back to name order (`:1944-1947`); `instrument/apply!`
  receives `:seon.instrument/changed-identities` so the re-arm is proportional
  (A1 owns `apply!`; producer and consumer land together).
- **Split the entry point** (cut 1.3d commit 3, `plan/README.md:182`): `publish!`
  stops implying the process-wide reload at `cluster.clj:1984`; reload becomes a
  separate call taking the changed identities.
- **Hook** (B1 §5 row 12): publication becomes ~40 lines over `prepl-eval!` — one
  request into the live JVM, no `bin/seon init` subprocess
  (`bin/seon-hook:1528-1545` deleted), then `:current-source {:enabled true}`
  (`.claude/seon-hook.edn:52`, B1 §5 row 13).

Target after that: a docstring edit publishes the changed file's rows, reloads that
file's namespace plus its stored-graph dependents, re-arms only the changed contracts,
and records adoption. Plan target for that path is ≤ 700 ms (`plan/README.md:184`).
