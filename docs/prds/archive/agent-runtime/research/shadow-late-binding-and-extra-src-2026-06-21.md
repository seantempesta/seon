---
type: research
status: active
tags: [research, agent]
---

# Shadow late-binding (`:static-fns`) + the SEON_EXTRA_SRC compile path

Research for the runtime-override capability, 2026-06-21. Every claim is
grounded in vendored source: shadow-cljs `reference-code/shadow-cljs`
(HEAD `8236315a`) and the ClojureScript compiler
`reference-code/clojurescript`. Exact `file:line` citations throughout.

The capability under test: a seon agent OVERRIDES a fn at runtime
(redefine via `(defn …)` upsert, or `(set! seon.demo/greeting f)`) and an
EXISTING compiled caller (`seon.demo/greet-loudly`, which calls
`(greeting)`) picks up the override. This requires LATE BINDING — the
caller must read the var slot at call time, not capture a static
reference. (Fixture: `src/seon/demo.cljs`.)

## TL;DR — per-level verdict

| `:optimizations` | shadow `:static-fns` value | override flows through? | one-line why |
|---|---|---|---|
| **`:none`** (pod dev / `watch client`, AND `compile test`) | `true` | **YES** | cross-ns var ref is emitted as a GLOBAL property read `seon.demo.greeting` (`cljs_hacks.cljc:674`); `set!` reassigns that same slot (`compiler.cljc:1328`). `:static-fns` only changes the *invoke shape*, not the slot read. |
| **`:simple`** (only on an actual `release`; NOT used today) | `true` | **YES** (mechanism identical) | same global-property emission. Closure `:simple` renames/minifies but does not do whole-program DCE/inlining that would sever a mutated global slot. Note: `:simple`/`:advanced` also FLATTEN ns objects, which breaks seon's OWN `goog.global`-walking resolver — a separate reason the pod stays dev-compiled (`bin/test-cljs:38-44`). |
| **`:advanced`** (explicitly NOT targeted) | `true` (forced) | **NO — CRITICAL** | static-fns true + Closure cross-module code motion + DCE/inlining can prove `seon.demo.greeting` constant and inline/rename it; a runtime `set!` then updates a slot nobody reads. Override silently breaks. |

**Headline:** at the two levels the pod actually runs (`:none` for both
the live pod and the test suite), `set!`/redefine override DOES flow
through to compiled callers. The mechanism is robust because CLJS emits
*every* cross-ns var reference — static-fns or not — as a live global
property read of `ns.var`; `set!` writes that exact property. **`:advanced`
is the only level that breaks it**, and `:advanced` is explicitly out of
scope (`docs/prds/agent-runtime/tile-isolation-prd-2026-06-21.md:435,494`).

CRITICAL subtlety worth stating loudly: shadow-cljs **defaults
`:static-fns` to `true` at ALL levels** (`build/api.clj:66`), unlike vanilla
ClojureScript which defaults it `false` and only forces `true` at
`:advanced` (`closure.clj:2563-2565,3024-3027`). So a naive "static-fns is
off at `:none`, therefore late-bound" reasoning is WRONG for shadow. Late
binding survives anyway — but for the var-emission reason below, not
because static-fns is off.

## 1. `:static-fns` defaults per level [source-cited]

### Shadow's global default: `:static-fns true` regardless of level

`reference-code/shadow-cljs/src/main/shadow/build/api.clj:64-92` —
`default-compiler-options` is the seed `:compiler-options` for every build
state:

```clojure
(def default-compiler-options
  {:optimizations :none
   :static-fns true            ;; <-- line 66, true by default
   ...})
```

This map is installed unconditionally into the build state at
`api.clj:150-151` (`:compiler-options default-compiler-options`). User
`:compiler-options` and `--config-merge` are deep-merged ON TOP
(`api.clj:233-237`, `build.clj:329-334`), so `:static-fns` stays `true`
unless someone explicitly sets it `false`. The seon builds never do.

The mode handling in `build.clj` confirms NEITHER mode touches
`:static-fns`:

- `:dev` mode (`build.clj:389-398`): sets `{:optimizations :none}`,
  `goog.DEBUG true`, source maps — does NOT set `:static-fns`.
- `:release` mode (`build.clj:401-409`): sets
  `{:optimizations :advanced :elide-asserts true :load-tests false
  :pretty-print false}` — does NOT set `:static-fns` either; it stays the
  default `true`.

There is NO shadow code path that derives `:static-fns` from
`:optimizations`. Grep of the entire `src/main/shadow/` tree for
`static-fns` returns only `api.clj:66` (the default), `compiler.clj:719-723`
(the binding), `compiler.clj:914` (a cache-affecting-options entry, with the
comment "these should basically never be changed"), and `cljs_hacks.cljc`
emission sites. The analyzer reads it via the binding at
`compiler.clj:722-723`:

```clojure
(binding [ana/*cljs-static-fns* (true? static-fns)
          ana/*fn-invoke-direct* (true? fn-invoke-direct) ...])
```

`:fn-invoke-direct` defaults to ABSENT (commented out at `api.clj:70`), so
`(true? fn-invoke-direct)` → `false`.

### Mode mapping: `compile`/`watch` = `:dev`; `release` = `:release`

- `compile` → `compile*` → `(util/new-build build-config :dev opts)`
  (`devtools/api.clj:293-296`).
- `release` → `(util/new-build build-config :release opts)`
  (`devtools/api.clj:328`).
- `watch` → workers use `:dev` (`devtools/server/worker/impl.clj:195,205`).

And `:dev` mode FORCES `:optimizations :none`, OVERRIDING whatever a build's
own `:compiler-options :optimizations` says (`build.clj:441-443`):

```clojure
(cond->
  (= :dev mode)
  (assoc-in [:compiler-options :optimizations] :none))   ;; line 442-443
```

**Operationally critical:** the `:test` build in `shadow-cljs.edn` sets
`:optimizations :simple`, but `bin/test-cljs` runs `clj -M:cljs compile test`
(`bin/test-cljs:58`) → `:dev` mode → `:none`. So that `:simple` is DEAD
unless someone runs an actual `release test`, which nothing does. **Both the
live pod (`watch client`, `bin/seon:187`) and the test suite run at
`:none`.** A "release" of the pod, per `bin/test-cljs:38-44`, would be
`:simple` at most (`:advanced` is rejected) — but the pod is shipped
dev-compiled today.

### Vanilla CLJS (the contrast)

`reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:61-62`:
`(def ^:dynamic *cljs-static-fns* false)` and `*fn-invoke-direct* false`.
`reference-code/clojurescript/src/main/clojure/cljs/closure.clj:2563-2565`
and `3024-3027`: static-fns is forced `true` ONLY when
`(= optimizations :advanced)` and not explicitly false. So vanilla
`:none`/`:simple` = static-fns false; shadow = static-fns true at every
level. This difference does NOT change the late-binding verdict (see §2) —
it only changes the invoke shape.

## 2. Call emission: late-vs-static [source-cited]

The decisive fact: **a cross-ns var reference is emitted as a live global
property read, independent of `:static-fns`.** static-fns only selects the
*invoke method shape*.

### Var reference emission — always the global munged name

shadow registers `shadow-emit-var` for the `:var` op
(`cljs_hacks.cljc:1343`). Its fallback branch (`cljs_hacks.cljc:671-674`):

```clojure
:else
(when-not (= :statement (:context env))
  (comp/emit-wrap env
    (comp/emits (comp/munge info))))      ;; line 674
```

`(comp/munge info)` emits the fully-qualified munged name, e.g.
`seon.demo.greeting` — a property read off the `seon.demo` namespace object
at the moment of evaluation. Vanilla CLJS does the identical thing:
`emit-var` ends in `(emits info)` (the munged global)
(`clojurescript/.../compiler.cljc:455-497`, esp. the final `(emits info)`
at line 495/497). So in BOTH compilers, `greeting` inside `greet-loudly`
compiles to a fresh global read of `seon.demo.greeting` every call.

