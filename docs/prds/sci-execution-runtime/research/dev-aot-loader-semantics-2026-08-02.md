---
type: research
status: active
tags: [research, architecture]
---

# Development AOT loader semantics

## Verdict

Clojure 1.12.5 does support the intended development-cache shape, with
important limits. For one namespace, `RT/load` compares the first classpath
resource named `<root>__init.class` with the first `.clj` resource (or the
first `.cljc` resource only when no `.clj` exists). It loads the class only
when the class resource timestamp is **strictly greater** than the source
timestamp; equal timestamps choose source. A first-party edit whose source
mtime advances past its cached `__init.class` therefore evaluates from source
in a fresh process, and `require :reload` reaches that same decision in a live
process.

That rule does **not** make every possible stale class impossible. It does not
remove cached classes for deleted namespaces, enumerate all duplicate
resources, or invalidate an AOT consumer when only a macro dependency changes.
A correct refresh target must rebuild into an empty replacement directory and
must recompile the whole selected closure when any selected macro provider
changes. Source preference remains the hot-edit mechanism after that cache has
been admitted.

The core.async caveat in the current working edge needs a correction. The
selected `1.10.874-alpha3` always expands `go` through the IOC compiler, whether
the namespace is loaded from source or AOT. The briefly shipped
`clojure.core.async.vthreads` property was reverted before this pin and is
absent here. Only the `:io` executor and `io-thread` opportunistically use a
virtual thread on this pin. AOT-caching core.async therefore does not change
`go` from a virtual-thread implementation to IOC: both sides are already IOC.

## Dependency ledger

| Dependency | Selected identity | Maintained source | Boundary used here |
|---|---|---|---|
| Clojure | `org.clojure/clojure` `1.12.5` (`deps.edn:15`); release tag dereferences to `3bc2b3e91fdf` | `reference-code/clojure` is currently post-release master `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`; the exact release object was fetched into that repository for comparison | `reference-code/clojure@3bc2b3e9:src/jvm/clojure/lang/RT.java:412-484`; `src/clj/clojure/core.clj:5960-6007,6070-6138,6176-6205`; `src/jvm/clojure/lang/Compiler.java:7572-7589,8290-8308,8327-8395` |
| core.async | `org.clojure/core.async` `1.10.874-alpha3` (`deps.edn:22-24`), tag/checkout `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async` at the exact selected tag | `src/main/clojure/clojure/core/async.clj:485-505,509-556`; `impl/go.clj:977-1059`; `impl/dispatch.clj:71-123` |

The checked-out Clojure master and the exact `1.12.5` tag have byte-identical
`RT.lastModified`, `RT.compile`, and `RT.load` bodies (SHA-256
`a4ebb2085139841964b988fcdf8f59779c8f549b83c61c22766962a1836a0c06` for
the compared slice). `javap -c -p` against the selected Maven
`clojure-1.12.5.jar` independently showed the same branch structure. The line
citations below use the exact tag rather than assuming post-release master.

The current core.async Maven jar contains its three relevant namespaces as
source (`clojure/core/async.clj`, `impl/go.clj`, and `impl/dispatch.clj`) and
does not contain their `__init.class` files. It is therefore runtime-compiled
today and is a real candidate for the development class cache.

## Exact namespace-loading decision

`require` skips an already loaded lib unless `:reload` or `:reload-all` is
present. When loading is selected, `load-one` maps the namespace to its root
resource and calls `clojure.core/load`; that ultimately calls `RT/load`
(`reference-code/clojure@3bc2b3e9:src/clj/clojure/core.clj:5960-6007,6070-6138,6176-6193`).

For a root such as `x/y/z`, `RT/load` does the following:

1. Construct `x/y/z__init.class`, `x/y/z.clj`, and `x/y/z.cljc` names
   (`RT.java:447-451`).
2. Ask the classloader once for the class resource and once for `.clj`; only
   when `.clj` is absent does it ask for `.cljc` (`:452-457`). This is
   first-resource classpath selection, not a scan for the globally newest
   duplicate.
