---
type: research
status: active
tags: [research, sci, database, datahike, agent]
---

# A durable sci env: the atom protocol, konserve, and where structural sharing actually lives

Research lane, 2026-08-01, on the owner's directive: "sci keeps its hot
state in an env ATOM. If it's in an atom then we can implement the atom
protocol and write it to Datahike or to konserve. I want immutable data
structures with structural sharing like both atoms and konserve support.
If we can extend the Datahike API to support this then that's probably the
best way forward. We need to detect what is a closure and for that we can
re-eval with the restored context." Plus: "maybe directly reading/writing
konserve doesn't need to be the Datahike api."

Every number below comes from a script committed under `tmp/durable-env/`
(`p1_env_anatomy.clj`, `p2_konserve_amplification.clj`,
`p3_psset_sharing.clj`, `p4_closures_and_restore.clj`,
`p5_durable_atom.clj`, `p6_datahike_sharing.clj`), each run in a fresh
`clojure -M:dev` JVM against its own store roots under
`tmp/durable-env/store-p*` — no cluster store was opened. Machine: this
laptop, JDK 26, `:dev` (`-Xmx512m`). Pins: sci `937d392`, konserve
`737697d`, datahike `9b3be9d`, persistent-sorted-set `e1a17bb`.

Builds on `research/sci-session-persistence-2026-08-01.md` (ctx anatomy),
`plan/stateless-resume-design-2026-08-01.md` (forms-are-truth, the session
image, pre-interning), and
`research/admission-caps-and-blob-fallback-2026-08-01.md` (the ~80× datom
amplification).

## 0. Recommendation, one page

**The atom-protocol idea is mechanically possible and semantically
fatal. Do not build it.** Sci accepts a custom `IAtom`/`IDeref` as its
`:env` and evaluates correctly on one (p5 §A — measured, not argued). But
three measurements kill it as a persistence hook:

1. **Redefining an existing name changes the env atom's value by
   NOTHING.** `eval-def` calls `vars/bindRoot` on the *existing Var
   object* and then `swap!`s the same object back into the same map
   (`reference-code/sci/src/sci/impl/evaluator.cljc:29-48`). Measured: the
   swap fires, and `(identical? old new)` on the env value is **true**
   (p1 §D, p5 §B/§E). The value moved into `sci.lang.Var`'s
   `^:volatile-mutable root` field
   (`reference-code/sci/src/sci/lang.cljc:71-90, 96-102`). A
   value-diffing durable atom would persist a name once and then never
   see it change again. **The env atom is a namespace TABLE, not a value
   store.**
2. **`@env` cannot be serialized at all.** Not the whole env, not
   `(:namespaces @env)`, not a single namespace's table — all three are
   refused by the store's fressian serializer, because they hold
   `sci.lang.Namespace` and `sci.lang.Var` objects. Only *dereferenced*
   values serialize (p5 §C).
3. **The cost is 81×.** 50 `def`s with a durable-persisting env:
   **8.95 ms/def**; the same 50 through a plain atom: **0.11 ms/def**
   (p5 §D) — and that was persisting a tiny one-namespace projection to a
   local filestore, synchronously, on the eval hot path.

**Konserve is a whole-value KV store with zero structural sharing.**
`update-blob` reads the full old value, applies the change in memory,
serializes the ENTIRE new value, and atomically moves a new file over the
old (`reference-code/konserve/src/konserve/impl/defaults.cljc:57-124`,
`:260-360`). Measured: a one-entry change to a 400 000-entry map costs
**310 ms** and rewrites everything (p2 §B). "Konserve supports structural
sharing" is not true of konserve; it is true of what Datahike *builds on
top of* konserve.

**Structural sharing over konserve is real, and it already exists in the
tree.** `persistent-sorted-set`'s durable mode stores only nodes whose
address is `null` — i.e. only dirty ones
(`reference-code/persistent-sorted-set/src-java/org/replikativ/persistent_sorted_set/Branch.java:1866-1884`),
so unchanged subtrees keep their content address and are never rewritten.
Measured on a 100 000-key tree (2.8 MB, 349 nodes): one `conj` + flush
rewrites **2 nodes, ~24 KB, 17 ms — 0.8 % of the tree** (p3 §B/§C), and a
lazy restore from a root address answers a lookup in **2 node reads,
5.7 ms** (p3 §D). The same data as one konserve value costs a **108 ms
full rewrite** for the same one-key change (p3 §E).

