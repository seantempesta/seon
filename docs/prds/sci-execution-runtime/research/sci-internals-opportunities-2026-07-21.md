---
type: research
status: active
tags: [research, agent, architecture]
---

# Sci internals: unused capabilities mapped to the W/U program

Read of the pinned checkout `reference-code/sci` (HEAD `be4021d`,
contains JIT `45bcf0f`) against the planned units in
[[../program-synthesis-2026-07-21]] and [[../roadmap]]. Each finding
names file:line evidence, whether it is public API or needs a patch, and
the consuming unit. Ends with the fork-vs-mirror recommendation.

## 1. U6 instrumentation — watches are public; no invoke hook needed

**What sci already tracks.** A `sci.lang.Var` carries mutable fields
`root`, `meta`, `thread-bound`, `watches` (`src/sci/lang.cljc:71-90`).
On the JVM it implements `clojure.lang.IRef` — **`add-watch` /
`remove-watch` on sci vars is plain public Clojure API**
(`lang.cljc:194-202`). `bindRoot` calls `notify-watches` with the old
and new root (`lang.cljc:97-103`, watcher fn `lang.cljc:61-69`), so a
watch fires on every `def`-with-init and `alter-var-root`. Caveats:

- `unbind` mutates root directly without notifying
  (`lang.cljc:119-121`) — a bare `(def x)` re-declaration is invisible
  to watches; irrelevant for our defn-shaped corpus.
- `add-watch` runs inside `with-writeable-var`
  (`src/sci/impl/vars.cljc:282-293`): watching a `:sci/built-in`-stamped
  var requires the privileged path (`sci.ctx-store/with-ctx` with
  `:unrestricted`, exactly what `sci.core/alter-var-root` does at
  `core.cljc:249-257`).
- The "var-epoch" language in the U2/U3 roadmap rows is **CLJS-only**:
  `var-epoch` exists solely for JIT deref caches
  (`src/sci/impl/vars.cljc:47-56`, `#?(:cljs ...)`). On the JVM there is
  no epoch — live swap works because every call node derefs the var per
  invocation (`lang.cljc:213-265`). Worth one doc-drift fix in the
  roadmap wording (W8).

**Invocation data.** Sci keeps none. The only per-invocation hook is
`:interrupt-fn`, called with zero args on every interpreted fn entry and
loop recur (`src/sci/impl/fns.cljc:24-81`, the
`(when-not (nil? interrupt-fn#) (interrupt-fn#))` in every generated
arity) — identity-less, so unusable for call capture.

**U6 design consequence (no patch).** Malli instrumentation stays a
wrapper installed with the privileged `sci/alter-var-root` — wrapping is
unavoidable anyway because schema checking needs the args. What the
internals buy us is the *re-instrumentation trigger*: a watch on each
recorded corpus var re-wraps on agent redefinition instead of the pod's
derive-and-reapply sweep. The watch must guard against its own
`bindRoot` (re-entrancy: wrap inside the watch triggers the watch);
guard with a thread-local or a `::instrumented` marker compared on the
new root. Registry vars are already the shared objects
(`src/seon/host/context.clj:432-473`), so one watch instruments every
context at once.

