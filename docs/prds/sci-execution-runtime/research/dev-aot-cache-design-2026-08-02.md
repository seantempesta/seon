---
type: research
status: active
tags: [research, runtime]
---

# Development dependency AOT-cache design

## Decision

Build one development class cache for the dependency namespaces actually loaded
by `(require 'seon.artifact)`. The first cache includes only source resources
owned by non-project directory classpath roots: local/root and git dependencies
such as Datahike, clj-kondo, SCI, http-kit, Datastar, and Konserve. It never
AOT-compiles a first-party namespace, and it does not enumerate namespaces by
name or scan every source file under a dependency root.

The current uncached closure proves the selector's expected result: a fresh JVM
has 425 newly loaded libraries after requiring `seon.artifact`, and exactly
205 of the resulting loaded libraries resolve their `.clj` or `.cljc` source
inside a non-project directory dependency root. This is the same 205-namespace
source-dependency population measured at 6,014 ms, or 56.9% of exclusive load,
in `source-load-profile-2026-08-02.md`.

The cache is an acceleration layer over the source classpath, not another build
or runtime. Source stays present. Clojure's loader compares the first
`__init.class` resource with the first source resource and chooses the class
only when its timestamp is strictly newer
(`reference-code/clojure@3bc2b3e9:src/jvm/clojure/lang/RT.java:447-480`). A
normal later source edit therefore selects source; an equal timestamp also
selects source. A refresh additionally uses content digests and a fresh output
directory, closing the deletion, backdated-mtime, and macro-capture cases that
the loader's timestamp rule alone cannot close.

## Exact derived closure

The refresh child starts from the project basis without the admitted cache on
its classpath. It obtains the set to compile as data:

1. Derive the ordered classpath and the canonical project and library roots
   from `clojure.tools.build.api/create-basis` over `deps.edn`.
2. Start a fresh `clojure.main` JVM with the same JVM options as `:dev`, an
   empty candidate class directory first, and the uncached project classpath
   after it.
3. Require `seon.artifact`, then read `clojure.core/loaded-libs`.
4. For each loaded library, derive its root resource by Clojure's own mapping
   (dots to slashes and dashes to underscores), resolving `.clj` before
   `.cljc`, exactly as `root-resource`, `load-one`, and `RT/load` do
   (`reference-code/clojure@3bc2b3e9:src/clj/clojure/core.clj:5943-5971`;
   `RT.java:447-457`).
5. Select the library only when that resolved source is a file beneath a
   canonical directory root owned by a basis library and is not beneath a
   project path. Sort the resulting symbols only to make compilation and the
   manifest deterministic.

The emitted manifest stores that derived vector with each library's selected
source URL, owning basis library, and source digest. It is evidence, not an
input roster. A refresh always re-derives it from a real load. Namespace
prefixes (`datahike.*`, `sci.*`, and so on), the literal `reference-code/`
path, and a checked-in namespace list are all forbidden selectors: Konserve is
under `.gitlibs`, path layout can change without changing ownership, and a
prefix is not a classpath fact.

### Why not compile every classpath namespace

Classpath reachability is broader than application reachability. The current
directory dependencies contain 202 JVM Clojure source files before even
counting clj-kondo's parser and inlined roots. They include product generators,
pod entry points, experimental namespaces, compliance tests, and alternate
surfaces such as `datahike.codegen.*`, `datahike.pod`, and
`konserve.compliance-test` that the artifact closure does not load. Future
optional backend namespaces may require coordinates deliberately absent from
the selected basis. A recursive source scan would execute those top-level
forms during AOT and can fail on optional dependencies that the running system
never needs.

`loaded-libs` after the one real root require is the smallest honest boundary:
every compiled namespace is demonstrated reachable, and every reachable
directory-source dependency is included. Direct `load` helper resources that
do not establish libraries are excluded because `compile` accepts a namespace,
not an arbitrary load path (`core.clj:6195-6205`).

## Compilation without transitive spill

The discovery JVM leaves the complete closure loaded, then binds
`*compile-path*` to the empty candidate directory and calls `compile` once for
each selected symbol. `compile` binds `*compile-files*` and reloads the named
library (`core.clj:6195-6205`), while its nested `require`s see their libraries
already present in `loaded-libs` and skip them (`core.clj:5985-6001`). This
emits the selected target without recursively AOT-compiling jar or first-party
namespaces. The target must assert the output contains one selected
`__init.class` per manifest row and contains no `seon/**__init.class`.

Compilation re-executes each selected namespace's top-level forms in the
short-lived build JVM. That is an intrinsic AOT property, not permission to
run the application or publish facts. A build-time side effect, background
owner, or dependency compile failure rejects the candidate and leaves the
active cache untouched.

## Digest and replacement contract

The cache identity is a SHA-256 over a canonical EDN description containing:

- a cache-format/selector version;
- the ordered uncached classpath and resolved basis library identities;
- the bytes of every selected dependency source;
- the bytes of every non-project jar and dependency directory entry that can
  affect compilation, including macro providers, Java classes, and resources;
- the selected Clojure coordinate/jar digest, JDK major version, architecture,
  and effective compiler/JVM options; and