**And Datahike is already that tree.** Its indexes ARE persistent sorted
sets over konserve
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:409-467`,
`CachedStorage` implementing `IStorage`). So the honest answer to "extend
the Datahike API to support structural sharing" is: **Datahike does not
need extending — writing a datom IS the content-addressed
structurally-shared write the owner is asking for.** The current plan
(session image as `:seon.code.def` facts + blobs for large values) is
already that design. It was not chosen for this reason, but it is
correct for this reason.

The one honest caveat, and it favours the current plan rather than a new
substrate: Datahike's per-commit floor is **73 KB / 11 nodes / 65 ms**
with history on (p6 §B) — six index trees plus commit metadata, versus 2
nodes for a single bare tree. That floor is per *commit*, not per datom,
so it is paid once per turn, not once per `def`. A dedicated env-tree
would beat it per-write and lose everything else (see §5).

**What to do.**

1. **Keep the current design.** Session image as facts, blob tier for
   large values, forms-as-truth for anything a value cannot capture.
2. **Replace the closure predicate with a totality predicate** (§4). The
   naive "is it a `sci.impl.fns$…` class" rule is *wrong* — multi-arity
   `defn` produces `sci.impl.analyzer$analyze_fn_STAR_$reify…$f…`, and
   `partial`/`comp` over interpreted fns produce `clojure.core$…` classes
   (p4 §A). Worse, a fn predicate cannot see the *silent* fidelity
   losses: a `sorted-set-by` with a custom comparator round-trips to a
   `PersistentHashSet` that still compares `=`, metadata is dropped
   silently, a lazy-seq becomes a list (p2 §C, p4 §B). The correct rule
   is one computed question — **does this value round-trip through the
   real serializer with its class and metadata intact?** — and everything
   that fails it falls back to its defining form.
3. **The hybrid restore works, end to end.** A 50-def mixed session
   (data defs, chained fn defs, closures over locals, values computed
   from fns) restored into a fresh ctx in **11.3 ms** with **identical
   answers** (p4 §D). A re-evaluated closure DOES resolve a data def that
   was restored from the store and never evaluated (p4 §E) — the owner's
   core claim, proven.
4. **Never batch the durable write per `def`.** Persist the image once at
   the turn boundary. Per-`def` persistence is the 81× tax measured in
   p5 §D and it buys nothing the turn-boundary write does not.

**Hazards for the concurrent Lane 1 work** are in §6. The load-bearing
one: Lane 1A makes the ctx per-cluster and long-lived, which means the
session image must be diffed against a *shared* env whose Vars other
agents may have rebound — and a Var rebinding is invisible to any watch
on the env atom (§1, §6.1).

## 1. The env anatomy

`sci/init` (`reference-code/sci/src/sci/core.cljc:304-311` →
`impl/opts.cljc:236-280`) returns a `sci.impl.opts.Ctx` record whose
`:env` key holds an atom — and `opts.cljc:255` is literally
`(or env (atom {}))`, which is why a caller-supplied ref works at all.
`sci/fork` is `(update ctx :env (fn [env] (atom @env)))`
(`core.cljc:318-323`) — a new atom over the same map, therefore the same
Var objects.

`@env` measured (p1 §A/§B/§C), for a bare `(sci/init {:namespaces {'user
{}}})`:

| `@env` key | Class | Contents | Plain data? | Serializable? |
|---|---|---|---|---|
| `:namespaces` | `PersistentHashMap`, 12 entries, 687 interned names (585 in `clojure.core`) | ns-sym → `{name → sci.lang.Var, :obj sci.lang.Namespace, :aliases …, :refers …, :imports …, :types …}` | **no** — Vars and Namespace objects | **NO** (p5 §C) |
| `:imports` | `PersistentArrayMap`, 8 | simple-sym → fully-qualified class sym | yes | yes |
| `:class->opts` | `PersistentHashMap`, 18 | class sym → `{:class <java.lang.Class> …}` | **no** — JVM `Class` objects | no |
| `:raw-classes` | `PersistentHashMap`, 18 | the pre-normalized form of the same | **no** | no |
| `:ns-aliases` | `PersistentArrayMap`, 0 | global ns aliases | yes | yes |
| `:load-fn` | nil here; a fn when set | the host require hook | **no** — a fn | no |
| `:public-class` | nil here; a fn when set | host class resolver | **no** — a fn | no |

Inside one namespace entry (p1 §C, after `(def d {:a 1}) (defn f [x] (* 2
x)) (def g (fn [x] x))` in `my.probe`):

| Name | Var class | Root (value) class | Persistable |
|---|---|---|---|
| `:obj` | — | `sci.lang.Namespace` | no (identity object) |
| `d` | `sci.lang.Var` | `clojure.lang.PersistentArrayMap` | **yes, as a value** |
| `f` | `sci.lang.Var` | `sci.impl.fns$fun$arity_1__3706` | no — re-eval its form |
| `g` | `sci.lang.Var` | `sci.impl.fns$fun$arity_1__3706` | no — re-eval its form |

And the fields of `sci.lang.Var` itself
(`reference-code/sci/src/sci/lang.cljc:71-90`, read reflectively in p1
§E): `root` (`^:volatile-mutable`), `sym`, `meta`
(`^:volatile-mutable`), `thread-bound`, `needs-ctx`, `watches`
(`^:volatile-mutable`), `ns`. **The value is a mutable field on a JVM
object.** That single fact determines everything in §2.

## 2. The atom protocol, tested

### 2.1 sci's actual access pattern on the env atom

Nine `swap!` call sites across `core.cljc`, `impl/load.cljc`,
`impl/namespaces.cljc`, `impl/analyzer.cljc`, `impl/evaluator.cljc`,
`impl/deftype.cljc`, `impl/interop.cljc`, `impl/utils.cljc`,
`impl/opts.cljc`. Counted live (p1 §G), per form:

| Form | env `swap!`s |
|---|---|
| `(ns my.t)` | 1 |
| `(def a 1)` | **2** (analyzer pre-interns unbound, `analyzer.cljc:780-797`; then `eval-def`, `evaluator.cljc:45`) |
| `(defn h [x] …)` | 2 |
| `(+ 1 2)` | **0** |
| `(require '[clojure.string :as s])` | 1 |

So the env atom is swapped roughly twice per `def`, never for pure
computation. That is a *low* rate — the problem is not frequency.

### 2.2 The problem is that the swap carries no information

Measured (p1 §D, p5 §B):

```
first def of a NEW name    — watch fires 2x; first fires with a CHANGED env value
REdef of an EXISTING name  — watch fires 2x; BOTH fire with (identical? old new) TRUE
  ... and the value read back afterwards is the NEW one
(:namespaces env) identical? across a redefinition:  true
the Var OBJECT identical? across a redefinition:     true
```

Source: `eval-def` (`evaluator.cljc:29-48`) resolves the existing Var,
calls `vars/bindRoot prev init` — a mutation of the Var's `root` field —
and then `assoc`s the *same object* back under the same key. `assoc` of
an identical value at an existing key returns the identical map, so the
`swap!` is a structural no-op.

A durable atom therefore observes: the first `def` of each name (a map
grew), and nothing thereafter. A long agent session that redefines `x`
forty times would persist `x`'s first value and no other. This is not a
tuning problem; it is the wrong hook.

### 2.3 It cannot persist anything anyway

`@env` holds Vars and Namespaces. Measured (p5 §C):

```
whole @env                -> konserve:  REFUSED "Cannot write my.d as tag null"
(:namespaces @env)        -> konserve:  REFUSED
ONE namespace's table     -> konserve:  REFUSED
the DEREFED data value    -> konserve:  OK
```

So a durable env atom can only ever persist a hand-built *projection* of
dereferenced values — which is exactly the session image the current plan
already defines, reached by a worse route.

### 2.4 The cost, when you build it anyway

p5 implements a real `DurableRef` (`AtomicReference` + `IAtom` +
`IDeref`), hands it to `sci/init` as `:env`, and persists a small
name→edn projection of one namespace on every value-changing swap:

```
50 defs, durable-persisting env :  447.6 ms total, 8.95 ms/def  (50 persists)
50 defs, plain atom env         :    5.4 ms total, 0.11 ms/def
```

**81× on the eval hot path**, for a projection that is already incomplete
(§2.2) and already available for free at the turn boundary.

### 2.5 The technically-correct variant of the owner's idea

If you *do* want change capture at the source, the hook is the **Var, not
the atom**. `sci.lang.Var/bindRoot` calls `notify-watches`
(`lang.cljc:96-102`, `:61-69`), and `add-watch` on a sci Var works —
proven (p1 §F): redefining a watched Var fired `[[3 99]]`. That is a real
door and it sees exactly the events the env atom is blind to. It is still
not worth using here: you would have to install a watch on every interned
name (including names interned by other agents once Lane 1A makes the ctx
shared), and the thing you learn — "name N now has value V" — is the same
thing a turn-boundary diff of the namespace table tells you for free, at
zero per-`def` cost. Record it as the correct mechanism if a *streaming*
requirement ever appears; it is not needed for persistence.

## 3. Structural sharing, three substrates measured

### 3.1 (a) Konserve direct — no sharing, whole-value writes

Source: `-update-in`/`-assoc-in` exist on `PEDNKeyValueStore`
(`reference-code/konserve/src/konserve/protocols.cljc:4-12`) and look
path-shaped, but `update-blob`
(`impl/defaults.cljc:57-124`) does
`(update-in old-value rkey up-fn)` **in memory** and then serializes the
whole result into a new blob file which is atomically moved into place.
`io-operation` (`:260-360`) reads the full old value first for any
non-overwrite write. There is no node structure and no sharing.

Measured (p2 §A/§B):

| Operation | Time | Store delta |
|---|---|---|
| write a fresh 100 000-entry map | 77.4 ms | 1 284 808 B |
| rewrite the whole map with ONE entry different | 54.4 ms | +10 B (**full rewrite**) |
| `k/assoc-in` ONE path into it | 138.8 ms | +5 B (**full rewrite**) |
| one-entry `assoc-in`, n=1 000 | 9 ms | full rewrite |
| one-entry `assoc-in`, n=10 000 | 15 ms | full rewrite |
| one-entry `assoc-in`, n=100 000 | 60.6 ms | full rewrite |
| one-entry `assoc-in`, n=400 000 | **310.5 ms** | full rewrite |
| `bassoc` 8 MB / `bget` 8 MB | 11.1 / 2.6 ms | — |

Linear in the value's size, for every change. (The 8 MB blob numbers
reproduce `admission-caps-and-blob-fallback-2026-08-01.md`'s ~10 ms/8 MB.)

**Serializer fidelity (p2 §C, p4 §B) — the part nobody would notice:**

| Value | Round-trips? |
|---|---|
| plain map / vector / Ratio / BigInt / Character | **yes**, class preserved |
| `sorted-map` | `=` true, but comes back a `PersistentArrayMap` — **sortedness lost** |
| `sorted-set-by` with a custom comparator | `=` true, comes back a `PersistentHashSet` — **comparator and order lost** |
| a value with metadata | `=` true, **metadata silently dropped** |
| lazy-seq | comes back a `PersistentList` |
| `java.time.Instant`, an atom, any fn | **refused** with an exception |

The three `=`-true rows are the dangerous ones: an equality check would
call a restore faithful when the agent's ordering or metadata is gone.

### 3.2 (b) Datahike facts — sharing, at a per-commit floor

Datahike's indexes are persistent sorted sets over konserve
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:409-467`),
so a transact writes only the nodes it dirties. Measured on a 2 000-entry
image (p6):

| Operation | Time | Store growth |
|---|---|---|
| seed 2 000 entries in one transact | 501.7 ms | 735 441 B, 79 files |
| ONE redefinition (one datom), median of 12 | **65 ms** | **73 322 B, 11 new files** |
| a 588 891-char edn value INLINE in a datom | 100.8 ms | 4 240 593 B (**7.2× the payload**) |
| a LATER unrelated one-datom transact, after that | 74.3 ms | **2 429 951 B** (was 73 KB) |
| `d/branch!` | **20.1 ms** | **1 583 B** |

Three readings. First, the per-commit floor is 11 nodes / 73 KB with
`:keep-history? true` — six index trees (eavt/aevt/avet plus their
temporal twins) each rewriting a root path, plus commit metadata. That is
per *commit*, so one turn's whole image costs one floor, not one per
`def`. Second, the last two rows are the ~80× amplification of
`admission-caps-and-blob-fallback-2026-08-01.md` caught in the act: a big
inline value inflates every *subsequent* unrelated commit from 73 KB to
2.43 MB, because it sits in leaves the next commit's path rewrites. **Big
values go in blobs; this is now measured twice, independently.** Third,
`d/branch!` is a head pointer — 20 ms and 1.6 KB — which is why the
grader's branch path is free.

### 3.3 (c) A content-addressed node tree — real sharing, and it exists

`IStorage.store(node)` is called only for nodes whose address is `null`:
`Branch.store` iterates children and recurses only `if (newAddresses[i]
== null)`
(`reference-code/persistent-sorted-set/src-java/org/replikativ/persistent_sorted_set/Branch.java:1866-1884`);
`psset/store`'s own docstring says "Incremental, won't store same node
twice on subsequent calls"
(`src-clojure/org/replikativ/persistent_sorted_set.clj:308-316`).

p3 builds a minimal konserve `IStorage` (the same shape as Datahike's
`CachedStorage`, minus the LRU and freelist) and measures it directly:

| Operation | Time | Nodes written | Bytes |
|---|---|---|---|
| build + flush a 100 000-key tree | 3 045.6 ms | 349 | 2 815 414 |
| **one `conj`, flush** | **17.8 ms** | **2** | **22 214 (0.79 % of the tree)** |
| ten more one-key changes (median) | 17 ms | **2** | 24 705 |
| lookup in a freshly restored lazy tree | 5.73 ms | 2 node **reads** | — |
| the same data as ONE konserve value, one-key change | 108.0 ms | — | full rewrite |

So: yes, an env projection *could* live as such a tree, sharing nodes
across versions; and no, we would not be building it — `psset/sorted-set*`
with a `:storage` and `psset/restore-by` are the public API
(`persistent_sorted_set.clj:258-272, 284-296`), and Datahike's
`create-storage` (`persistent_set.cljc:461-467`) is a ready-made konserve
`IStorage` with caching.

But writing a datom already goes through this machinery. A separate
env-tree would buy 2 nodes/change instead of 11 and would cost: its own
root-pointer transaction (to make the version durable and recoverable at
all), its own GC reachability class (§5), its own branch/fork semantics,
its own history story, and a second durable authority for agent state.
That is a second mechanism for a 5× per-write saving on a write that
happens once per turn.

## 4. Closure detection and hybrid restore

### 4.1 The naive predicate is wrong

Measured classes (p4 §A):

| Shape | `fn?` | Class |
|---|---|---|
| single-arity `defn` | true | `sci.impl.fns$fun$arity_1__3706` |
| **multi-arity `defn`** | true | **`sci.impl.analyzer$analyze_fn_STAR_$reify__4562$f__4563`** |
| variadic `defn` | true | `sci.impl.fns$fun$arity_0__3408` |
| anonymous `fn` / closure over a local | true | `sci.impl.fns$fun$arity_1__3706` |
| **`partial` over an interpreted fn** | true | **`clojure.core$partial$fn__5929`** |
| **`comp` over an interpreted fn** | true | **`clojure.core$comp$fn__5897`** |
| a host fn (`inc`) | true | `clojure.core$inc` |

A `sci.impl.fns$` prefix rule misses three of these — and it is a hand
rule besides. Plain `fn?` catches all of them, because *no* function
serializes.

### 4.2 Walk depth, and what a fn predicate still cannot see

p4 §B, for each value: shallow `interpreted-fn?`, a deep
`(some interpreted-fn? (tree-seq coll? seq v))`, and the round-trip probe.

| Value | shallow | deep walk | store-faithful |
|---|---|---|---|
| plain data | false | false | **ok** |
| a `defn` value | **true** | true | refused |
| a MAP containing a fn | false | **true** | refused |
| nested vector containing a fn | false | **true** | refused |
| set containing a fn | false | **true** | refused |
| `sorted-set-by` w/ fn comparator | false | **false** | **`:equal true` but `:same-class false`** |
| `sorted-map` | false | false | **`:equal true` but `:same-class false`** |
| value with metadata | false | false | **`:equal true` but `:meta-kept false`** |
| lazy-seq | false | false | `:same-class false` |
| an atom / a host fn | false | false | refused |

Two conclusions. A **deep walk is required** — the shallow predicate
misses every nested closure. And **the deep fn walk is still not
sufficient**: the comparator of a `sorted-set-by` is a fn that
`tree-seq` never reaches (it is a field of the collection, not an
element), and metadata and sortedness vanish with no error at all.

Deep-walk cost (p4 §C): 0.00 ms for a small map, 0.99 ms for a 10k
vector, **16.98 ms for a 200k vector**, 4.12 ms for a 10k-entry map. On a
large value the walk costs about as much as `pr-str` of it — so a walk on
every `def` is not free either.

**The rule to implement:** one computed predicate, `store-faithful?` —
write the value through the real serializer, read it back, and require
`=` **and** identical class **and** identical metadata. Everything that
fails falls back to its defining form. This is one question, no hand
list, and it is the only predicate that catches all three failure
families (refusal, structural loss, metadata loss). Its cost is a real
round-trip, so it belongs at the turn boundary on the values a turn
actually changed, never inside the eval loop.

### 4.3 The hybrid restore, end to end

p4 §D builds a 50-def session in `my.sess`: 20 data defs, 20 chained
`defn`s (`f19` calls `f18` … `f0`, each also reading a data def), 5
`def`s holding closures over locals, and 5 data defs *computed from*
`f19`. Then it classifies, persists to konserve, and restores into a
fresh ctx by the stateless-resume two-pass method (pre-intern every name
unbound, bind the value entries, evaluate the source entries in ordinal
order).

```
original session eval:                       10.3 ms
original answers:                            [2101 15 2104 200]
image entries: 50   (as value: 25, as source: 25)
as-source names: [c0..c4 f0..f19]
image write to konserve:                      9.6 ms
RESTORE (read image + 2 passes):             11.3 ms
restored answers:                            [2101 15 2104 200]
IDENTICAL TO ORIGINAL?                       true
```

**0.23 ms per name**, consistent with `stateless-resume`'s 110 µs/name
for a lighter mix. Note the classification landed exactly where the
design predicts: the 25 fn-valued names (including the five `def`s of
closures over locals, which have no `:seon.fn` row) went to source; the
25 data names — including `r0..r4`, values *computed by* an interpreted
fn — went as values.

### 4.4 The owner's key claim, proven

"Re-eval with the restored context" requires that a closure's free names
resolve through Vars that were populated from the store, never evaluated.
p4 §E:

```
data def arrives as a VALUE from konserve (never evaluated in this ctx),
interned via sci/add-namespace! + sci/new-var; then the closure's
defining form is evaluated against that ctx:
  (uses-stored 5)  =>  1005          ; 5 + (count stored), stored = (range 1000)

and the reverse order — intern the name UNBOUND, define the fn, bind later:
  (uses-later 5)   =>  12            ; 5 + 7
```

Both work. The second is the load-bearing one: sci's analyzer captures
the **Var object** at analysis time (`analyzer.cljc:780-797` interns an
unbound Var on first reference), so a later `bindRoot` is seen by a
function that was analyzed before the value existed. That is precisely
why `stateless-resume-design`'s two-pass pre-intern makes ordering
irrelevant, and it is independently reconfirmed here.

For completeness, p4 §F: storing an undetected closure is not silently
wrong — konserve refuses it (`Cannot write sci.impl.fns$fun$arity_1… as
tag null`). The *silent* failures are the fidelity losses of §4.2, not
the closures.

## 5. The comparison, and why the current plan wins

| | konserve direct | Datahike facts (current plan) | a dedicated psset env-tree |
|---|---|---|---|
| structural sharing | **none** — whole-value rewrite (p2) | yes, via the same psset trees (p6) | yes, 2 nodes/change (p3) |
| one small change | 60–310 ms, size-linear | **65 ms, 73 KB** (per commit, not per def) | 17 ms, 24 KB **+ a root-pointer commit** |
| large values | `bassoc` 11 ms/8 MB — good | **must** be blobs: inline costs 7.2× and taxes every later commit 33× (p6 §C) | same problem, same answer |
| restore fidelity | serializer loses sortedness, comparators, metadata *silently* (p2 §C) | identical serializer, **same losses** — which is why forms-are-truth is the fallback, not values | identical serializer, same losses |
| branch / fork | no notion of a version at all | **`d/branch!` = 20 ms, 1.6 KB** (p6 §D) | would need its own root-pointer versioning, invented from scratch |
| history / time travel | none | `:keep-history? true`, already on (ruling #23) | none; would need its own |
| GC | manual | `datahike.gc/gc-storage!` marks from branches + index roots (`reference-code/datahike/src/datahike/gc.cljc:22-81, 83-140`) — Seon's blobs already need a union hop | a **third** reachability class (node addresses, not digests) that the filed blob-GC fix would not cover |
| queryability | none | Datalog, the same store the agent already queries | none |
| complexity | one API, wrong semantics | **zero new mechanisms** | a second durable authority for agent state |

The GC row deserves naming. `docs/seon/issues/blob-reachability-names-one-attribute-by-hand.md`
already records that Seon's blobs share the Datahike store and would be
swept unless the mark is extended, and that the fix is to derive the
reachable set from the schema attributes whose declared form is
`:seon.blob/digest`. A psset env-tree would introduce a reachability
class that fix does **not** cover — psset node addresses are not blob
digests — so it would require a third marking path. That is a concrete
argument against the new substrate, not a hypothetical.

**So: is the answer "the current plan already is the content-addressed
structural-sharing design"? Yes — and the owner's instinct about the
substrate was right; only the door was wrong.** The immutable
structurally-shared durable data structure the owner wants exists, it is
`persistent-sorted-set` over konserve, it is measured here at 0.79 %
write amplification, and Seon already writes through it every time it
transacts a datom. Extending the Datahike API is unnecessary because the
extension point is already the public one: `d/transact`.

The one place a direct konserve write is right — and the owner's aside
"maybe directly reading/writing konserve doesn't need to be the Datahike
api" is correct here — is the **blob tier**: large values go through
`bassoc`/`bget` (11 ms per 8 MB, p2 §D) precisely to stay *out* of the
index trees, because p6 §C shows what one big inline value does to every
later commit. That is already ruling #25's design.

## 6. Hazards found, including for the concurrent Lane 1 work

1. **A shared long-lived ctx makes the session-image diff harder than a
   per-run fork did.** Lane 1A makes the ctx per-cluster and durable
   across turns; ruling #27 intends agents to share it. But §2.2 means
   there is **no observable event** when agent B rebinds a name agent A
   defined — the env atom does not change, the Var mutates. Any design
   that plans to capture "what this turn changed" by watching the env
   atom, diffing `(:namespaces @env)` by identity, or comparing
   before/after env values **will silently record nothing for every
   redefinition**. The only correct turn-boundary diff compares
   *dereferenced values* per name, not map or Var identity. This is worth
   stating in the Lane 1 plan before someone writes the cheap version.
   `refactor-wave`'s Slice 1A table already lists "removed-identity
   diffing … before/after interns are now against a shared env" as a
   discovery candidate; this is the sharper form of it.
2. **`=`-true is not restore fidelity.** Any acceptance test that
   validates a session image with `(= original restored)` will pass while
   sortedness, custom comparators, and metadata are gone (p2 §C, p4 §B).
   The regression for this class must assert class and metadata, not
   equality.
3. **Big values must not ride datoms.** p6 §C: a 589 KB inline edn value
   grew the store 4.24 MB on its own commit, and inflated the *next*
   unrelated one-datom commit from 73 KB to 2.43 MB. Independent
   confirmation of the admission-caps ruling; a session image that inlines
   `value-edn` without a size cut-over will degrade every later turn in
   the cluster, not just its own.
4. **The deep closure walk is not free on large values** — 17 ms for a
   200k vector (p4 §C). If classification walks every `def`'s value every
   turn, a session holding a few large collections pays that per turn.
   Classify only names whose value changed this turn.
5. **Vendored konserve is one commit ahead of the pinned SHA.**
   `deps.edn` pins `b5c99bc0` ("Make Node filestore deletion idempotent");
   `reference-code/konserve` HEAD is `737697d` ("Implement ordered
   filestore multi-key operations"), with the pin as its parent. Our own
   fork has an unevaluated commit sitting in the vendored tree — the exact
   shape the standing upstream-delta sweep is meant to catch. Not a
   defect; worth one line in the next sweep.

## 7. Reproducing

```bash
clojure -M:dev -i tmp/durable-env/p1_env_anatomy.clj          # env anatomy, swap! blindness, Var watches
clojure -M:dev -i tmp/durable-env/p2_konserve_amplification.clj  # whole-value writes, serializer fidelity
clojure -M:dev -i tmp/durable-env/p3_psset_sharing.clj        # real structural sharing over konserve
clojure -M:dev -i tmp/durable-env/p4_closures_and_restore.clj # closure detection + hybrid restore
clojure -M:dev -i tmp/durable-env/p5_durable_atom.clj         # the atom protocol, built and measured
clojure -M:dev -i tmp/durable-env/p6_datahike_sharing.clj     # datom-level sharing, amplification, branch cost
```

Each writes only under `tmp/durable-env/store-p*` and calls
`(System/exit 0)`.
