---
type: research
status: active
tags: [research, agent, architecture]
---

# Sci routing seam for derived placement (2026-07-20)

Where the "placement is derived from requires" design attaches to sci,
grounded in `reference-code/sci` at `be4021d` (origin/master; the JIT
commit `45bcf0f` and `sci.interrupt` are upstream — nothing in this
audit is fork-only source). Owner intent: per-agent sci contexts on a
JVM host; every non-local capability (db, the pod as the cluster's
shared JS host, Java packages, OS tools) is a remote function call with
a pure-data transit boundary; the require graph already persisted as
`:seon.ns/require-edges` maps each required namespace to its host, and
the eval boundary synthesizes the crossing so the agent perceives one
platform. Vocabulary: agent context, runtime hosts, allowlisted binding
tables.

Probes (reproducible, committed):

- `tmp/sci-probe/seam_probe.clj` — JVM: `:load-fn` generated-stub
  provisioning, laziness/caching, fork isolation, registry propagation
  to live forks, `merge-opts` live extension, unresolved-symbol
  behavior, ctx/env inventory.
- `tmp/sci-probe/seam_probe2.clj` — JVM: the recommended hybrid seam
  (`:load-fn` injecting wrapper-var maps, no generated source).
- `tmp/sci-probe/src/probe/seam.cljs` (`build`: same `cljs.main
  -t nodejs -O simple` shape as the corpus, run under the vendored
  bun) — CLJS: JIT tier over a `:load-fn`-provisioned namespace.

## 1. Namespace/require resolution

The whole require path is `sci.impl.load/handle-require-libspec`
(`src/sci/impl/load.cljc:161-247`):

- `:ns-aliases` rewrite first (`load.cljc:166`), then **cache check**:
  a lib already present in the env's `:namespaces` map is satisfied
  immediately with no `:load-fn` call (`load.cljc:180`), plus
  `*loaded-libs*` bookkeeping (`load.cljc:149-159`) and cyclic-load
  detection (`load.cljc:181-195`).