- the derived selected-library vector.

Paths identify inputs in the manifest, but byte digests decide equality;
mtimes never decide cache validity. Hashing all non-project dependency inputs
is intentionally broader than hashing the 205 outputs: a macro or inline
provider can be captured in an AOT consumer even when the provider itself is
not selected. Any dependency edit, pin change, classpath-order change, or
compiler change therefore recompiles the whole selected closure, never only
the apparently changed provider.

Each refresh compiles into a newly created empty directory under `target/`.
It validates the manifest, output set, and `class-mtime > source-mtime` for
every selected loader class before admission. A failed compile or validation
does not mutate the active cache. Admission replaces the active directory (or
an active link to an immutable digest directory) only after validation; it
never compiles incrementally over admitted output. Thus a successful refresh
cannot retain a class for a deleted or renamed namespace. Refresh operations
serialize on one target-local lock, and old digest directories are retained
until `clean` so a running JVM never loses files it may still resolve.

The source-preference rule handles ordinary edits made after admission. The
digest handles edits whose mtimes were preserved or moved backward when the
refresh target next runs. A source deletion cannot be made safe by
newer-wins—Clojure explicitly loads the remaining class when source is absent
(`RT.java:460-463`)—which is why fresh replacement is mandatory.

## Classpath placement

Add only the admitted cache path to the `:dev` alias. A local tools.deps probe
places a `:dev` `:extra-paths` entry before project `src`, `resources`, and all
dependency roots. This makes the cache the unambiguous first class resource,
while the first source resource remains the ordinary source path used for the
mtime comparison. The target must assert that ordering from its effective
basis rather than relying on the probe forever.

Do not add the cache to `:test`. `bin/test` launches `clojure -M:test`, so it
continues to exercise the source classpath independently. `bin/seon` launches
its child with `-M:dev` (`script/seon/fresh_operator.clj:243-254`), so the same
single `:dev` classpath addition accelerates both the direct require and the
end-to-end operator start without changing operator code.

## core.async policy

The initial directory-source selector excludes core.async automatically
because the selected `org.clojure/core.async` artifact is a Maven jar, not a
directory dependency. This is an origin rule, not a `clojure.core.async` name
exception. Its measured residual is material but bounded:
`clojure.core.async` averaged 312 ms and `clojure.core.async.impl.go` about
116 ms.

There is no semantic reason to exclude this pin permanently. The exact
`1.10.874-alpha3` jar contains source and no corresponding loader classes.
Both source loading and AOT expand `go` through the IOC compiler; the reverted
`clojure.core.async.vthreads` property is absent, while `:io` dispatch still
uses virtual threads (`dev-aot-loader-semantics-2026-08-02.md`, “core.async AOT
and virtual-thread result”). A direct AOT probe already confirmed the same IOC
macroexpansion and virtual `:io` behavior under the cache.

First measure the directory-only cache three times. If it misses the under-five
second require gate, widen the same origin predicate to observed jar-source
libraries that lack `__init.class`; do not add a core.async roster or a second
cache. That widened policy must be measured as a whole because jar/platform
source is another 3.53 seconds and core.async macro expansions are captured in
consumers. Any future core.async pin change invalidates the entire cache and
must rerun the source-versus-AOT behavior falsifier before admission.

## Smallest implementation surface

The smallest public API is one tools.build target:

```bash
clojure -T:build dev-aot-cache
```

Put the implementation in one new build namespace, with `build.clj` delegating
that target to it. `deps.edn` needs only the build namespace path and the one
`:dev` cache path. No `bin/` wrapper, cache daemon, watcher, namespace registry,
or operator branch is needed. Re-running the target is the refresh operation;
an identical digest may select the already validated immutable entry, while a
changed digest builds and admits a fresh replacement.

The target prints one compact result map with namespaced keys: cache digest,
selected namespace count, class count, build duration, and active path. On
failure it exits nonzero and reports the rejected candidate path without
switching the active cache.

## Acceptance sequence

1. Build the directory-source cache and record its derived namespace count and
   manifest. Assert no first-party loader class exists.
2. In three fresh JVMs, run
   `clojure -M:dev -e "(time (require 'seon.artifact))"`; each valid cold run
   must be under five seconds, and report all three plus mean and median.
3. If that gate misses, measure the one widened observed-jar-source policy as
   described above; keep only the policy that passes.
4. Run the full `bin/test` gate. It must remain source-only and green.
5. Prove first-party hot reload in a fresh JVM: require an ordinary first-party
   namespace, edit a Var-returning function, invoke `require` with `:reload`,
   and observe the new value. Also show that namespace has no class resource in
   the cache. Restore the source coherently after the proof.
6. On an existing isolated operator root, measure wall time from
   `bin/seon --root ROOT start NAME` to its successful readiness output three
   times. The ten-second law requires every accepted run below ten seconds.
7. Touch one selected dependency source, prove the next fresh require chooses
   source, refresh, and prove the admitted replacement has a new digest and no
   class left over from a deliberately removed candidate namespace fixture.

The cache graduates only when the require, test, hot-reload, and operator gates
all pass. A faster require that moves startup past ten seconds elsewhere does
not close the velocity incident.