### `:static-fns` selects the invoke shape, not the slot read

shadow's invoke analysis (`shadow-parse-invoke*`, `cljs_hacks.cljc:865`):

- **static-fns FALSE** (`cljs_hacks.cljc:1011-1013`): a known fn-var (that
  is not a JS/foreign/`not-native` special-case) falls to
  `(make-invoke :dot-call …)`. Emit (`cljs_hacks.cljc:1340-1341`):
  `f.call(null, …)` where `f` = `seon.demo.greeting`.
- **static-fns TRUE** (`cljs_hacks.cljc:1015-1056`): a known single-arity
  fn-var goes to `(make-invoke :ifn …)` with `:info :name` rewritten to the
  munged static name (`cljs_hacks.cljc:1046-1056`). Emit
  (`cljs_hacks.cljc:1317-1320`): `f.cljs$core$IFn$_invoke$arity$N(…)`
  (the `as-ifn-prop` suffix) where `f` is STILL `seon.demo.greeting`.

In every invoke-type case (`cljs_hacks.cljc:1292-1341`), `f` is emitted via
`shadow-emit-var`, i.e. the global munged name. The `:ifn` path reads
`seon.demo.greeting` then dereferences its arity method; the `:dot-call`
path reads `seon.demo.greeting` then `.call`s it. **Both read the live
global slot first.** That is why late binding survives static-fns: the
override replaces the value at `seon.demo.greeting`, and the next call reads
the new value before invoking it.

(Cross-ns calls to other namespaces also hit the
`cljs_hacks.cljc:989-994` branch — "ns resolved but not in analyzer
namespaces" → `:fn` direct — only for JS/unknown nses; seon-to-seon calls
are known fn-vars and take the `:ifn`/`:dot-call` paths above. Both still
read the global slot.)

### Why `:advanced` is the exception

Under `:advanced`, the Google Closure Compiler runs whole-program
optimization: cross-module code motion, dead-code elimination, function
inlining, and property collapsing/renaming. shadow even rewrites top-fn
`set!` analysis to "restore :methods information" precisely because
"top level fns are decomposed for Closure cross-module code motion"
(`clojurescript/.../analyzer.cljc:2770-2783`). If Closure proves
`seon.demo.greeting` is assigned once and never reassigned in the analyzed
program, it may inline the call target or rename the slot — at which point a
RUNTIME `set!` (invisible to the static analysis) updates a property no
caller reads. Override silently no-ops. This is the documented
advanced-compile hazard class
(`tile-isolation-prd-2026-06-21.md:349-355,435,494,545-560`). No source
flips this on for `:none`/`:simple`, so the breakage is confined to
`:advanced`.

## 3. `set!` var semantics [source-cited]

`(set! seon.demo/greeting f)` reassigns the same global slot late-bound
callers read.

Analyzer parse — `clojurescript/.../analyzer.cljc:2723-2793`:

- A symbol target is analyzed via `(analyze-symbol enve target)`
  (`analyzer.cljc:2756`), yielding a `:var` op (the same op the reader of
  `greeting` produces).
- Guard: if the var resolves as `:const` it throws "Can't set! a constant"
  (`analyzer.cljc:2747-2748`). A `defn`'d fn is not const, so it passes.
- Result AST: `{:op :set! :target texpr :val vexpr …}`
  (`analyzer.cljc:2792`).

Compiler emit — `clojurescript/.../compiler.cljc:1326-1328`:

```clojure
(defmethod emit* :set!
  [{:keys [target val env]}]
  (emit-wrap env (emits "(" target " = " val ")")))
```