- **`:load-fn`** lives in the env (`(:load-fn env)`,
  `load.cljc:198`) and is called only on the first require of an
  unknown lib — laziness is the default behavior, no extra mechanism.
  Call contract (`load.cljc:200-206`): one map
  `{:libname lib :ctx ctx :opts opts :ns current-ns :reload bool}`
  (`:namespace` is the deprecated alias of `:libname`). Return
  `{:file f :source s :handled bool}` or nil (nil ⇒ "Could not find
  namespace", `load.cljc:238-242`).
- When `:source` is present it is evaluated via `load-string*` under
  `with-bindings` of current-ns/file (`load.cljc:218-224`); an eval
  error rolls the namespace back out of the env (`load.cljc:225-227`).
- When `:handled` is absent/false, sci performs the `:as`/`:refer`/
  `:rename`/`:exclude` wiring itself from whatever now sits at
  `[:namespaces lib]` (`load.cljc:228-234`,
  `handle-require-libspec-env` `load.cljc:95-147`). `:handled true`
  means the load-fn did that wiring too.
- On the JVM every `load-lib` takes one **process-global lock**
  (`load.cljc:264-267`). Watch item for an N=100-context host: keep
  the load-fn body a map lookup + env swap (microseconds), never a
  blocking remote round-trip inside the lock.

`:namespaces` injection: `opts/init-env!` merges option namespaces
over the defaults (`src/sci/impl/opts.cljc:17-63`, merge at 21-27),
and `sci/add-namespace!` mutates a live env
(`src/sci/core.cljc:651-657`). An injected namespace satisfies require
via the `load.cljc:180` cache check — eager, no `:load-fn`.

Symbol resolution (analysis time) is
`sci.impl.resolve/lookup*` (`src/sci/impl/resolve.cljc:39-170`):
current-ns `:aliases` (`resolve.cljc:50-52`), global `:ns-aliases`
(`:52`), the target namespace map (`:70-80`), `:refers`
(`:137-139`), current-ns vars (`:140`), `clojure.core` fallback
(`:145-152`), then classes/imports. The analyzer enters it at
`sci.impl.analyzer/analyze` symbol case (`analyzer.cljc:2276`) and
call position `analyze-call` (`analyzer.cljc:1885-1898`,
`resolve/resolve-symbol ctx f* true` at `:1898`).

**Probed** (`seam_probe.clj` run output): a `:load-fn` returning
generated stub source (each public fn a wrapper closing the remote
call as pure data) provisioned `seon.db` on first require, load-count
1 after two requires (cached), wrapper calls reached the host fn and
returned data values.

## 2. Var-resolution intercept (the one real gap)

There is **no unresolved-symbol hook**. The failure is the throw in
`resolve-symbol` (`resolve.cljc:322-332`): after `resolve-symbol*` and
the cljs dotted-access fallback return nil, it throws "Unable to
resolve symbol" with `{:phase "analysis"}` location
(`resolve.cljc:11-12`). Probed: both `(undefined-fn 1)` and — the case
that matters for derived placement — a **qualified call into a
never-required namespace** (`(seon.db2/query 1)`) throw here without
ever consulting `:load-fn` (require is the only load trigger).

Fork addition: a `:resolve-fn` ctx option consulted at exactly that
throw site —

```clojure
(or (resolve-symbol* ctx sym call? m)
    #?(:cljs (resolve-dotted-access ctx sym call? m))
    (when-let [rf (:resolve-fn ctx)]
      (rf {:ctx ctx :sym sym :call? call?}))   ;; -> [sym v] or nil
    (throw-error-with-location ...))
```

The hook can consult the require-edge/placement registry, provision
the namespace (env swap, same as the load-fn body), and return the
resolved pair — auto-provisioning plus "did you mean" steering (a nil
return falls through to the existing error; a richer error can be
thrown from the hook). Patch surface: ~8 lines in
`resolve.cljc:322-332`, ~4 lines passing `:resolve-fn` through
`opts/init` and `opts/merge-opts` (`opts.cljc:236-273, 275-310`; on
the JVM `Ctx` is a record, `opts.cljc:207-217` — a once-per-init assoc
into the extmap is fine), plus tests. **~25 lines total.**
Upstreamability: plausible — the API is already hook-rich
(`:load-fn`, `:async-load-fn`, `:interrupt-fn`, `:reify-fn`,
`:resolve` exists as a query at `core.cljc:684-685`), and all recent
JIT/interrupt work landed upstream; propose as a PR before carrying a
patch.

## 3. JIT interaction

The analyzer attaches a delayed `jit/compile-template` per fn body
(`analyzer.cljc:549-551`); call sites emit
`[:call-direct|:call-var|:call-bind|:call-node ...]` specs
(`src/sci/impl/jit.cljs:129-133`). A call through a var is
`:call-var` with a per-site deref cache invalidated by the global
var-mutation epoch (`jit.cljs:404-413`; epoch bump on var mutation,
`src/sci/impl/vars.cljc:48-56, 131, 135`) — so **re-provisioning or
upgrading a wrapper var safely invalidates every compiled call site**.
Compiled templates retain the `:interrupt-fn` check
(`if(INT!==null)INT()` observed in the emitted source).

**Probed** (`probe/seam.cljs` under the vendored bun): JIT enabled,
lazy `:load-fn` provisioning inside sci, a sci `hot` fn calling the
stub 1000× correct, and the compiled template shows the epoch-cached
`:call-var` deref + `t0.call(null,t1)` — remote-call wrappers JIT as
ordinary vars.

Two seam constraints:

- **Varargs bodies never compile**: `compile-template`/`make-fn-body`
  fall back to the interpreter when `:vararg-idx` is set
  (`jit.cljs:724-744`, fallback at `:729`, guard at `:741`). Generated
  stub *source* written as `(defn f [& args] ...)` stays interpreted.
  The hybrid seam below sidesteps this entirely (wrapper values are
  host fns — native, nothing to compile); if source stubs are ever
  used, emit the real fixed arities from the analyzer's arglists.
- The JIT is CLJS-only (`jit.cljs`); on the Variant-C JVM host sci is
  the interpreter — no JIT consideration there.

## 4. Context lifecycle (park/restore)

- **Fork**: `sci/fork` (`core.cljc:318-323`) =
  `(update ctx :env (fn [env] (atom @env)))` — a new atom over the
  same persistent env value. Structural sharing is why C1 measured
  22.7 KB (idle 18.6 KB / 117.9 KB working-set) marginal per context.
  Probed: defs in a fork are invisible to sibling forks and the base.
- **What a ctx is** (probed inventory): ctx keys `:env :bindings
  :features :readers :check-permissions :interrupt-fn :unrestricted
  :allow :deny :reify-fn :proxy-fn :deftype-fn :main-thread-id` plus
  analysis-transient fields (`:recur-target :params :parents
  :closure-bindings :fn-expr`, nil between evals — `opts.cljc:203-217`
  note). Env keys: `:namespaces :imports :class->opts :raw-classes
  :public-class :load-fn :ns-aliases` (+ `:js-libs`/`:async-load-fn`
  on CLJS).
- **Serialize vs rebuild**: env `:namespaces` values are sci vars
  holding host objects, closures, and analyzed fn node trees — not
  meaningfully serializable. Park = drop the context. Restore =
  `sci/fork` of the shared base + replay the agent's def source from
  the one program-graph corpus (roadmap blocker 6's sync contract
  makes that source the durable record anyway); B1 measured a
  200-form replay at 37-43 ms through the full envelope path. A
  pure-data-var snapshot (walk `:namespaces`, keep values that are
  plain data) is a possible warm-restore optimization, never the
  authority.
- **Interrupt/deadline is per-context state**: `:interrupt-fn` is a
  ctx record field set at `init`/`merge-opts`
  (`opts.cljc:219-227, 300`), checked on every interpreted fn/loop
  entry and inside every compiled JIT template; per-agent deadline =
  an interrupt-fn closing over that agent's deadline. Uncatchable
  delivery via `sci.interrupt/interrupt!`
  (`src/sci/interrupt.cljc:32-42`, marker-checked by sci's `try`);
  host-sequence coverage by merging `interrupt/clojure-core`
  (`interrupt.cljc:289-306`). C1 already proved thread-per-eval +
  `Thread/interrupt` on top of this.

## 5. Dynamic binding-table extension of live contexts

- **One context**: `sci/merge-opts` mutates the ctx's own env atom in
  place (`opts.cljc:275-310`, `init-env!` swap at `:299`);
  `add-namespace!`/`add-class!`/`add-import!`/`add-js-lib!`
  (`core.cljc:628-692`) are the narrow versions. Probed: `merge-opts`
  on a live fork provisioned a new namespace for that fork only.
- **All contexts at once**: direct injection into the base env does
  NOT reach existing forks (separate atoms — probed), but the
  `:load-fn` **closure is shared** by every fork (the env value is
  copied, the fn inside it is the same object). A registry-backed
  load-fn therefore propagates lazily: add the capability namespace to
  the registry once and every live context can `require` it from then
  on — probed (`my.net` added after two forks existed; both required
  and called it). This is the provisioning mechanism: the registry is
  a derived view of database facts (require-edges + host placement),
  no per-context mutation, no rebuild.
- **Upgrading an already-required namespace** in a live context:
  mutate its vars (epoch bump invalidates JIT caches, §3) or
  `require` with `:reload` (`load.cljc:177-180` bypasses the cache
  and re-enters the load-fn).

## Recommended seam

**Registry-backed `:load-fn` that injects a wrapper-var namespace map
directly and returns `{}` — no generated source.** Probed end-to-end
in `seam_probe2.clj`: the load-fn body does
`(swap! (:env ctx) assoc-in [:namespaces lib] wrapper-var-map)` and
returns `{}`; sci then performs the `:as`/`:refer` wiring itself
(`load.cljc:228-234`), metadata (`:arglists`, `:doc`) is live on the
vars for prose/steering, laziness and caching are sci's own, and the
wrappers are host fns calling the one remote boundary with pure data.

Why over the alternatives:

- vs **generated stub source**: no parse/analyze cost, no interpreted
  variadic wrapper layer (§3), nothing to keep textually in sync —
  the wrapper map is derived directly from the placement registry.
  Generated source remains the fallback only if a capability ever
  needs a real sci-side body.
- vs **eager `:namespaces` injection at init**: eager injection
  provisions every context with every capability up front and cannot
  reach already-forked contexts when a capability appears later; the
  load-fn registry gives first-require provisioning and live
  propagation with the same code.
- vs **analyzer hook only**: requires are the normal path and need no
  fork at all; the hook is additive steering.

## Fork punch list

| Gap | Patch | Size | Upstream? |
|---|---|---|---|
| `:resolve-fn` unresolved-symbol hook (qualified-sym auto-provision + did-you-mean) | `resolve.cljc:322-332` + `opts.cljc` passthrough + tests | ~25 lines | Plausible PR; propose before forking |
| Require-event hook | none — `:load-fn` already is that event | 0 | — |
| Per-env load lock (only if the global `load.cljc:264-267` lock ever measures as contention at N=100) | move lock into env | ~5 lines | Plausible |

Everything else the design needs — lazy require-driven provisioning,
live binding-table extension, cheap forked contexts, per-context
interrupt/deadline, JIT-safe wrapper calls — is stock upstream sci at
`be4021d`. **The seam requires zero fork**; the only candidate patch
is the steering hook, and it is PR-sized.
