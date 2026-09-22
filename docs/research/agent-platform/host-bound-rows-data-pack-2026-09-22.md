---
type: reference
created: 2026-09-22
subject: which declarations SCI cannot interpret, per function, and the one computed rule
head: 252e6e5bd
sci-rev: fcbd8862
---

# Host-bound rows — data pack

HEAD `252e6e5bd` (`refactor/agent-platform`); `reference-code/sci` at `fcbd8862`.
Probes: `mcp__seon__eval_clj`, mode `jvm`, `read_only true`, root `/Users/sean/src/seon`,
cluster `default`, custody `(seon.cluster.boot/connection "default")`. No writes, no JVM
launched, no test run. Every line cited below was read at this HEAD.

## Summary — ten lines

1. **Yes, we know ahead of time, and it is a closed set of form heads — no whitelist.**
   SCI refuses exactly four constructs outright: `reify`, `proxy`, `definterface`,
   and any `deftype`/`defrecord` implementing a **host interface**.
2. `deftype`, `defrecord`, `defprotocol`, `extend-type`/`extend-protocol` (even on a host
   class), `defmacro` and `defmulti` **all interpret fine** — measured, §1 table.
3. The real cutting line is not the type-defining forms at all: it is **host class
   resolution**. Seon's SCI admits four classes (`eval.clj:244-248`), so `(java.util.Date.)`
   and `(System/currentTimeMillis)` refuse in *any* interpreted row.
4. `deftype`/`defrecord` that interpret still produce a **SciType, not the host class** —
   silently divergent for compiled `instance?` checks (`env.clj:47`). That is a
   host-bound row even though SCI accepts it.
5. The analyzer already stores the form head per declaration:
   **`:seon.fn/defined-by`** (`seon.fn.edn:24-35`, written `fn.clj:674-675`, indexed).
   A Datalog clause answers "this row's form head is host-defining" today.
6. It does **not** store the body facts: class literals, `reify`/`proxy` and constructor
   interop land in a **file-level** `:seon.fn/unresolved-references`, hoisted off the row
   at `fn.clj:1329`. Per-declaration granularity is computed and then discarded.
7. Population today (probe, §4): **21** rows carry a host-defining form head;
   **21** rows contain `reify`/`proxy` in their stored source; **70** construct a class
   literal; **257** call a static host method. Total rows with source: **4,462**.
8. `1,309` tests reach the 17 first-party type rows; `1,665` reach those plus the
   reify/proxy rows (`seon.fn/gate-sets`, 821 ms cold / 49 ms warm).
9. **The rule:** `host-bound?` = form head ∈ the §1 refused set, **or** the row's own body
   carries an unresolvable host reference. Derived by the **indexer** from analysis facts
   it already has, stored as one declared boolean; never a namespace roster.
10. A plain caller of a host-bound row is **not** host-bound — it calls the compiled Var
    through `copy-var*` (`sci/core.cljc:112`). An *interpreted* caller whose own body names
    the host class **is** — `analyzer.cljc:1353`.

---

## 1. What SCI cannot interpret, from SCI's own source