3. Try the generated loader class when the class resource exists and either
   source is absent or `class-mtime > source-mtime` (`:458-474`). A tie does
   not satisfy that strict comparison. The same branch also attempts the
   generated class name when `classURL` is nil, which permits a classloader to
   supply a class it cannot expose as a URL; an ordinary directory/jar miss
   returns nil and continues to source.
4. If no class loaded and source exists, compile it only under
   `*compile-files*`; otherwise evaluate the source resource (`:475-480`). If
   neither path loaded, fail with the three expected resource names
   (`:481-483`).

`lastModified` has two materially different implementations
(`RT.java:412-425`):

- for a `jar:` URL it reads the ZIP entry time from
  `JarFile.getEntry(libfile).getTime()`; the jar file's filesystem mtime is not
  consulted;
- for every other URL, including an ordinary classpath directory, it uses
  `URLConnection.getLastModified()`.

It opens and closes the selected resource stream in either case. An I/O error
while resolving either timestamp propagates rather than silently preferring
source.

### What the rule does prove

- Put an AOT cache directory on the classpath while retaining all source
  roots. An ordinary source file with a later mtime than its cached loader
  class wins in the next JVM.
- Equal millisecond timestamps are safe for source preference because the
  class comparison is strict `>`.
- `.clj` has explicit suffix precedence over `.cljc`; timestamp comparison
  happens only after that choice.
- Editing source and then using `require :reload` in a live JVM re-enters
  `RT/load`. When the source now wins, its forms are evaluated and ordinary
  Vars receive the new roots. Without `:reload`, `require` correctly skips a
  lib already recorded in `*loaded-libs*`.

### Failure cases a cache target must close

1. **Deletion/rename:** if a stale cached `__init.class` remains and the old
   source resource disappears, `cljURL` is nil and the class is explicitly
   preferred (`RT.java:460-463`). Refresh into a new empty directory and admit
   it atomically; never compile incrementally over the old output tree.
2. **Macro and inline capture:** AOT compilation invokes the currently loaded
   macro Var and emits its expansion into the consumer class
   (`reference-code/clojure@3bc2b3e9:src/jvm/clojure/lang/Compiler.java:7572-7589,8290-8308,8327-8395`). If only the macro provider source changes, the
   consumer's own source mtime has not changed, so per-namespace loader
   preference cannot detect that transitive semantic staleness. A refresh
   must recompile the selected closure after any selected macro/inline source
   change; merely recompiling the provider is insufficient.
3. **Duplicate resources/classpath order:** `getResource` chooses one class
   URL and one source URL. It does not choose the newest among all matching
   source or class roots. The cache must be one unambiguous first class root,
   and the source classpath must retain the intended source roots without an
   earlier duplicate.
4. **Jar entry timestamps:** copying or touching a jar does not change the
   entry time used by the comparison. A rebuilt jar that preserves old entry
   times may still lose to an older-looking directory class; a jar entry may
   also report an unknown timestamp. Cache admission should be keyed by input
   identity and refresh explicitly, not use the outer jar mtime as its
   currentness test.
5. **Preserved/backdated source mtimes:** the loader cannot detect changed
   bytes whose source timestamp remains older than the loader class. The build
   target should use digests to decide whether to refresh; loader mtimes are
   the live-edit preference after refresh, not the cache's complete validity
   proof.
6. **Named JVM classes:** source preference updates Vars, but a class already
   defined in a JVM is not unloaded. `defrecord`, `deftype`, `gen-class`, and
   other named-class changes retain the usual restart boundary. The requested
   ordinary-Var hot-reload proof should not be generalized to Java class
   redefinition.

## core.async AOT and virtual-thread result