`target` is the var emitted via the `:var` emit = the munged global
`seon.demo.greeting`. So `(set! seon.demo/greeting f)` emits JS
`(seon.demo.greeting = f)` — a direct assignment to the EXACT property that
compiled callers read in §2. Therefore the write target and the read target
are byte-identical; the override is visible to all existing callers
immediately. This matches the live proof recorded in the task
(`(set! seon.demo/greeting (fn [] "X"))` makes `(seon.demo/greet-loudly)`
return `"X!"` under the pod's `:none` build).

Redefine (`(defn greeting …)` upsert, the canonical override in
`db-is-the-running-system-2026-06-17.md`) reaches the same slot: a `def`
emits an assignment to `seon.demo.greeting`, so re-evaluating the form
overwrites the same property. Both override paths depend on the identical
global-slot property the callers read.

## 4. The SEON_EXTRA_SRC / preload / config-merge compile path [source-cited]

Goal: a downstream consumer sets `SEON_EXTRA_SRC` (a tiny deps.edn project)
+ `SEON_EXTRA_PRELOAD` (an entry ns that calls
`(reset! seon.client/!extra-core-vars (specced-fn-vars))`), and its specced
fns get COMPILED, ANALYZED, ENUMERATED, and INDEXED into the pod's program
graph — overridable like the core's own. **Confirmed end-to-end: the path
is fully implemented (not just designed) on both the shadow side and the
seon side.**

### (a) classpath: `:local/root` joins the build via deps mode

`bin/seon`'s helpers inject the extra root + preload into every
`clj -M:cljs …` invocation (`bin/seon:141-150`):

```sh
extra_src_sdeps()      -> -Sdeps '{:deps {seon.extra/src {:local/root "<SEON_EXTRA_SRC>"}}}'
extra_preload_merge()  -> --config-merge '{:devtools {:preloads [<SEON_EXTRA_PRELOAD>]}}'
```

Verified live via `bin/seon print-cmd cljs-watch` with the env set — output:

```
clj -Sdeps '{:deps {seon.extra/src {:local/root "/…/tmp/acme-extra"}}}' \
    -M:cljs watch client --config-merge '{:devtools {:preloads [acme.pod]}}'
```

shadow runs in DEPS MODE (`shadow-cljs.edn` opens with
`{:deps {:aliases [:cljs]}}`), so the tools.deps classpath IS the build's
source-path set (`shadow-cljs.edn` records the 2026-06-09 live proof that
`:source-paths` is ignored in deps mode). A `:local/root` dep therefore puts
`<SEON_EXTRA_SRC>/src` on the build classpath (the research doc's `-Spath`
probe confirmed this: `extra-src-research-2026-06-12.md` §"Live probe").

### (b) `--config-merge` concats `:preloads`

`--config-merge DATA` is a real CLI option
(`cli_opts.cljc:67-73`; must yield a map). It is applied LAST in config
assembly (`build.clj:329-334`):

```clojure
(-> (get-build-defaults …)
    (deep-merge (get-target-defaults …))
    (deep-merge config)
    (config-merge mode)
    (util/reduce-> deep-merge (:config-merge cli-opts)))   ;; line 334
```

`deep-merge`'s vector case is `(concat a b) → distinct → vec`
(`api.clj:37-40`), so
`--config-merge '{:devtools {:preloads [acme.pod]}}'` APPENDS `acme.pod`
to the `:client` build's existing
`:devtools {:preloads [seon.dev.test-preload seon.demo]}` — the seon
preloads are kept, not replaced.

### (c) preloads are compiled AND analyzed into the build

For `:node-script` in `:dev` mode, `configure` calls
`(shared/inject-preloads :main config)` (`targets/node_script.clj:45-46`).
`inject-preloads` PREPENDS the preload nses onto the `:main` module's
`:entries` (`targets/shared.clj:252-257`):

```clojure
(update-in state [::modules/config module-id :entries] prepend preloads)
```

Module `:entries` are resolved with their full transitive require closure,
compiled, and their analysis lands in `:cljs.analyzer/namespaces` (the
compiler env) — exactly like `:main`. So the preload ns AND everything it
`:require`s (the downstream's surface) are compiled and analyzed before
`:main` runs (preloads load first). Preloads run on `compile`/`watch` only
(`:devtools`-scoped, dev mode) — fine, the pod is dev-compiled. (Confirmed
for the `:test` build too: node-test honors preloads —
`bin/test-cljs:50-51` cites `node_test.clj:50`.)

### (d) seon-side enumeration + indexing (already implemented)

The macro `seon.indexing/specced-fn-vars` reads the analyzer env at
MACROEXPANSION time and enumerates every public specced fn-var in the
CALLING ns's transitive require closure (`src/seon/indexing.clj:86-104`):
it walks `:cljs.analyzer/namespaces` (`indexing.clj:30-33`), follows
`:requires` edges from the calling ns (`indexing.clj:35-45,92-93`), and
keeps defs that are `:fn-var`, public, carry `:malli/schema`, and pass
`first-party-file?` (`indexing.clj:98-102`).

`first-party-file?` now accepts files under `SEON_EXTRA_SRC` in addition to
`user.dir` (`indexing.clj:54-86`):

```clojure
(defn- first-party-roots []
  (let [extra (System/getenv "SEON_EXTRA_SRC")]
    (cond-> [(System/getProperty "user.dir")]
      (and extra (not= extra "")) (conj extra))))
```

So when the downstream preload ns invokes `(specced-fn-vars)` inside its own
macroexpansion (its require closure pulls its surface), its specced fns
ARE enumerated (they pass the extra-root filter).

The client side consumes the result (`src/seon/client.cljs`):

- `(defonce !extra-core-vars (atom []))` (`client.cljs:912`) — the
  registration target. The preload does
  `(reset! client/!extra-core-vars (specced-fn-vars))` (`client.cljs:905`
  docstring; the precedent is `!indexed-test-vars`).
- `extra-core-vars*` dedups extras already in `core-vars` by FQ-sym
  (`client.cljs:914-926`) and `assert-no-reserved-extra-nses!` refuses
  `seon.*` / `my.*` prefixes LOUDLY (`client.cljs:928-956`).
- `index-core!` builds `:seon.fn` + `:seon.ns` rows from the extras via
  REAL runtime introspection — source + spec — and `read-src-file` probes
  `$SEON_EXTRA_SRC/src` and `/test` (raw, after the artifact roots) so the
  full file source is captured (`client.cljs:997-1021`, esp. 1011-1015,1020).
- `core-ns-set` concats `@!extra-core-vars` (`client.cljs:995`), so the
  extra nses join the REPLAY-SKIP set (compiled code must not be re-evaled
  from the store) and render alongside the core.

This is verified by the committed test `extra-core-test.cljs` against the
committed fixture `acme.extra-fixture` (under seon's own `test/` root, no
env var needed): it asserts the fn-row has real source + spec, the ns-row
carries the FULL file source, the ns joins `core-ns-set`, reserved-prefix
extras throw, and core-overlap dedups silently.

Net: the chain — `:local/root` classpath → `--config-merge` preload concat
→ preload compiled+analyzed → `specced-fn-vars` enumerates (extra root
allowed) → `!extra-core-vars` registration → `index-core!` produces graph
rows + `core-ns-set` membership — is implemented and source-grounded. The
downstream fns become indexed, and because they are dev-compiled `:none`
globals, they are overridable by the §2/§3 late-binding mechanism exactly
like the core's own.

## 5. Live reproduction recipe (do NOT run against the live pod)

A committed in-repo fixture exists for the SUITE-level proof (no env, no
checkout): `test/acme/extra_fixture.cljs` + `test/seon/client/extra_core_test.cljs`.
Run it with `bin/test-cljs` (or the single ns). That proves enumeration +
indexing + replay-skip + reserved-prefix refusal WITHOUT a separate build.

For the full END-TO-END `SEON_EXTRA_SRC` build proof (a separate downstream
deps.edn project compiled INTO the pod bundle), a scratch project already
exists at `tmp/acme-extra/` (gitignored):

- `tmp/acme-extra/deps.edn` = `{:paths ["src"]}`
- `tmp/acme-extra/src/acme/pod.cljs` = an entry ns with one specced fn.

STALE-NAME WARNING: the existing `tmp/acme-extra/src/acme/pod.cljs` resets
`client/!extra-substrate-vars` — that is the OLD atom name from the
2026-06-12 research doc. The IMPLEMENTED atom is `!extra-core-vars`
(`client.cljs:912`). Fix the preload to:

```clojure
(ns acme.pod
  (:require [clojure.string :as str]
            [seon.client :as client])
  (:require-macros [seon.indexing :refer [specced-fn-vars]]))

(defn shout
  {:malli/schema [:=> [:cat :string] :string]}
  [s] (str/upper-case s))

(reset! client/!extra-core-vars                 ;; was !extra-substrate-vars
        (filterv #(str/starts-with? (str (:ns (meta %))) "acme.")
                 (specced-fn-vars)))
```

### Build command (a FRESH, separate JVM — never the running cljs-watch)

The running pod must NOT be disrupted (its watcher classpath is fixed at
launch; setting `SEON_EXTRA_SRC` requires restarting cljs-watch). Do a
ONE-SHOT compile into a SCRATCH output dir, in a fresh JVM, so the live
`out/client/main.js` is not clobbered:

```bash
cd /Users/sean/src/seon
SEON_EXTRA_SRC=$PWD/tmp/acme-extra SEON_EXTRA_PRELOAD=acme.pod \
clj -Sdeps '{:deps {seon.extra/src {:local/root "tmp/acme-extra"}}}' \
    -M:cljs compile client \
    --config-merge '{:devtools {:preloads [acme.pod]}}' \
    --config-merge '{:output-to "tmp/acme-extra-out/main.js"}'
```

(`compile` = `:dev` = `:none` — the override-friendly level. The exact
`-Sdeps`/`--config-merge` flags are what `bin/seon print-cmd cljs-watch`
emits with the two env vars set; the extra `:output-to` config-merge
redirects output so the live bundle is untouched — `deep-merge`'s string
case replaces, `api.clj:45-46`.)

### Run + assert (against the scratch bundle, not the live pod)

```bash
SEON_EXTRA_SRC=$PWD/tmp/acme-extra node tmp/acme-extra-out/main.js
```

Then, in a REPL session on THAT scratch runtime (or via a smoke ns):

1. **Indexed.** `(client/index-core!)` returns a row with
   `:seon.fn/sym "acme.pod/shout"` whose `:seon.fn/source` includes
   `"defn shout"` and whose `:seon.fn/spec` is present; and a
   `:seon.ns/name :acme.pod` row whose `:seon.ns/source` is the FULL file
   (includes the ns docstring). `(contains? (client/core-ns-set) :acme.pod)`
   is true. (Same assertions as `extra_core_test.cljs`, but driven by the
   real compiled-in build rather than a hand `reset!`.)
2. **Overridable (the capability).** Define a caller in the same ns or eval
   one that calls `(acme.pod/shout "x")`; confirm it returns `"X"`. Then
   `(set! acme.pod/shout (fn [_] "OVERRIDDEN"))` (or redefine via
   `(defn shout …)`), call the caller again, confirm `"OVERRIDDEN"`. This
   proves a DOWNSTREAM fn participates in the same `:none` late-binding the
   core's `greet-loudly`/`greeting` proof established.

Assert NOTHING about `:advanced`/`:simple` here — those are not the pod's
runtime and `:advanced` would break override (§2).

## Smells reported

- `tmp/acme-extra/src/acme/pod.cljs` references `client/!extra-substrate-vars`
  (the pre-implementation atom name). The implemented atom is
  `!extra-core-vars` (`client.cljs:912`). Scratch/gitignored, so harmless,
  but it will mislead anyone copying it as the downstream template — the
  recipe above gives the corrected form. Worth fixing the scratch file or
  promoting a corrected `acme.pod` template into docs.
- shadow's `:static-fns true` default at `:none` is a real footgun for
  anyone reasoning about CLJS late binding from vanilla-CLJS intuition
  (vanilla = false at `:none`). It does not break override here, but the
  reasoning "static-fns is off so it's late-bound" is wrong for shadow —
  the late binding comes from the var-emission model (§2), not from
  static-fns being off.