Probe (read-only, a throwaway `sci/init` with **Seon's exact `:classes` map**, `eval.clj:244-248`):

```clojure
(let [ctx (sci.core/init {:classes {'Throwable Throwable 'java.lang.Throwable Throwable
                                    'Error Error 'java.lang.Error Error}})
      try! (fn [s] (try (str (sci.core/eval-string* ctx s))
                        (catch Throwable t (str "REFUSED: " (.getMessage t)))))] …)
```

| Form | Result | SCI `file:line` |
|---|---|---|
| `(defmacro m [x] …)` | **`2`** — supported | `namespaces.cljc:1815` (`defmacro` macrofied) |
| `(defprotocol P (m [_]))` | supported | `impl/protocols.cljc:40`; registered `namespaces.cljc:1624` |
| `(deftype T [] P (m [_] 42))` | **`42`** — supported, protocol-only | `impl/deftype.cljc:314-372`; registered `namespaces.cljc:1820` |
| `(defrecord R [a])` | **`1`** — supported | `impl/records.cljc`; registered `namespaces.cljc:1819` |
| `(deftype T2 [] java.lang.Runnable (run [_] 1))` | **REFUSED**: `Protocol not found: java.lang.Runnable` | `impl/deftype.cljc:322-325`; the host-interface assertion behind it `deftype.cljc:15-21` ("defrecord/deftype currently only support protocol implementations") |
| `(extend-type java.lang.String Q (n [_] :s))` | **`:s`** — supported, host class included | `impl/protocols.cljc:287`; `extend-protocol` `:257`; registered `namespaces.cljc:1628-1629` |
| `(reify java.lang.Runnable (run [_] 1))` | **REFUSED**: `Unable to resolve symbol: java.lang.Runnable` | class gate first; then `impl/reify.cljc:40` `"No reify factory for:"` — Seon supplies **no `:reify-fn`** (grep over `src/`, `script/`: zero hits); unsupported-interface path `reify.cljc:38` |
| `(proxy [java.io.FilterInputStream] [nil])` | **REFUSED**: `Unable to resolve symbol: java.io.FilterInputStream` | then `impl/proxy.clj:33` `"no proxy-fn"` — Seon supplies **no `:proxy-fn`** |
| `(definterface I (foo []))` | **REFUSED**: `Unable to resolve symbol: definterface` | not registered anywhere in `namespaces.cljc` (SCI's only `definterface` is its own `lang.cljc:10`) |
| `(ns foo (:gen-class))` | **`""`** — silently **ignored**, no class generated | `impl/analyzer.cljc:1494` `:gen-class ;; ignore`; the generating branch is commented out at `namespaces.cljc:1407-1418` |
| `(java.util.Date.)` | **REFUSED**: `Unable to resolve classname: java.util.Date` | `impl/analyzer.cljc:1353` (`analyze-new`); constructor-position variant `:1421` |
| `(java.lang.System/currentTimeMillis)` | **REFUSED**: `Unable to resolve symbol: …` | class resolution `impl/interop.cljc:166` `resolve-class-opts`, `:185` `resolve-class` |
| `(import 'java.util.Date)` | **REFUSED**: `Unable to resolve classname: java.util.Date` | `impl/evaluator.cljc:373-374`; analyzer entry `analyzer.cljc:1826` |
| `(set! some-var v)` | supported for **vars**; host field set is CLJS-only | `impl/analyzer.cljc:1514-1540` |
| `definline` | absent from SCI entirely | no hit in `reference-code/sci/src` |

**What Seon's config permits.** `build-base-ctx` (`src/seon/sci/eval.clj:185`) passes
`:classes` with exactly four entries — `Throwable`, `java.lang.Throwable`, `Error`,
`java.lang.Error` (`eval.clj:244-248`). SCI merges its own `default-classes`
(`impl/opts.cljc:100-118`: `String`, `Object`, `Integer`, `Number`, `Double`,
`StringWriter`, `ExceptionInfo`, `LazySeq`, `Delay`, `sci.lang.*`, …) at `opts.cljc:315`.
Nothing else. `seon.sci.kernel/context-options` (`kernel.clj:98-106`) adds only the guard,
`:interrupt-fn`, `:host-interop-observer` and `:built-in-call-observer` — **no class roster,
no `:reify-fn`, no `:proxy-fn`, no `:deftype`/`:defrecord` flags** (SCI has no such flags;
both are unconditional macros).

**Verdict:** the refused set is closed and knowable without a whitelist —
`{reify, proxy, definterface, gen-class (silently), deftype/defrecord over a host
interface, any unadmitted class literal or static call, any import}`. Everything else,
including `defprotocol`, `deftype` over protocols, `extend-type` on a host class and
macros, interprets.

**The sixth case, which no refusal reports.** An interpreted `deftype`/`defrecord`
creates a SciType (`impl/deftype.cljc:314`, `namespaces.cljc:985-995`), **not** the host
class. `seon.env/environment?` is `(instance? (class empty-environment) value)`
(`src/seon/env.clj:47`); a context that reinterprets `seon.env` therefore builds values
that every compiled caller rejects, with no error at install. This is why the type-defining
form heads are host-bound even though SCI accepts them.

---

## 2. What the analyzer already records per declaration

| Fact | `file:line` | Answers |
|---|---|---|
| `:seon.fn/defined-by` — the interning form, verbatim from clj-kondo (`clojure.core/defn`, `…/deftype`, `…/defprotocol`, a first-party def-ing macro) | schema `resources/seon/schemas/seon.fn.edn:24-35`; written `src/seon/fn.clj:674-675`; `:seon.db/index true` | **"this row's form head is host-defining" — yes, one clause, today** |
| `::analyzer/defined-by` / `:defined-by->lint-as` normalization | `src/seon/fn/analyzer.clj:126`, `:494`; `defmulti` consumer `fn.clj:327` | the producer |
| `:seon.fn/macro?` | `seon.fn.edn:20-23`; `fn.clj:668` | macro rows (SCI-supported) |
| `:seon.fn/references` / `:seon.fn/calls` | `fn.clj:685-692`; schema `seon.fn.edn:143`, `:146` | **var** edges only |
| `:seon.fn/source`, `:seon.fn/file`, `:seon.fn/form-span` | `fn.clj:660-664` | the exact stored text a context would interpret |
| `:seon.schema.admission/source` | on the **file** entity, value `:core`; install decision `src/seon/sci/eval.clj:883`, `:893-895` | today's only interpret/bind decision |
| `::analyzer/protocol-impls` — collected from clj-kondo | `src/seon/fn/analyzer.clj:32`, `:379`, `:426`, `:489-490` | attributed to the *enclosing var*, then folded into call edges; **not** kept as a row fact |
| `:seon.fn/unresolved-references` — the set containing `clojure.core/.`, `clojure.core/set!`, class names, unresolved constructors | assoc'd **per row** at `fn.clj:1193-1195`, hoisted to the file row at `fn.clj:1326-1328`, then **`dissoc`'d from every row at `fn.clj:1329`** | the missing fact — it exists for one line and is thrown away |

**Probe — declarations by form head on `default`:**

```clojure
(datahike.api/q '[:find ?d (count ?e) :where [?e :seon.fn/sym _] [?e :seon.fn/defined-by ?d]] db)
```

| form head | rows |
|---|---|
| `clojure.core/defn-` | 3,127 |
| `clojure.core/defn` | 1,305 |
| `clojure.core/defrecord` | 8 |
| `clojure.core/deftype` | 7 |
| `clojure.core/defprotocol` | 6 |
| `clojure.core/defmacro` | 6 |
| `clojure.core/defmulti` | 2 |
| `clojure.core/def` | 1 |
| **total** | **4,462** |

**Verdict:** the form-head half of the rule is already a fact and already indexed. The
body half (host class / `reify` / `proxy`) is computed by the indexer and discarded one
line before it is stored. No new analysis pass is needed — only a different `dissoc`.

---

## 3. The computed rule

**One derived fact, declared on the function row:**

```
:seon.fn/host-bound? true
  ⇔ (:seon.fn/defined-by row) ∈ #{clojure.core/deftype
                                  clojure.core/defrecord
                                  clojure.core/defprotocol
                                  clojure.core/definterface}
  ∨ the row's OWN unresolved references include a host class, a constructor,
    `clojure.core/reify`, `clojure.core/proxy` or `clojure.core/import`
```

**Where it is computed: the indexer, not acquisition.** `seon.fn/index!` already holds
both inputs in the same reduce — `::analyzer/defined-by` at `fn.clj:674` and the per-row
unresolved set at `fn.clj:1193`. Computing it there costs one `contains?` and one set
intersection per row inside a pass that already runs; computing it at acquisition would
re-derive 4,462 rows' facts per context fork, against a `sci/fork` measured at
0.00123 ms — i.e. it would dominate the operation it guards. **Store it.** The attribute
is `[:= true]` with stored absence meaning not host-bound (no nil), matching the
`:seon.fn/macro?` precedent (`seon.fn.edn:20-23`).

**Closure semantics — three cases, each with the deciding SCI line:**

| Case | Host-bound? | Deciding line |
|---|---|---|
| Row `f` calls host-bound `g`, and `f` itself is **not** in `affected` → `f` binds the JVM Var | **No** | `sci/core.cljc:112` `copy-var*` copies the compiled root; the call never enters the analyzer |
| Row `f` is in `affected` (must be interpreted), body calls `(->Environment …)` — a **Var** | **No** | the callee's own JVM Var was copied in by `install-jvm-root!` (`src/seon/sci/eval.clj:818`); SCI resolves a Var, not a class |
| Row `f` is in `affected`, body names the **class** — `(Environment. …)`, `(reify …)`, `(proxy …)`, `(SomeClass/method …)` | **Yes** | `impl/analyzer.cljc:1353` (`analyze-new`), `:1421`; `impl/reify.cljc:40`; `impl/proxy.clj:33` |
| Row `f` **is** the `deftype`/`defrecord`/`defprotocol` and is overridden | **Yes**, even though SCI accepts it | `impl/deftype.cljc:314` builds a SciType; compiled `instance?` at `src/seon/env.clj:47` then fails silently |

So `host-bound?` needs **no transitive closure over call edges**. It is a per-row
predicate over that row's own form head and its own body references. The transitive part
already exists and is B2 §2a's `affected` (`seon.fn/gate-sets`, `src/seon/fn.clj:1506`);
the refusal is simply `affected ∩ host-bound? ≠ ∅`.

**Verdict:** one stored boolean per declaration, produced by the indexer from facts it
already computes, intersected with the existing `affected` set. No roster, no namespace
granularity, no new analysis.

---

## 4. The population today

Probe over all 4,462 rows carrying `:seon.fn/source` (regexes are **measurement**, not
production code):

| Signal | rows |
|---|---|
| host-defining form head (`deftype`/`defrecord`/`defprotocol`) | **21** |
| body contains `(reify` | 18 |
| body contains `(proxy` | 3 |
| body contains a class-literal constructor `(Class. ` | 70 |
| body contains a static host call `(Class/member` | 257 |
| body contains `(definterface` | 0 |
| body contains `(import` | 0 |
| body contains `(set! ` | 1 |

**The 21 host-defining rows** (`symbol`, form head, file, callers via
`:seon.fn/calls`∪`:seon.fn/references`):

| symbol | form | file | callers |
|---|---|---|---|
| `seon.cluster.agent/->CountedSlidingBuffer` | deftype | `src/seon/cluster/agent.clj` | 0 |
| `seon.env/->Environment` · `map->Environment` | defrecord | `src/seon/env.clj` | 0 · 0 |
| `seon.flow/->CountedDroppingBuffer` · `->RefusingBuffer` | deftype | `src/seon/flow.clj` | 0 · 0 |
| `seon.print/->HiccupSink` · `->TeeSink` · `->TextSink` | deftype | `src/seon/print.cljc` | 0 · 0 · 0 |
| `seon.print/-close` · `-fragment` · `-open` · `-token` | defprotocol | `src/seon/print.cljc` | 5 · 2 · 5 · 7 |
| `seon.render.hiccup/->Raw` · `map->Raw` | defrecord | `src/seon/render/hiccup.clj` | 1 · 0 |
| `seon.render.walk/->DatabaseSchemaIdentity` | deftype | `src/seon/render/walk.clj` | 0 |
| `seon.search/->IndexHandle` · `map->IndexHandle` | defrecord | `src/seon/search.clj` | 0 · 0 |
| `seon.test-support/acquire-base!` · `release-base!` | defprotocol | `test/seon/test_support.clj` | 1 · 1 |
| `seon.sci.admit-test/->IdentityOnlyRecord` · `map->…` | defrecord | `test/seon/sci/admit_test.clj` | 0 · 0 |

**Stale-index caveat:** `seon.search/*` rows are still present on `default` although the
search subsystem was deleted at `434c01f4c`. `default` has adopted no edit this session
(hook publication paused), so its program is older than HEAD.

**Zero callers is the fourth-case evidence, not an absence of use.** `seon.env`'s own
`map->Environment` appears in the **file's** `:seon.fn/unresolved-references`, not on any
row's `:seon.fn/references` — constructor interop is not a var edge. Any rule reading only
`:seon.fn/references` will under-count.

**Namespaces carrying `reify`/`proxy` bodies** (21 rows): `seon.cluster`, `seon.flow`,
`seon.fn.analyzer`, `seon.fn.signature`, `seon.plan`, `seon.schema`, `seon.test.runner`,
`seon.web.jvm`, plus tests (`my.examples-test`, `seon.ai-test`, `seon.cluster.agent-test`,
`seon.flow-test`, `seon.web.jvm-test`, `seon.test-support`).

**Test reach** (`seon.fn/gate-sets` map arity, `src/seon/fn.clj:1506`):

| seeds | tests selected | measured |
|---|---|---|
| 17 first-party type rows | **1,309** | 821 ms (cold) |
| those + 21 reify/proxy rows | **1,665** | 49 ms (warm) |

**Verdict:** "changes only through the files" is **21 declarations** under the strict form
head rule, **42** including `reify`/`proxy` bodies, out of 4,462 — under 1 %. Add the
class-literal and static-interop bodies and the honest upper bound is **~310 rows (7 %)**,
concentrated in `seon.print`, `seon.flow`, `seon.web.jvm`, `seon.test.runner` and
`seon.fn.*` — platform plumbing, exactly as §7's ruling assumed. But 1,665 tests reach
them, so the refusal must be precise or it swallows a third of the suite.

---

## 5. What changes for B2 §2a

| Seam | `file:line` | Change |
|---|---|---|
| `install-row!` — the one installer | `src/seon/sci/eval.clj:829`; admission read `:883`; the decision `if (= :core admission)` `:893-895`; interpret branch `:904` | Before choosing to interpret, read `:seon.fn/host-bound?` off the committed row. If the row is in `overridden` ∪ `affected` **and** host-bound, **refuse by name** instead of interpreting |
| `install-function-from-database!` | `src/seon/sci/eval.clj:709` | Never reached for a host-bound row |
| The silent `:jvm-fallback` | `src/seon/sci/eval.clj:909-912`, consumed `:1911`, `:1918` | Deleted: a host-bound row is refused up front, so there is no failure to swallow. This is the same deletion 1.3d commit (1) already schedules |
| `acquire-program!` | `src/seon/sci/eval.clj:1709` | Collects the refusals as typed acquisition results, the way it already collects `install-row!` outcomes |
| The `affected` closure | `src/seon/fn.clj:1506` `gate-sets` | Unchanged — intersect its result with the stored boolean |

**What the refusal returns.** A flat declared value on the acquisition result, naming the
operation, the host-bound declaration and why, with no general error predicate:

```clojure
{:seon.error/operation 'seon.sci.eval/install-row!
 :seon.error/layer     :seon.sci.eval/program
 :seon.fn/sym          'seon.env/->Environment
 :seon.fn/defined-by   'clojure.core/defrecord
 :seon.fn/host-bound?  true
 :seon.error/expected  :seon.sci.eval/interpretable-declaration
 :seon.error/message   "This context cannot interpret the override's affected closure: the declaration is defined by a form SCI cannot reproduce on the host. Change it through the files."}
```

README §7 (`docs/prds/agent-platform/plan/README.md:357`) rules exactly this: refuse the
override in that context, naming the host-bound caller; no namespace roster. The rule
above is the per-declaration form of it, and §4 says the roster it replaces would have
been 14 namespaces where the truth is 21–42 declarations.

**Verdict:** two reads and one refusal inside `install-row!`, plus one indexer-stored
boolean. Nothing else in B2 §2a moves.