At the exact pin, `go` is a macro that unconditionally calls
`clojure.core.async.impl.go/go-impl`
(`reference-code/core.async/src/main/clojure/clojure/core/async.clj:485-505`).
`go-impl` analyzes the body, emits an IOC state machine, and schedules
`run-state-machine-wrapped` through the core.async dispatch executor
(`impl/go.clj:1024-1059`). `go-loop` is only syntax over `go`
(`core/async.clj:553-556`). There is no branch on `*compile-files*` and no read
of `clojure.core.async.vthreads` in this selected source.

Virtual threads remain real, but at a different boundary. The `:io` executor
detects the virtual-thread class and uses `Thread.startVirtualThread` when it
is available (`impl/dispatch.clj:75-96`); `io-thread` sends its body through
`thread-call` with workload `:io` (`core/async.clj:509-549`). The only system
property read by this selected dispatcher is
`clojure.core.async.executor-factory` (`impl/dispatch.clj:98-111`).

The local vendor-announcement capture currently says that version ordering
alone proves alpha3 contains the vthread-backed `go` and the
`clojure.core.async.vthreads` dial
(`core-async-virtual-threads-capture-2026-08-01.md:11-40`). Git history
falsifies that inference: ASYNC-262 added the property and alternate expansion
at `634d6cb402108fdd2b10792b422af2614e31e7bd`, then
`6b47312` reverted it before the selected `dc35f3e` release. The working edge's
statement that the property is inert/reverted is correct; its implication that
only AOT produces IOC is not.

### Direct probes on JDK 26.0.1

Three fresh source-load JVMs, with the property unset, `target`, and `avoid`,
all produced:

- a `go` macroexpansion containing `run-state-machine-wrapped` and no
  `thread-call`;
- no `target-vthreads?` Var in `clojure.core.async.impl.dispatch`;
- a real `:io` task reporting `Thread.isVirtual() = true`, including under
  `avoid`, proving that the property is ignored on this pin.

The decisive form in each fresh process was equivalent to:

```clj
(require '[clojure.core.async :as a]
         '[clojure.core.async.impl.dispatch :as dispatch])
(let [expansion (pr-str (macroexpand '(a/go :ok)))
      result (promise)]
  (dispatch/exec #(deliver result (.isVirtual (Thread/currentThread))) :io)
  {:ioc? (boolean (re-find #"run-state-machine-wrapped" expansion))
   :thread-call? (boolean (re-find #"thread-call" expansion))
   :property-var? (boolean (ns-resolve 'clojure.core.async.impl.dispatch
                                       'target-vthreads?))
   :io-virtual? @result})
```

AOT-compiling `clojure.core.async` into a repository-local scratch directory
took 7,066 ms, emitted 493 class files, and emitted multiple
`state_machine`-named classes. Fresh JVMs with that class directory first on
the classpath proved the class URL was selected while the source remained in
the Maven jar. Under all three property settings they again showed IOC
macroexpansion, no property Var, and a virtual `:io` task. This is the expected
same-semantics result for this pin.

## Recommendation and falsifier

Do not exclude core.async merely because AOT would turn vthread `go` blocks
into IOC: that transition does not exist at the selected revision. Include it
when the namespace profile shows material load cost, subject to the same
build-time-side-effect and closure-refresh gates as every other dependency.

The recurring falsifier for inclusion should run once from source and once
with the admitted class cache first on the same source classpath, in fresh
JVMs, and assert:

- `(a/<!! (a/go :ok))` returns `:ok`;
- macroexpansion contains the IOC runtime call and not `thread-call` on this
  exact pin;
- an `:io` dispatch task reports a virtual thread on the supported JDK;
- `target` and `avoid` do not change either result while this pin remains;
- changing a dependency source then refreshing the cache removes all old
  output before recompiling the entire selected closure.

Exclude core.async immediately if a future selected revision reintroduces a
source-versus-AOT `go` expansion distinction, or until an explicit owner ruling
chooses which expansion is the development contract. A core.async pin change
also requires a full selected-closure refresh because `go` expansions are
captured in every AOT consumer; timestamp preference on core.async's own
namespace cannot invalidate those consumers.
