---
type: research
status: active
tags: [research, cljs, build]
---

# Third-party EXPLICIT override of core at build time (two source paths)

Direct answer to Sean's question: how the seon CLJS build handles two source
paths with overlapping definitions, and how to make third-party code EXPLICITLY
override core. Every claim is grounded in vendored shadow-cljs / ClojureScript
source, the committed build config, or a live read-only `clj -Spath` probe run
this session (2026-06-17). No build/config edits made; no recompile triggered.

## TL;DR

- **Two source paths with the SAME namespace is NOT a hard error and NOT
  last-wins. It is FIRST-ON-CLASSPATH-WINS, with an info-level log.** shadow's
  `index-rc-merge` keeps the resource already indexed for a `resource-name` and
  DROPS the later one (`classpath.clj:846-855`), emitting `::duplicate-resource`
  ("duplicate resource X on classpath, using A over B"). Vanilla cljs is the
  same by construction: it resolves a ns via `io/resource` (`util.cljc:101-102`,
  `analyzer.cljc:2798 locate-src`), which returns the first classpath match.
- **Today, CORE wins, not third-party.** Live `clj -Spath` probe: seon's own
  `src` is classpath entry #3; the injected downstream `:local/root` src is #37
  (down with the deps). tools.deps puts the project's own `:paths`/`:extra-paths`
  ahead of dependency paths, so a downstream file with the same `seon/foo.cljs`
  name is the LATER duplicate and gets dropped. The current plumbing therefore
  CANNOT make third-party override a core ns by shipping a same-named file —
  exactly the gap Sean intuited ("I don't think it knows what to do … from two
  source paths with overlapping definitions").
- **Current plumbing (`SEON_EXTRA_SRC`/`SEON_EXTRA_PRELOAD`) is ADDITIVE, not
  override.** It is built for the downstream to ship NEW namespaces under its own
  prefix (`acme.*`); it actively REFUSES `seon.*`/`my.*` via
  `assert-extra-vars-unreserved!` (`client.cljs:937-951`). And `SEON_EXTRA_PRELOAD`
  is not wired operationally — both env vars are unset on the live pod, so the
  downstream entry ns is dead-code (`@!extra-core-vars` = 0). That is the B2 gap.
- **The correct EXPLICIT override mechanism is (c): a third-party PRELOAD ns that
  `(:require [seon.foo])` and REDEFINES specific core vars with normal `(defn …)`
  at load time.** It is the only mechanism that is (1) explicit, (2) per-var
  surgical, (3) collision-free (no duplicate resource), and (4) already supported
  by the shipped plumbing. It works because shadow emits in dependency order
  (`resolve-deps`, `resolve.clj:34-55`: deps first, then the requiring ns), so the
  redefinition `:def` (`compiler.cljc :def`) re-assigns the munged core global
  AFTER core's module-load, and dev-build late-binding (`*cljs-static-fns* false`)
  makes every existing caller pick it up. Same-name file replacement (a/b) is
  rejected because the classpath order is wrong AND only file-vs-jar overrides are
  honored (two fs dirs just warn + drop, never override).
- **Dev-build invariant holds:** the `:client` build has no `:optimizations` key →
  dev `:none` → `goog.DEBUG true`, `*cljs-static-fns* false`. Override (and the
  preload merge itself) is a DEV-BUILD-ONLY capability; `:advanced` silently
  no-ops both. Flag, don't block (matches the PRD's decision #3).
- **Naming:** call the shadow-compiled base the **compiled package** (the PRD's
  own term) = kernel + core + third-party-base. Reserve **"override"** for the
  load-time redefinition layer. Suggested env name if a dedicated override slot is
  wanted: `SEON_OVERRIDE_PRELOAD` (a preload whose job is redefinition), kept
  distinct from `SEON_EXTRA_PRELOAD` (additive new nses) — though one preload can
  do both.

---

## 1. Current plumbing — exactly how `SEON_EXTRA_SRC` reaches the build

`SEON_EXTRA_SRC` is injected as a **tools.deps `:local/root` dependency**, NOT a
shadow `:source-paths` entry and NOT read at runtime by `read-src-file` for the
BUILD. `bin/seon` builds the CLI:

```sh
# bin/seon:101-105
extra_src_sdeps() {
  if [ -n "${SEON_EXTRA_SRC:-}" ]; then
    printf " -Sdeps '{:deps {seon.extra/src {:local/root \"%s\"}}}'" "$SEON_EXTRA_SRC"
  fi
}
```

and attaches it to the `cljs-watch` command (`bin/seon:143`):

```sh
cljs-watch)  echo "clj$(extra_src_sdeps) -M:cljs watch client$(extra_preload_merge)" ;;
```

This is correct because shadow runs in DEPS MODE (`shadow-cljs.edn:7`
`{:deps {:aliases [:cljs]}}`); in deps mode shadow takes its classpath ENTIRELY
from tools.deps and IGNORES the `:source-paths` vector (the file's own comment
records this live proof, `shadow-cljs.edn:32-41`). So the extension point is the
tools.deps classpath, and `:local/root` is right: it brings the downstream's own
deps transitively (live-proven in the prior research, extra-src-research §103-106).

`read-src-file` (`client.cljs:995-1019`) ALSO probes `$SEON_EXTRA_SRC/src` and
`/test`, but that is the BOOT INDEXER reading source TEXT for the DB display rows
(`:seon.ns/source`), entirely separate from compilation. It does not affect what
shadow compiles.

### `SEON_EXTRA_PRELOAD` — wired in code, but operationally unset (the gap)

`extra_preload_merge` (`bin/seon:107-111`) only fires when BOTH vars are set:

```sh
extra_preload_merge() {
  if [ -n "${SEON_EXTRA_SRC:-}" ] && [ -n "${SEON_EXTRA_PRELOAD:-}" ]; then
    printf " --config-merge '{:devtools {:preloads [%s]}}'" "$SEON_EXTRA_PRELOAD"
  fi
}
```

`--config-merge` deep-merges and CONCATS `:preloads` (shadow `build-api/deep-merge`
vector case → `concat → distinct → vec`; cited extra-src-research §116-123), so
`seon.dev.test-preload` is kept. **The precise gap (PRD's "B2 is operational
only"):** both env vars are unset on the live pod, so no preload entry is injected,
so the downstream entry ns is never a module graph root, so it is dead-code and
`@!extra-core-vars` stays 0. Live-confirmed this session and in the prior research
(`{:extra-core-vars 0, :env-extra-src nil, :env-extra-preload nil}`). Why a require
is mandatory: shadow compiles only the dependency closure of a module's `:entries`
(`resolve-entries`, `resolve.clj:714`); classpath presence alone compiles nothing.

---

## 2. Overlapping-definition behavior (THE core question)

### Same `resource-name` (= same ns, e.g. both ship `seon/foo.cljs`)

shadow indexes the classpath with `index-rc-merge` (`classpath.clj:796-924`). The
load-bearing branch (`classpath.clj:846-855`):

```clojure
;; do not merge files that are already present from a different source path
(when-let [existing (get-in index [:sources resource-name])]
  (not (is-same-resource? rc existing)))
(let [conflict (get-in index [:sources resource-name])]
  (when-not (and (:from-jar rc)
                 (not (:from-jar conflict)))
    ;; only warn when jar conflicts with jar, fs is allowed to override files in jars
    (log/info ::duplicate-resource {:resource-name resource-name
                                    :url-a (:url conflict)
                                    :url-b (:url rc)}))
  index)   ; <-- returns index UNCHANGED: the SECOND file is DROPPED
```

The log message (`classpath.clj:768-769`):

```clojure
(format "duplicate resource %s on classpath, using %s over %s" resource-name url-a url-b)
```

So: **NOT a hard error; FIRST-on-classpath-wins; later duplicate silently dropped
with an info log.** The classpath is processed in order (`index-classpath`,
`classpath.clj:1063-1070`: `(reduce index-path* % paths)` over `get-classpath`
entries; `get-classpath` reads `java.class.path` in order, `classpath.clj:37-47`).

The `:from-jar` clause matters for the override question: the warn is SUPPRESSED
only when an fs file overrides a JAR (`"fs is allowed to override files in jars"`).
`:from-jar` is set for jar entries (`classpath.clj:397,438`) and gitlibs
(`classpath.clj:663,698`). **Two plain filesystem source dirs (seon's `src/` and a
downstream `:local/root` src) are BOTH non-jar, so neither overrides the other —
the later one is dropped AND the duplicate-resource info log fires.** There is no
fs-over-fs override path in shadow.

Vanilla ClojureScript (non-shadow) is the same by construction: `locate-src`
(`analyzer.cljc:2798`) and `cljs-source-for-namespace` (`util.cljc:101-102`)
resolve a ns via `(io/resource (ns->relpath ns :cljs))`, and `io/resource` returns
the FIRST classpath match. No error, first-wins. (Closure's `DUPLICATE_VARS`
diagnostic, `closure.clj:162`, is about advanced-compile var collisions, NOT
two source files for one ns — different layer, irrelevant here.)

### Live classpath-order probe (run 2026-06-17, read-only)

Injected a throwaway `:local/root` project and dumped the ordered classpath:

```sh
clj -Sdeps '{:deps {seon.extra/src {:local/root "tmp/override-probe"}}}' \
    -A:cljs -Spath | tr ':' '\n' | head
# 1  test                       (:cljs :extra-paths)
# 2  dev-resources/konserve-shim
# 3  src                        (seon's own — base :paths)
# 4  resources
# 5… jars …
# 37 /Users/sean/src/seon/tmp/override-probe/src   (the :local/root dep)
```

(Probe dir created + deleted; no recompile triggered.) **Conclusion: seon's own
`src` (#3) precedes the downstream root (#37).** tools.deps places a project's own
`:paths`/`:extra-paths` ahead of dependency paths. Therefore a downstream
`seon/foo.cljs` is the LATER duplicate → DROPPED. **Today the build resolves
core-wins on a same-ns collision — the OPPOSITE of "third-party overrides core."**

### Different ns, but third-party redefines a core VAR (the additive+redef case)

If the third-party ships a DIFFERENT ns (`acme.overrides`) that `(:require
[seon.foo])` and contains `(defn seon.foo/bar …)`-style redefinition (or a `(in-ns
'seon.foo) (defn bar …)`), there is NO duplicate resource — both files compile.
What happens at runtime is governed by the CLJS emit (prior research,
build-merge-and-cljs-semantics §Q3, validated against `compiler.cljc`): a re-eval'd
`(defn bar …)` emits a plain assignment to the munged global
`seon.foo.bar = <new fn>` (`:def` emit), and dev `:none` callers read that global
fresh on every call (`*cljs-static-fns* false` → `:invoke :else` emits
`seon.foo.bar.call(null,…)`). So the redefinition takes effect for all callers —
PROVIDED it loads AFTER `seon.foo`. Load order is dependency order (see §3c), and a
require guarantees the dependency loads first. This is the override path.

---

## 3. How to make third-party EXPLICITLY override core — options evaluated

### (a) Source-path ORDER precedence (third-party path before core so its same-ns file wins)

**Mechanism in principle:** put the downstream root EARLIER on the classpath than
`src`, so its `seon/foo.cljs` is the first-indexed and core's is the dropped
duplicate.

**Verdict: DO NOT USE.** Reasons, all source-grounded:

1. It STILL fires the `::duplicate-resource` info log for every overridden file
   (two fs dirs, no jar, so no warn-suppression; `classpath.clj:849-854`). Noisy
   and fragile.
2. It is whole-FILE replacement, not per-var: the downstream must re-ship the
   ENTIRE `seon/foo.cljs` to change one fn (and keep it in sync forever — the exact
   "two versions drift" trap CLAUDE.md's "don't be a dumbass" warns against).
3. Achieving the order is awkward in deps mode: tools.deps puts the project's own
   `:paths` first (probe above), so you would have to inject the downstream as an
   `:extra-paths`/alias ordered ahead of `src` — which means editing how seon's own
   classpath is assembled, not just adding a dep. There is no clean `bin/seon`
   knob for "before src".
4. It silently breaks `read-src-file`/the boot indexer's source display, which
   probes `src` BEFORE `$SEON_EXTRA_SRC` (`client.cljs:1018`) — the DB would show
   core's source while the bundle runs the downstream's. Two truths.

### (b) Third-party ships REPLACEMENT FILES for whole core nses

This is just (a) restated (a same-name file IS the replacement). Same verdict:
first-on-classpath-wins means it only "works" if ordered ahead of core, with all of
(a)'s problems, plus it cannot do a partial override (you replace the whole ns or
nothing). shadow has NO "merge two files into one ns" path — `index-rc-merge` keeps
exactly one resource per `resource-name`. Rejected.

### (c) Third-party PRELOAD ns that requires core and REDEFINES specific vars — RECOMMENDED

The downstream ships an entry ns under its OWN prefix (e.g. `acme.overrides`) that:

```clojure
(ns acme.overrides
  (:require [seon.foo :as foo]
            [seon.indexing :as ix]))

(defn foo/bar "overridden" [x] …)   ; redefines the core var (in-ns or fully-qual)

;; register the additive surface for DB display (the existing precedent)
(reset! seon.client/!extra-core-vars (ix/specced-fn-vars))
```

It is wired exactly like the existing additive path: `SEON_EXTRA_SRC` puts it on
the classpath; `SEON_EXTRA_PRELOAD=acme.overrides` makes it a graph root via the
`:preloads` merge (`bin/seon:107-111`).

**Why this is the explicit, correct mechanism — source-grounded:**

1. **No duplicate resource.** `acme.overrides` is a distinct `resource-name`; both
   it and `seon.foo` index cleanly. No first-wins fight, no info log.
2. **Per-var surgical.** Override one fn or thirty; the rest of core is untouched
   and stays byte-identical to the compiled package.
3. **Loads AFTER core.** shadow emits in dependency (post-order) order:
   `resolve-deps` (`resolve.clj:34-55`) recurses into a resource's deps FIRST, then
   conjes the resource onto `:resolved-order` (line 50-55). Because
   `acme.overrides` `(:require [seon.foo])`, `seon.foo` is emitted/module-loaded
   first; the override `:def` runs second and re-assigns the munged global. The
   redefinition wins.
4. **Late-binding makes callers pick it up.** Dev `:none` build (§4) →
   `*cljs-static-fns* false` → every cross-ns call re-reads the munged global
   (`compiler.cljc` `:invoke :else`), so existing core callers route to the new fn
   with no recompilation. (Full emit chain in
   build-merge-and-cljs-semantics-2026-06-17 §Q3; validated against
   `compiler.cljc emit-var`/`:def`/`:invoke`.)
5. **It is the existing plumbing.** `:preloads`-as-graph-root is the documented
   shadow mechanism (`inject-preloads`, `shared.clj:252-257`; the
   `seon.dev.test-preload` precedent, `test_preload.cljs:76`). No new build
   machinery.

**The ONE seon-side change required:** the override preload must be allowed to
register/redefine `seon.*` vars. Today `assert-extra-vars-unreserved!`
(`client.cljs:937-951`) THROWS if any registered extra var's ns starts with `seon.`
or `my.`. That guard is correct for the ADDITIVE path (downstream new nses must not
masquerade as core), but it must NOT block a deliberate override. Two clean options:

- Keep `!extra-core-vars` strictly additive (downstream-prefixed, guard intact),
  and DO NOT register the redefined core vars into it at all — the redefinition
  still takes effect at load time (it is a plain `:def` on the core global); the DB
  display row for `seon.foo/bar` is then re-derived from the compiled var-meta each
  boot (the PRD's "DB is a derived display index" model), and will reflect the
  overridden value because var-meta reads the live var. This needs ZERO guard
  change. **Preferred** — it fits "redefinition = upsert, no override origin".
- OR, if overrides should be explicitly tracked, add a separate
  `!override-vars` atom + a distinct registration fn that is EXEMPT from the
  reserved-prefix guard (because overriding `seon.*` is its whole job), and
  surface those rows as `:seon.db/origin :override` for display. More machinery;
  only if Sean wants override provenance visible.

**What changes, precisely:**

- `bin/seon` — NOTHING (plumbing already correct); operationally, start
  `cljs-watch` with `SEON_EXTRA_SRC` + `SEON_EXTRA_PRELOAD` set.
- `shadow-cljs.edn` — NOTHING (dev `:client`, `:preloads` concat already works).
- `src/seon/client.cljs` — NOTHING for the preferred (display-derived) variant;
  OR add `!override-vars` + a guard-exempt register fn for the tracked variant.

### (d) The DB-index path (third-party WITHOUT a build → indexed as rows + eval'd)

Out of scope for THIS question (Sean asked about the BUILD path / two source
paths), but noted as the fallback for any case shadow can't cleanly do: a
no-build downstream's `.cljs` is read, indexed as `:seon.fn`/`:seon.ns` rows, and
eval'd on resume uniform with agent code (PRD §"Third-party bulk delivery", ~1-3 s).
Redefining a core fn that way is the same upsert-on-`:seon.fn/sym` + re-eval
mechanism — no build involvement. Use this when the downstream has no build, or
when a deep override needs to also redefine an aliased re-export (the alias-capture
hazard, build-merge §Q3 PROOF 3, is identical on both paths and is fixed once by
auditing `seon.*` re-export aliases).

### When shadow CANNOT cleanly do it → fall back to (d)

- The downstream has no build / no deps.edn project. Use (d).
- A `:release`/`:advanced` pod is required: `:preloads` is dev-only
  (`inject-preloads` gated on `(= :dev mode)`, node_script.clj:45-48, cited in
  build-merge §Q1) AND `*cljs-static-fns*` flips true, so the override `:def`
  re-point silently no-ops at call sites that inlined the arity method (build-merge
  §Q3). Both the preload merge AND override die under `:advanced` — same invariant.
  No clean build fix; the override would have to be compiled INTO the package
  (i.e. the downstream forks/replaces the file at build time, accepting drift).

---

## 4. Dev-build invariant

Overriding a core var via a third-party preload redefinition REQUIRES late
binding: `goog.DEBUG true` / `*cljs-static-fns* false`. Confirmed the current
build is dev-compiled:

- `shadow-cljs.edn` `:client` (lines 58-77) has NO `:optimizations` key → shadow
  defaults a `watch`/`compile` to dev `:none`. Grep this session: no `advanced`,
  no `static-fns`, no `optimizations` on `:client`.
- Prior research live-confirmed `goog.DEBUG=true` on the running pod
  (build-merge-and-cljs-semantics-2026-06-17 §TL;DR + §Q3).
- `*cljs-static-fns*` defaults `false` (`analyzer.cljc:61`) and is only set true
  under `:advanced`/explicit `:static-fns` (`closure.clj` static-fns binding,
  cited build-merge §Q3). Dev `:none` leaves it false → cross-ns calls re-read the
  global, redefinition propagates.

So the recommended mechanism (c) is valid on today's build. The invariant is
LOAD-BEARING: an `:advanced` pod silently no-ops both the preload merge and every
override. Flag `:advanced` as a future issue, not a blocker (matches PRD decision
#3). If the pod ever goes `:advanced`, the override must be compiled into the
package (no runtime redefinition), i.e. option (a)/(b) file replacement with all
their drift cost — another reason the pod should stay dev-compiled.

---

## 5. Naming

The PRD already coined the right umbrella term — use it consistently:

- **Compiled package** = kernel + core + third-party-base, shadow-compiled into
  `out/client/main.js`, module-loaded once. (Alternatives "base image" / "bundle"
  are fine but "compiled package" is the PRD's word — keep one term.)
- **Override layer** = the load-time redefinitions (third-party preload `:def`s)
  that re-point compiled core globals. Distinct from the **DB layer** (agent code +
  redefinitions loaded from datahike).
- Within third-party, distinguish **additive** surface (new `acme.*` nses,
  registered into `!extra-core-vars`) from **override** surface (redefinitions of
  `seon.*` vars). They can ride the same preload but are conceptually two jobs; if
  a dedicated env knob is wanted, `SEON_OVERRIDE_PRELOAD` reads clearly alongside
  `SEON_EXTRA_PRELOAD`. Minor — one preload doing both is also fine.

Avoid "third-party core" (the PRD's tentative phrasing) — it conflates "compiled-in
base third-party code" with "overrides of core", which are different mechanisms
(additive compile vs load-time redefinition).

---

## Recommendation (one mechanism)

**Ship option (c): a third-party PRELOAD ns that `(:require)`s the core nses it
overrides and redefines specific vars with normal `(defn …)`.** It is explicit,
per-var, collision-free, dependency-ordered (loads after core), and runs on the
already-shipped `SEON_EXTRA_SRC` + `SEON_EXTRA_PRELOAD` + `:preloads` plumbing with
ZERO build-config changes. The only operational fix is the known B2 gap (set both
env vars on `cljs-watch`). The only code question is whether to let overrides touch
`seon.*`: the preferred answer is to NOT register redefined core vars into
`!extra-core-vars` at all (leave the reserved-prefix guard intact) — the
redefinition takes effect at load time regardless, and the DB display row
re-derives from the (now-overridden) live var-meta each boot.

Reject same-name file replacement (a/b): the live classpath order is core-first
(probe: src #3 vs local-root #37), shadow only honors fs-over-jar override (two fs
dirs just warn + drop the later one, `classpath.clj:846-855`), and whole-file
replacement is the drift trap. Fall back to the DB-index path (d) only for no-build
downstreams or `:advanced` pods.

---

## Source citations

- `bin/seon:101-111` — `extra_src_sdeps` (`-Sdeps :local/root`), `extra_preload_merge`
  (`--config-merge :preloads`, fires only when BOTH vars set); `:143` cljs-watch wiring.
- `shadow-cljs.edn:7,32-41` — deps mode, `:source-paths` ignored; `:58-77` `:client`
  dev `:node-script`, no `:optimizations`; `:68-69` `:preloads [seon.dev.test-preload]`.
- `src/seon/client.cljs:907` `!extra-core-vars`; `:909-921` `extra-core-vars*`
  (sym-dedup vs core); `:937-951` `assert-extra-vars-unreserved!` (reserved-prefix
  THROW for `seon.*`/`my.*`); `:995-1019` `read-src-file` (`src` before `$SEON_EXTRA_SRC`).
- `reference-code/shadow-cljs/src/main/shadow/build/classpath.clj:37-47` `get-classpath`
  (classpath order); `:768-769` `::duplicate-resource` log; `:777-794` `index-rc-merge-js`;
  `:796-924` `index-rc-merge`, esp. `:846-855` first-wins-drop-later + `:from-jar`
  warn-suppression ("fs is allowed to override files in jars"); `:1063-1070`
  `index-classpath` (reduce over paths in order).
- `reference-code/shadow-cljs/src/main/shadow/build/resolve.clj:34-55` `resolve-deps`
  (post-order = dependency-order emit); `:714` `resolve-entries` (compile only the
  closure of a module's `:entries`).
- `reference-code/shadow-cljs/src/main/shadow/build/targets/shared.clj:124-126`
  `prepend`; `:252-257` `inject-preloads` (preloads prepended to module `:entries`).
- `reference-code/clojurescript/src/main/clojure/cljs/util.cljc:101-102`
  `cljs-source-for-namespace` (`io/resource` first-match); `…/analyzer.cljc:2798`
  `locate-src`; `:61` `*cljs-static-fns* false` default.
- Live probe (2026-06-17): `clj -Sdeps '{:deps {seon.extra/src {:local/root …}}}'
  -A:cljs -Spath` → seon `src` #3, downstream `:local/root` src #37 (throwaway probe
  dir created + deleted, no recompile).
- Prior research (build the chain on, do not re-derive):
  - [[docs/prds/agent-runtime/research/build-merge-and-cljs-semantics-2026-06-17.md]]
    — `:preloads` graph-root + dev-only scoping (Q1); analyzer-state authority (Q2);
    the override emit chain `emit-var`/`:def`/`:invoke`, `*cljs-static-fns*`,
    alias-capture hazard (Q3). THIS doc adds the missing piece: the two-source-path
    duplicate-resource resolution + live classpath order.
  - [[docs/prds/agent-runtime/research/extra-src-research-2026-06-12.md]] — the
    original `SEON_EXTRA_SRC`/`SEON_EXTRA_PRELOAD` design + `-Spath` proof.
  - [[docs/prds/agent-runtime/db-is-the-running-system-2026-06-17.md]] — the model
    (compiled package + DB layer; §"Third-party bulk delivery").