**Minimal `:invoke-fn` patch, if ever needed.** The natural seam is
`gen-fn` (`fns.cljc:31-81`) plus the `fun` builder (`fns.cljc:88-171`),
which already has `fn-name` and `nsm` in scope: add
`invoke-fn# (:invoke-fn ~'ctx)` beside `interrupt-fn#` and
`(when-not (nil? invoke-fn#) (invoke-fn# ~'fn-name ~'nsm))` at fn entry
(~6 lines, one ctx key). Same zero-cost-when-nil shape as
`:interrupt-fn`, so an upstream PR is plausible (the precedent option is
documented in `eval-string`'s docstring, `core.cljc:292`). Not needed
for U6; recorded here as the fallback if wrapper overhead ever measures
too high.

## 2. Graduation heat — the JIT keeps no counters; derive from receipts

The JIT (`src/sci/impl/jit.cljs`) is **CLJS-only** (a `.cljs` file; ns
docstring `jit.cljs:1-13`) and is *lazy*, not *hot-counting*: a
per-arity stub compiles the JS template at **first** invocation
(`make-stub` `jit.cljs:696-720`, `make-fn` `jit.cljs:722-734`). There is
no invocation count, per-var heat, or tier record anywhere. The only
counter-shaped state is the global `var-epoch` cell
(`vars.cljc:47-56`) and per-call-site deref caches
(`jit.cljs:404-412`) — cache-invalidation machinery, not usage data. The
JVM host tier (variant C, the chosen direction) never touches jit.cljs
at all; it runs the interpreter closures from `fns.cljc`.

**Consequence for U3/U12 candidate selection.** Sci offers nothing to
read; do not patch counters into the fn-entry hot path. The
data-oriented source we already own is better: every host-tier form
commits a `:seon.eval` receipt before running (U4), and every recorded
corpus fn has `:seon.fn` rows — heat is a *derived query* over receipts
and, once U6 lands, the instrumentation wrapper can cheaply bump an
in-memory counter for intra-turn call frequency (same wrapper, one
mechanism). Graduation policy reads facts, not interpreter internals.

## 3. Repair/preflight prose — analysis is separable; ex-data nearly rich enough

**What the analyzer knows at failure time.** `lookup*`
(`src/sci/impl/resolve.cljc:39-170`) resolves against the full env: the
current ns map (interned vars), `:refers`, `:aliases`, global
`:ns-aliases`, `clojure.core`, `:types`, classes. On failure,
`resolve-symbol` throws `ex-info` with message
`"Unable to resolve symbol: <sym>"` and data
`{:type :sci/error :line :column :file :phase "analysis"}`
(`resolve.cljc:11-12` + `resolve.cljc:322-332`,
`utils.cljc:62-70`). Everything except the prose — the symbol, the
candidate set, the aliases in scope — is discarded from the throw, but
**not lost**: it remains queryable from the ctx afterward.

**Analysis-only entry point exists.** `eval-form*`
(`src/sci/impl/interpreter.cljc:29-62`) shows the exact recipe: under
`store/with-ctx`, assoc `:parents`/`:closure-bindings`, call
`(ana/analyze ctx form)` — and simply *don't* call `types/eval` on the
result. Because sci is pinned `:local/root`, calling
`sci.impl.analyzer/analyze` directly is available today with no patch
(impl-namespace stability caveat: re-verify on version bumps). Two
side-effect caveats make the preflight ctx a **disposable `sci/fork`**:
`analyze-def` interns an unbound var at analysis time (`init-var!`,
`analyzer.cljc:765-797`) and `analyze-ns-form` switches `*ns*`
(`set-namespace!` at `analyzer.cljc:1429`); require clauses however only
run at eval (they analyze to nodes, `return-ns-op`
`analyzer.cljc:1400-1407`). Fork is one `(atom @env)` copy
(`core.cljc:318-323`) — cheap.

**Candidate synthesis without a patch.** On catching the analysis
error, the adapter queries the same ctx with public API:
`sci/resolve` (`core.cljc:684`), `sci.impl.namespaces/sci-ns-publics*`
and `sci-ns-interns*` (`namespaces.cljc:503-524`), `sci/all-ns`
(`core.cljc:663-667`), plus Seon's own wrapper registry — and ranks with
the existing `seon` repair ranking. The only regex left is extracting
the symbol from the message.

**Minimal patch (recommended first fork change if we make one).** Add
the symbol (and optionally the alias map) to the thrown data:
`resolve.cljc:330-332` currently
`(throw-error-with-location (str "Unable to resolve symbol: " sym) sym)`
via the local wrapper at `resolve.cljc:11-12` which passes
`{:phase "analysis"}` — extend to
`{:phase "analysis" :sci.impl/symbol sym}` (2 lines). Pure-additive
ex-data; high upstream acceptance odds. Consumers: the B1 adapter items
(error-prose synthesis, sci resolution queries for prose/preflight) and
W3 repair sub-loop parity.

**Bonus prose sources already present.**

- `sci/stacktrace` + `format-stacktrace` (`core.cljc:402-410`) read the
  `:sci.impl/callstack` chain of `StackFrame` records
  (`utils.cljc:296-305`) carrying line/column/ns/file and `f-meta` — the
  *called function's metadata* at the failure site. This is exactly the
  warning→catch-site classification input; no message parsing.
- `rewrite-ex-msg` (`utils.cljc:88-119`) already rewrites arity-error
  prose to name the real var — error-prose synthesis partially ships in
  sci itself.
- **Typed interrupt classification (kills a W-list WEAK item):**
  interrupts are identified structurally, never by message —
  `(identical? utils/interrupt-marker (:sci.impl/interrupt (ex-data e)))`
  (`utils.cljc:47-56`), and `sci.interrupt/interrupt!` accepts a data
  map (`interrupt.cljc:32-42`), so Seon's `:interrupt-fn` can throw
  `(interrupt/interrupt! "eval deadline exceeded" {:seon.error/kind :timeout})`
  and the catch site classifies on ex-data keys end-to-end. The
  regex-classified-interrupts weakness needs zero sci changes.

## 4. eval-def copy-on-write — assessed and rejected

The refusal-with-steering design
([[probe-shared-var-protection-2026-07-21]]) holds. The COW patch was
assessed concretely:

**Sketch.** In `eval-def`'s `assoc-in-env`
(`src/sci/impl/evaluator.cljc:25-47`), after computing `prev`: when
`(:sci/built-in (meta prev))` and the ctx is not `:unrestricted` and a
new opt-in ctx option (say `:copy-built-in-on-def`) is set, replace
`prev` with a fresh `lang/->Var` (meta copied minus `:sci/built-in`)
before `bindRoot`/`reset-meta!*`, so the def shadows locally in this
fork's env instead of mutating (or refusing on) the shared object.
~8 lines in one function.

**Why it is worse than it looks.**

1. **It never fires for the common case.** Toolkit names reach agent
   code as *refers*, and refers win resolution over ns-map vars
   (`resolve.cljc:136-139`); `analyze-def` refuses a def over a referred
   name *at analysis time* with "already refers to"
   (`init-var!`, `analyzer.cljc:771-779`) — before eval-def ever runs.
   A real COW would need a second change in `init-var!`, and the refer
   would still shadow the new var for reads. Two patched sites, not one.
2. **Within-context incoherence.** Analyzed call nodes close over the
   Var *object* and deref per call; every form evaluated before the
   shadow keeps calling the shared implementation while new forms see
   the override. "Last version wins" would hold only for
   not-yet-analyzed code — a semantic trap for agents.
3. Upstream-ability is mediocre: it changes def semantics rather than
   adding an observation hook, and babashka's sandbox story is built on
   the read-only refusal.

**The clean route already works**: probe step 9 proved last-version-wins
for agent-owned names; an agent wanting a local variant of a toolkit fn
defines it in its own ns (steering prose in W0.2 should say exactly
that). Do not carry this patch.

## 5. ns re-declaration — sci is already merge-semantics; no hook needed

The premise that we must pre-parse ns forms to avoid dropped requires is
false on the sci tier. Evidence chain:

- `analyze-ns-form` (`analyzer.cljc:1409-1458`) calls `set-namespace!`,
  which **merges** the attr-map into existing ns meta and reuses the
  existing namespace object (`utils.cljc:243-249`,
  `namespace-object` `utils.cljc:208-219`); it never clears the ns map.
- Each `:require`/`:use`/`:import` clause becomes a node over
  `load/eval-require`; `handle-require-libspec-env`
  (`src/sci/impl/load.cljc:95-147`) `assoc`s aliases and refers *into*
  the existing ns entry. `:refer-clojure :exclude` accumulates with
  `fnil into` (`load.cljc:372-377`).

So `(ns a (:require [x :as x1]))` followed by
`(ns a (:require [y :as y1]))` leaves **both** aliases live — sci's own
machinery already implements the require-merge rule. The
`augment-ns-source` seam survives only for its other two jobs: injecting
the standard capability aliases into a bare agent ns (the synthetic ns
form `seon.host.context.clj:795-805` already does this host-side) and
recording merged `:seon.ns/require-edges` durably
(`seon.host.record`'s tools.reader pass, unchanged). W4's teaching rule
("re-declaration merges requires") describes sci's actual behavior —
teach it, don't build it. Suggested falsifier for the W3/W5 gate: eval
the two ns forms above in one context and assert both aliases resolve.

## 6. Full ctx opts surface and unused-but-relevant capabilities

From `opts/init` (`src/sci/impl/opts.cljc:236-273`) and `merge-opts`
(`opts.cljc:275-310`): `:env`, `:bindings` (deprecated), `:allow`,
`:deny`, `:aliases`, `:namespaces`, `:classes`, `:imports`, `:features`,
`:load-fn`, `:readers`, `:reify-fn`, `:proxy-fn`, `:deftype-fn`,
`:interrupt-fn`, `:unrestricted`, `:ns-aliases`; CLJS-only
`:async-load-fn`, `:js-libs`, plus the `sci.core/disable-jit`
goog-define (`core.cljc:34-39`). Currently used by Seon: `:load-fn`,
`:namespaces`, `:interrupt-fn` (`src/seon/host/context.clj:926-933`).
Unused and relevant:

- **`:features`** — reader-conditional feature set threaded into the
  parser (`parser.cljc:146-152`). Directly serves the W5 "stored source
  is canonical CLJC" addendum: the host evals `.cljc` corpus with
  default `:clj`, and a `:seon` feature key is available if we ever need
  Seon-specific branches. No second parser.
- **`:readers`** — data readers at the eval boundary. The
  `seon/handle` tagged type (design addendum) can be readable inside
  agent code with one entry here, symmetrical with the transit tagged
  type on the wire.
- **`:ns-aliases`** — global namespace aliasing (e.g.
  `'cljs.core 'clojure.core` is the built-in default,
  `opts.cljc:229-234`). A migration aid for replaying pod-authored
  sources on the host tier (U9/W5).
- **sci print vars for W0.6 print floods** — `sci/print-length`,
  `print-level`, `out`/`err` are sci dynamic vars (`core.cljc:160-171`,
  `io.cljc:61`); `sci/with-bindings` around the eval bounds printed
  output with public API. On the JVM tier this replaces the pod's
  ALS-bridge problem entirely: bind `sci/out` to a capped writer feeding
  the existing `::output` seam.
- **`parse-next+string` + `source-reader`** (`core.cljc:358-391`) —
  verbatim per-form source text from the one parse. `seon.host.record`
  currently re-reads with tools.reader; the recording batch could take
  form+string from a single parse pass. (tools.reader stays the corpus
  graph owner; this only de-duplicates the *host eval* path's read.)
- **`copy-var*`** (`core.cljc:111-136`) — runtime copy of a real
  Clojure var into a sci var with `:doc`/`:arglists`/`:dynamic` carried
  over. W6/U13 wrapper generation for the JVM package host gets real
  docs/arglists for free instead of hand-built meta.
- **`add-class!` / `add-import!`** (`core.cljc:628-649`) — the
  supported way to grant classes to a binding table; the disposable JVM
  package host (W6) should use these rather than env surgery.
- **`sci/intern`, `ns-unmap`, `remove-ns`**
  (`core.cljc:259-270`, `namespaces.cljc:567,604,610`) — surgical
  context repair (delete one bad def without a context rebuild); useful
  for W10 tooling and U7 park/restore hygiene.
- **`:allow`/`:deny`** (`resolve.cljc:23-37`) — analysis-time symbol
  allowlisting. Note it only constrains built-in vars (non-built-in vars
  pass automatically, `resolve.cljc:29-31`). We already own containment
  via binding tables + stamping; recording that this third dial exists
  and is deliberately unused prevents someone reaching for it as a
  parallel mechanism.
- **Var `alter-meta!` is public** (JVM `IReference`,
  `lang.cljc:174-180`, built-in-guarded) — a live metadata channel on
  shared vars. Derive-don't-store says corpus facts stay in the
  database; noted only because U6's re-instrumentation guard can ride
  var meta.
- **`:realize-max` is NOT a sci option** — it is edamame parse
  machinery and sci's parser does not pass it
  (`parser.cljc:45-52`). W0 output bounding stays interrupt-fn + capped
  capture; nothing hidden here.
- Vestigial: the Var `needs-ctx` field (`lang.cljc:84-86`) has no
  readers at HEAD — do not build on it.
- Confirmed probe caveat: `sci.interrupt` overrides read
  `:interrupt-fn` via `sci.ctx-store/get-ctx` at call time
  (`interrupt.cljc:25-30`), so escaped lazy values must be forced under
  `ctx-store/with-ctx` — already a W0.1 line item.

## Ranked recommendation: fork vs mirror

1. **Stay an unmodified mirror now (W9 ships the pushed mirror as-is).**
   Every consuming unit is served by public API plus direct calls into
   impl namespaces of the pinned checkout: U6 = watches + privileged
   alter-var-root wrapping; graduation heat = derived from `:seon.eval`
   receipts (sci has no counters to read); preflight = fork +
   `sci.impl.analyzer/analyze` without eval; prose = ctx queries +
   `sci/stacktrace` + structural interrupt ex-data; ns-merge = sci's
   existing semantics; print caps = sci print vars.
2. **First patch worth a true fork commit, when B1 adapter work starts
   (W3):** structured resolution-failure ex-data
   (`:sci.impl/symbol` in the analysis throw, `resolve.cljc:330-332`,
   ~2 lines). Kills the last message regex. Submit upstream
   simultaneously; carrying it locally is near-zero rebase risk.
3. **Second candidate, only on measured need:** the `:invoke-fn`
   fn-entry hook in `fns.cljc` mirroring `:interrupt-fn` (~6 lines,
   upstreamable by precedent) — falls out only if wrapper-based call
   capture proves too costly, which C1's numbers make unlikely.
4. **Do not carry the eval-def COW patch** (finding 4): it requires two
   patched sites, silently misses referred names, and creates
   within-context version incoherence. Refusal-with-steering plus
   own-ns last-version-wins is the correct shipped design.
