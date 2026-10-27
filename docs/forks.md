# Forks

Every library we customize is a git submodule under `reference-code/`, pinned at a commit of our fork that carries a short list of our own commits on top of an upstream base, each marked **Generic** (an upstream candidate) or **Jolt** (specific to running on Jolt, kept on our line).

## Branch scheme

The branches each fork keeps: our working line, one fix per topic, and the generic fix cut for upstream.

- ``jolt/main`` in each fork is our working line; it may stay Jolt-specific.
- ``jolt/<topic>`` holds one fix, cut from ``jolt/main`` (or from the topic it needs) and merged into ``jolt/main`` once measured. A pin may sit on a topic branch that has not yet been merged into ``jolt/main``; its section says so.
- ``upstream/<topic>`` is a generic fix cut cleanly from upstream's own main, the only candidate for an upstream pull request, opened only when 100% sure.
- Commits in the forks carry no AI trailers.
- Submodule URLs (`.gitmodules`) are the GitHub forks, ``https://github.com/seantempesta/<lib>`` (owner, 2026-10-23); `persistent-sorted-set` and `jolt-overlay` are private, so a fresh clone needs the owner's GitHub login. A fork's branches are pushed there, never `main`, never forced. The rule comes from DECISIONS, "Libraries on Jolt". Every pinned commit is pushed to its GitHub fork on a `jolt/<topic>` branch by whoever made it, with no ask (owner, 2026-10-23), no force, never the fork's main.
- Pin bumps happen only in root's merges into main ([merging](merging.md)). `git -C <dir> log --oneline -- reference-code/<lib>` in this repository lists each bump with what it brought.

## jolt

`reference-code/jolt` pinned at `6e604ac8`, the tip of ``jolt/emit-string-literals``, on `eba8826a`, the tip of ``jolt/image-fn-forms-opt-in``, on `2a6ae8f8`, the tip of ``jolt/arrays-compare``, on `86f49de8`, the tip of ``jolt/analysis-lookups``, on ``jolt/record-field-binds`` `0d39edc3`, on `e0351af8` (``jolt/global-ids-pin``: ``jolt/pin-2026-10-23`` `f05de7db` with ``jolt/hashset-bulk`` `ce384f07` merged in).

- Fork repo: ``https://github.com/seantempesta/jolt``. Upstream: ``jolt-lang/jolt``. Fork: ``seantempesta/jolt``.
- Base: `f714b0ed`, upstream main (nightly + 10), the merge-base with upstream's main.
- Branch chain: ``jolt/main`` `1fad8b83` → ``jolt/j2-on-main`` `fc5d2f82` (merges ``jolt/cache-records-relative``) → ``jolt/eval-outside-build`` `d7ec259b` → ``jolt/runtime-fingerprint`` `60961b84` → ``jolt/fork-carriers`` `4342b73c` → ``jolt/thread-ticks`` `ed1e37a1` + ``jolt/temp-file-unique`` `a2fec377` (merged as `21e91705`) → ``jolt/aot-cache-hit`` `5edda163` → ``jolt/pin-2026-10-23`` `f05de7db`.
- Ours, on ``jolt/main`` `1fad8b83`:
  - `28970daf` perf: deftype `set!` by a memoized per-type flag and direct index. Generic (``upstream/deftype-field-access``).
  - `f944a449` perf: deftype method bodies read and write fields by slot index. Generic (``upstream/deftype-field-access``).
  - `971d6d0b` perf: host method sites answer `valAt`/`get`/`count`/`nth` on native persistent colls directly. Generic (``upstream/host-site-coll-reads``).
  - `3659e27a` chez-patches 0006: a forked child re-protects MAP_JIT code before returning to Scheme (macOS arm64). Generic (``upstream/fork-reprotect-jit``).
  - `40028e79` chez-patches 0007: static code leaves MAP_JIT after a static collect, so fork children share it copy-on-write (macOS arm64). Generic (``upstream/static-code-cow``; also a Chez candidate).
  - `00d090a5` Flow `stop` awaits every proc's exit; Flow's own go blocks run on fibers. Jolt for now: upstream only once proven.
  - `9946e2fa`, `765cd5a0` GC policy sizes the nursery bound and growth allowance from the collectable heap, not the static generation; its gate asks that `System/gc` shrank the allowance. Generic.
  - `2dc7bca1` library seam arms declare their claim (`{:kind :table}`); UUID and Class on the eq/hash fast paths. Generic.
  - `1b0d7719` `instance?` arms may claim class names (`{:classes #{...}}`). Generic.
  - `414ce2b6` Flow `update!` changes a running flow in place. Generic.
  - `7706dc1b` Flow `update!` plans only changed definitions and reroutes only changed conns (add+remove one proc at 1,000 procs: 18 MB / 4.8 ms → 0.4 MB / 0.46 ms). Generic.
  - `33cb3b3a` Flow's launcher carries its step Var as metadata, so `update!`'s fingerprint needs no datafy. Generic.
  - `1a00a128` the readable printer writes every level into one string port. Generic.
  - `93af2568` `go` runs on fibers by default (`*go-backend* :fiber`). Jolt (a default for our line).
  - `e95ca73a` sorted-coll predicates read a host table's tag once. Generic.
  - `1cae8e72` an array built from an array of its own kind copies the backing. Generic.
  - `cf6e0866` `jolt.async.remote`: core.async channels whose other end is another OS process. Generic (``upstream/remote-chan``).
  - `9d423a92`, `f0bd21be`, `d516c9b7` `jolt.async.remote`: `close!` never touches a descriptor its pumps closed; a passed socket is held until the receiver answers; generic wording. Generic.
  - `a036858d` remote ends' buffers start at 4 KB and grow. Generic.
  - `dd7b18da` build smoke: bignum literals round-trip in a built binary. Generic.
  - `55508e3c` the runtime fingerprint names the Chez boots it runs on. Generic.
  - `c22cb9c0`, `00a3b321` `try`/`catch` on Chez: a one-shot escape and a hand-pushed handler instead of `guard`, emitted at the try's site so the frame keeps its line. Generic.
  - `9e42a0d8` `jolt.analyzer/*unit-tap*` receives every top-level unit the runtime analyzes; `defprotocol` defines its value whole. Generic.
  - `90b1e924` runtime threads carry names; chez-patches 0008, `(cs)collect_sweepers` fixes the GC's sweeper pool. Generic.
  - `c508734d` a `put!`/`take!` that cannot complete at once is a waiter on the channel, its callback run on a fiber. Generic.
  - `6b862dff` Process: the exit status is recorded under the process mutex. Generic.
  - `02f97367` `run-interruptible` takes a tick budget. Generic.
  - `fce0f40c` a built binary binds `*command-line-args*` to its argv while its namespaces load and `-main` runs. Generic.
  - `16a1c18e` `jolt build`: an app that keeps the compiler carries jolt's install sources (one gzip-per-file blob), so it requires a jolt namespace from any cwd (binary +0.91 MB, RSS +1.9 MB). Generic.
  - `f8dced2c` the AOT cache keys a project with data readers the same on every run (unchanged `bin/build seon`: 0/265 → 217/217 hits). Generic.
  - `be2495af` inference: an element read off a typed vector may be nil. Generic.
  - `4ebd36eb` deps: a ``:local/root`` that does not exist is unresolved, as tools.deps refuses it. Generic.
- Ours, above ``jolt/main``:
  - `f1e21977`, `2db1551c` AOT cache: one cache shared by checkouts of a project; a `.res` sidecar names each read relative to the project root (the root itself `.`), a listed directory is a read, sidecars by temp+rename. Generic. Known: one sidecar holds the last compile's names, so checkouts whose read sets differ take turns missing.
  - `d7ec259b` a build's own evals compile outside its unit, so an eval at macro expansion no longer references the build's unbound defs. Generic.
  - `60961b84` `jolt.host/runtime-fingerprint`: the runtime's identity as its compiled units are keyed. Generic.
  - `6871f997`, `e469164c`, `52b50504`, `4342b73c` `jolt-after-fork-child!`: a forked process drops the bookkeeping of the runtime threads fork(2) did not copy (carriers, timer and its deadlines, shutdown watcher, thread tables) and starts no thread; fork-child-test proves a parked go, a timeout and a double fork's grandchild ([failure points](failure-points.md)). Generic.
  - `ed1e37a1` one tick count, owned by the timer: `jolt-set-timer!` is the one arm site, `jolt.host/ticks-of` and `jolt.host/ticks` read it, and a tick budget covers all the work in its extent, nested bounded runs included ([cost](cost.md#what-is-measured)). Generic.
  - `a2fec377` `File/createTempFile`: an exclusive create, the name from the pid and a counter. Generic.
  - `5edda163` the AOT cache's readers digest is folded once per key, not into every namespace digest, so a checkout's cache hits (a start 7.3 s to 0.6 s). Generic.
  - `3a3e35e6`..`0d39edc3` a record method reads only the fields its analyzed body reads: deftype and defrecord bind every field as Clojure's do (defrecord through `__record-get`, get under its own name, which `--opt` still lowers to the shape's accessor), and Jolt's fold pass (its drop-unread-fields) drops a field bind nothing reads (a record method read all of them on every call, ~6 ticks a field: 158 ticks a protocol call on Datahike's 21-field DB, ~20-25 now); `update` has clojure.core's fixed arities (656 B to 240 B on that record). Proofs: Jolt's unit suite `field-binds`, `seon.record-cost-test/a-protocol-call-costs-what-its-method-reads`. Generic.
  - `4af66adf` analysis allocates the IR, not its lookups: `*unchecked-math*` and `*invoke-rewrite*` read through their var cells, not an "ns/name" string probed in the var table on every list form; a macro call walks its call form for the reader's set forms only when the top-level form holds one (`form-holds-set-form?`, scanned once), so nested macros are linear (100 nested whens 1.69 MB before); a symbol resolved once (`resolve-global` answers `:macro`); `&env` kept as locals are added. Seon's import of src and test, warm: 479M to 388M ticks, 2,974 to 2,450 MB. Proof: Jolt's `make analyzealloc` (bytes a node kind, and the behaviour kept: sets reach macros as sets, `&env` names the locals in scope). Generic.
  - `94e10e1a`, `9e5f8174`, `86f49de8` `tree-seq` walks with a list of the sibling seqs still to visit, one self-recursive fn (so a tree-seq value still writes into an image), not mapcat per level: a read form 444 to 272 B a node on bin/jolt, 1,093 B before in a release runtime. Proof: Jolt's `make treeseqcost` (bytes a node, order and laziness against Clojure's definition). Generic.
  - `2a6ae8f8` `java.util.Arrays/compare` (nil first, the first differing element's sign, then the length): Datahike orders `:db.type/bytes` values by it, so a second vector written on an entity threw ([vectors](vector.md)). Proof: Jolt's unit suite `arrays-compare` (test/chez/unit.edn), `seon.vector-test/a-text-is-embedded-once`. Generic.
  - `8a6bfc5b`, `6e604ac8` the emitter writes a string literal in one indexed pass (`chez-str-lit`, `munge-chars`); its proof asserts the emitted text (named and hex escapes, one past the BMP), not only the round trip. Generic.
  - `686aff7d`, `eba8826a` image fn-form registration is a compile option: `*compiler-options*` `{:image-fn-forms false}` compiles a form without registering its fn literals' sources for a state image (no rendered source, no read-back, no `image-register-fn-form!`, no maker); absent or true, unchanged. `JOLT_COMPILER_OPTIONS` (an EDN map) sets `*compiler-options*`' root at start, as JVM Clojure reads `clojure.compiler.*`; Seon sets it in `bin/env`. A defn of n fn literals: ~1.24 MB a literal registering, ~478 KB not. A value that is no EDN map stops the start, naming the variable; a `binding` of `*compiler-options*` replaces the map, so merge to keep the option. Proofs: Jolt's `make imagefnforms`, `seon.program-cost-test/a-defn-compiles-within-its-estimate`. Generic.
  - `ce384f07` `java.util.HashSet`'s bulk Collection methods (`addAll`, `removeAll`, `retainAll`, `containsAll`): `datahike.api/datoms-diff` threw on any two trees that partly share ([storage](storage.md#merges-read-what-changed)). Generic.
- Pinned: ``jolt/num-equiv-two-args`` `67e7bf3e` (`2a71fa87`, `67e7bf3e`), on `07d9655d`: `(== a b)` on two plain numbers allocates nothing. `clojure.core/==` is `jolt-num-equiv`, called through its var wherever the numeric pass cannot type the operands (a comparator's `(== 0 c)`; Datahike's `combine-cmp` after every field of every datom comparison); as a rest-args procedure it consed its two arguments and walked them, 32 B a call (64 for two flonums). A 2-arity compares two plain numbers; anything else takes the rest-args body. A transient index insert 2.86 to 1.88 KB with Datahike `d4811ddf` (task datahike.transaction/an-index-insert-allocates-nothing-extra). Proof: Jolt's `make numequivalloc` (bytes a call, and the 2-arity against the rest-args body pair by pair). Generic.
- Under it, ``jolt/system-exit-runs-no-finally`` `07d9655d`, on `10cd561f`: `System/exit` halts with its status and runs no `finally`, as the JVM's `Runtime.exit` (the shutdown hooks, the console flushed, `_exit`, from any thread); on the boot thread it went through Chez's exit, whose base handler unwinds every `dynamic-wind`, so a `finally` around the call ran and one that exited again replaced the status (a Seon agent's `(System/exit 3)` reached root as 1, its forked-process boundary's; `host/chez/system-exit-smoke.sh`). Generic.
- Under it, ``jolt/main-carrier-returns`` `10cd561f` (``jolt/a-read-scope-holds-across-a-park`` `7edde382` merged onto `40e2333f`): `394aa223`, `ec199ced`, `dfdc33ef` every host winder whose cleanup closes or frees what its body uses (a read scope's jar readers, `zipdir-read`'s port, `zip-inflate-raw`'s zlib stream, a copy's source and file, the iconv, `tm` and Windows buffers) runs at the real exit, never at a park or preemption: its before-thunk is `jolt-finally-in`, which a park drops (a fork server reading its deps' jars at boot, a preemptible fiber, had a reader's port closed under it: `set-port-position!: not permitted on closed port`); `zip-index-preempt-test` preempts `zipdir-read` at each quantum through it (red on `d598fc04` at the same quanta every run); `scheme-adapter-runtime.ss`'s two buffer winders load before the marker exists (task jolt.async/the-adapter-runtimes-buffers-are-freed-at-the-real-exit); `with-port` closes through a guard, not a winder. Generic.
- Under it, ``jolt/main-carrier-returns`` `40e2333f` (`3f58bd5e` merged onto `d598fc04`): `5a1ecf16` `main-carrier-run!` returns into an ordinary process (the pool grows around carrier 0 to the count in force before the run, every carrier gets its thread, the timer thread starts when a deadline is pending, the poller's thread takes over its kqueue), so root boots on its main carrier, forks its fork server there and then starts its threads (```seon.root/boot!```); `84479502` a run from a fiber already on the main carrier runs in place (an agent of a main-carrier fork server); `754e9b3f` a deadline another thread sets ahead of the rest wakes the main loop (it fired with the later one: `main-carrier-wake-test.clj` r4, 29,962 ms before); `c7fb2ff3` `fork-child-test.clj` forks from the main carrier (a fork beside live runtime threads is refused whenever one is active at that instant); `3f58bd5e` the poller thread a return starts takes the deletes the main carrier's rounds carried. Under it:
- Under it, ``jolt/fiber-dump`` `d598fc04`, on `3b5eb591` (`b4d4aeea` merged with `2eda21da`): a fiber dump. `(jolt.host/fiber-dump)` answers, and SIGINFO (`kill -INFO PID`, ^T) writes to stderr, every live fiber: its state, carrier, the object it is parked on, its wait state and ticks, and for a parked fiber the frames it will resume in (each frame's name, the vars it holds, its source form). A lock-free weak list holds every fiber from its spawn (+3% a spawn, 226 to 233 ms for 100,000). The poller's kqueue also waits on SIGINFO, so a main carrier idle in `kevent` wakes and dumps. It cannot reach a thread in a foreign call, a condition wait (a promise's deref) or a lock, which run no event check ([failure points](failure-points.md)). macOS only. Proof: a unit row (a fiber parked in a channel take is listed, parked on its alt-handler, with its frames). Generic.
- Under it, ``jolt/zip-errors-reach-the-caller`` `2eda21da` (`f3502fd8` merged with `e33c6450`): `zip-archive?` raises a failure that says nothing about the file.
- Under it, ``jolt/isfile-regular`` `e33c6450`, on `f561902a`: `File.isFile` is true for a regular file only, as on the JVM (it was true for a FIFO, a socket or a device, so a scan that slurped every `.isFile` file blocked forever on a FIFO); Chez's `file-regular?`, which `Files/isRegularFile` already used. Proof: a corpus row from JVM values. Generic.
- Under it, ``jolt/deftype-protocol-dispatch`` `f561902a`, on `5cc7458e`: Clojure's reference and sorted fns reach a deftype, record or reify through the interface it declares, as on the JVM, and its sorted map keeps the JVM's identity. `sorted?`, `subseq` and `rsubseq` ask `clojure.lang.Sorted` (its comparator, entryKey, seqFrom and seq), and `(.seq sorted-map false)` walks it in reverse; `reset!`, `swap!`, `compare-and-set!`, `swap-vals!`, `reset-vals!`, `add-watch`, `remove-watch`, `set-validator!` and `get-validator` call the declared `IAtom`, `IAtom2` and `IRef` methods (an atom's own path is unchanged), and on anything else raise the JVM's cast naming the interface; in a deftype method, a mutable field reads live inside a map or set literal, and every binder (destructuring ones too, in `let`, `for`, `doseq`, `dotimes` and the rest) shadows it; `assoc` into a sorted map replaces a value `=` to the old one (`PersistentTreeMap.assoc` keeps the map only for the same value), so an identity cache over it sees the new one. The render build's server-side ratom and ctx-atom's identity cache need these. Proof: 17 corpus rows from JVM Clojure values and 5 unit rows; Jolt's gates the change reaches, before and after. Generic.
- Under it, ``jolt/timer-throws-reach-their-owner`` `5cc7458e` (`f7871f05` merged with `2ceb680e`): a deadline's thunk that throws reaches its owner, never silence: on a main carrier it ends `main-carrier-run!` with its cause, on the timer thread it goes to the uncaught-exception handler and the thread lives on. On ``jolt/suggestions-for-public-2`` `2ceb680e`, on ``jolt/load-ns-frame`` `f5122c6a` (a load binds `*ns*` for its own extent), on `7cfc3e80`: `jolt.analyzer/suggestions-for` is public with its docstring (seeds re-minted), so Seon's one answer for a missing name (```my.agent/no-var```) ranks the nearest names as the analyzer's did-you-mean does, with no second edit distance in src.
- Under it, ``jolt/main-carrier-wakes-kevent`` `7cfc3e80`, on `e1b72c1a` (the agent loop's main carrier, whose commits this list does not yet name):
  - `008d7bab`, `04dfc4a6`, `7cfc3e80` the main carrier runs a fiber another thread queues whatever its loop waits in: the io poller's kevent (its wake pipe, written after the carrier's mutex is released, since the park path takes that mutex under the poller's lock) or a timer (the carrier's condition, not a sleep). Unfixed, an agent's test fork serving HTTP never answered (S1, live agents 2026-10-25). Proof: `test/chez/main-carrier-wake-test.clj` (red on e1b72c1a: a hang; the timer case ran at the timer, 30 s). Jolt (the main-carrier mode is ours).
- ``upstream/*`` branches in the fork repo: `arraycopy-object-block`, `deftype-field-access`, `fiber-park`, `fork-reprotect-jit`, `host-site-coll-reads`, `remote-chan`, `static-code-cow`. None is pushed or opened: they exist only in the main tree's local clone of the fork (the module under `.git/modules/reference-code/jolt`), cut on jolt-lang/jolt main `8bebf786`. Before one is opened: the gate sweep of `fork`, `slots` and `static`, `make fibers` re-run alone on arraycopy-object-block (a new untriaged red under load), a Gambit check of deftype-field-access, a non-root Linux sweep, and a rebase on upstream's current main. static-code-cow is not for upstream (jolt itself never forks, and under the hardened runtime code outside MAP_JIT needs an entitlement or the process is SIGKILLed): it stays in our patch set. Of the Chez patches, 0006 belongs upstream (no fix in cisco/ChezScheme or Racket CS), 0007 only as an issue first. Drafts and reproductions: ``notes/upstream/`` in the tag ``history/prototype`` (``git show history/prototype:notes/upstream/<file>``).
- Open: the fork repo's ``jolt/main`` is at `44c9574a` (``jolt/load-form``, which Seon does not use; not in the pin), and `d7ec259b`..`f05de7db` are only in the submodule clone, not yet in the fork repo. ``jolt/main`` should be moved to the pin's line.

## chez

`reference-code/chez` pinned at `fa6a4c73`, the tip of ``jolt/collect-uncounted`` (a collection, its handler and the runtime's stacks are not charged to the measured thread: `002b57ad`, `c946398d`, `89b4082a`, `fa6a4c73`), on `80e4b9f`, the tip of ``jolt/signal-wakes-wait``.

- Fork repo: ``https://github.com/seantempesta/ChezScheme``. Upstream: ``cisco/ChezScheme``.
- Base: `e95a7ef`, Chez Scheme v10.4.1.
- Branch chain: ``jolt/main`` `fdd1e1e` (= ``jolt/gc-sweepers``) → ``jolt/thread-bytes`` `d19eb3b` → ``jolt/signal-wakes-wait`` `80e4b9f`.
- Ours:
  - `359f151` Jolt's chez-patches 0006 (`reference-code/chez/c/thread.c`) and 0007 (`reference-code/chez/c/segment.c`, `reference-code/chez/c/gcwrapper.c`, `reference-code/chez/c/externs.h`). Generic (Chez candidates; 0007 macOS arm64 only).
  - `8741ee8` Jolt's chez-patches 0001–0005 unchanged (lock-free thread activation, oversize chunk spare segment, boot load in place, vfasl string sharing, same-device compares both handles), as Jolt's CI builds Chez. Generic.
  - `4aa052d` vfasl: a negative bignum's bigits are its magnitude's. Generic.
  - `1d686b2` vfasl: an fxvector's header carries its length. Generic.
  - `c559ff3`, `0fd14b5`, `556ede5` mats: vfasl round-trips of a non-empty fxvector and of negative bignums. Generic.
  - `fdd1e1e` `(cs)collect_sweepers` fixes the parallel collector's sweeper pool at n threads. Generic.
  - `2986324`, `275aa26`, `d19eb3b` a per-thread mutator byte count, `(cs)thread_bytes_allocated`, counting every mutator `find_room` and not the collector's or the runtime's own allocation. Generic.
  - `80e4b9f` a pending signal wakes a thread in `S_condition_wait`, so its handlers run before the caller waits again. Generic.
- Open: the fork repo's ``jolt/main`` is at `cd1e16c`; `fdd1e1e`..`80e4b9f` are only in the submodule clone, not yet in the fork repo.

## datahike

`reference-code/datahike` pinned at `219f57f6`, the tip of ``jolt/diff-test-on-jolt-file-store`` (Datahike's `datoms_diff_test` requires `konserve.jolt.store`, so its restored histories run on Jolt's `:file` backend: without it every restored history threw on konserve's JVM store and the property reported the throw as a counterexample; the diff was exact; test only), on `416bdc63`, the tip of ``jolt/a-diff-records-its-reads`` (`65dad766`: `psi/diff` records its `[from to]` range as `-slice` does, so a read built on `d/datoms-diff` under `d/with-reads` is touched by a datom added in the diffed range: `[:a attr]`, or `:any` with no attribute; it recorded nothing, and a render over a diff kept its old answer. Proofs: `datahike.test.datoms-diff-test/a-diff-records-the-range-it-read`, `seon.render-test/a-render-over-a-diff-runs-again-when-the-diff-moves`. Generic), merged with `0d6b58c0`, the tip of ``jolt/compare-value-no-try`` (`d4811ddf`, `0d6b58c0`: `compare-value` on Jolt enters no `try` for two values of one scalar kind, 240 B a comparison that reaches the value before; `compare-data`'s helpers are top-level fns: n refs on one form 38 to 17 KB a datom at 1,000, `seon.db-test/a-value-costs-its-index-inserts`; a type test beside the `try` until Jolt's `try` costs nothing entered, task jolt.backend/a-try-entered-allocates-nothing), on `cc614f4e`, the tip of ``jolt/attribute-compare-without-try`` (`cmp-attr-nil` compares attributes with no `try`), on `e5a55664`, the tip of ``jolt/time-pred-order``, on `872bfa03`, the tip of ``jolt/pull-reverse``, on `336de2ab`, the tip of ``jolt/tx-data-retracts``, on `3fe48c96` (``jolt/multival-lookup-ref``), on `6dc9ce64`, the tip of ``jolt/with-reads`` (`6689077e` records the index ranges a read scanned: `with-reads`, `reads-index`, `datom-keys`, `reads-touched?`; `6dc9ce64` the collector is an atom), on `698a2fa`, the tip of ``jolt/a-predicate-that-throws-is-an-error`` (`6b668f0`, `25cc875`, `698a2fa`), on `d89be47` (the tip of ``jolt/datom-cost``: `6db7188` and ``jolt/nested-call-refused`` (`ae35add`, `dda24c0`), each on ``jolt/release-drains`` `bae7c3e` (``jolt/in-scalar`` `1880fcc` merged into it; both on ``jolt/global-ids`` `b4596cf`, ``jolt/gc-cache-epoch`` `700c473` merged in)).

- Fork repo: ``https://github.com/seantempesta/datahike``. Upstream: ``replikativ/datahike``.
- Base: `ad9fc97` "baseline", a history-less snapshot of Seon's fork ``seantempesta/datahike`` `ccba220b` (unknown: that commit is not in the repo, so the snapshot cannot be checked against it).
- Branch chain: ``jolt/main`` `fde9f89` → ``jolt/run-ahead`` `fe50764` → `jvm` = ``jolt/eid-lease`` `4b93a1b` → ``jolt/values-out-of-line`` `594815d` → ``jolt/deref-config`` `fd0c065` → ``jolt/global-ids`` `0783e8b`.
- Jolt-first (owner, DECISIONS "Storage is tuned for our workload"): the `jvm` branch freezes `4b93a1b`, the last commit that keeps the `:clj`/`:cljs`/Fressian code; the line after it drops that code from each file a change touches, each deletion its own commit.
- Ours, on ``jolt/main``:
  - `a6b9b86` `:jolt` reader conditionals and Jolt index and node-cache files. Jolt.
  - `8efacfb`, `8132b95` an interim index, then dropped for the Jolt persistent-sorted-set translation. Jolt.
  - `70ef3a3` EDN roots and nodes over StringSerializer. Jolt.
  - `8bd49ad` `boolean-cmp` via `compare`. Generic (works on the JVM too).
  - `9c38f95` Fressian read handlers for a JVM-written store (Jolt), writer start poll timeout 0, schema attribute classes as sets.
  - `fde9f89` the `:sync` writer with `transact-batch!` group commit. Jolt (our design).
- Ours, above ``jolt/main``:
  - `9cd6e7b` a `:sync` writer's shutdown commits its run-ahead value before it answers, so a release never drops a write applied and not committed. Jolt (our writer).
  - `b6ae683` `database-exists?` delivers its answer by calling the throwable-promise on Jolt (put! on it threw in the fiber and the caller hung). Jolt.
  - `deee8c2` freshly written index nodes are not put in the node cache. Generic.
  - `21bc8cd`, `0f1b619` the query result cache is off by default; its tests bind it on. Jolt (measured on our workload).
  - `c0f9149` the branch roster is a konserve append log, O(1) per `branch!`. Generic.
  - `7de2e94` `branch!` from a compact head writes the same pointer under the new name. Generic.
  - `5ed8d8f` `gc-storage!` `:sync? true` runs the whole collection on the calling thread. Generic.
  - `032552b` run-ahead writes at a `:sync` writer's serial caller (`apply!`, `pending?`, `commit-pending!`). Jolt (our design).
  - `fe50764` `merge-db!` on a `:sync` writer applies on the calling thread. Jolt.
  - `4b93a1b` a process holding a block of entity ids mints inside it (`set-eid-ceiling!`, ``:entity-id/lease-spent``); deleted by `be25be4`. Generic.
  - `4ae0593` large values out of line in `CachedStorage` (`:index-config :value-out-of-line-bytes`; GC's mark keeps them) ([storage](storage.md#values-out-of-line)). Generic.
  - `d904dea` ``:db.type/data`` and a total order on plain data ([storage](storage.md#plain-values)). Generic.
  - `594815d` a `:sync` writer runs `gc-storage!` as a collection, not a transaction ([storage](storage.md#collection)). Jolt (our writer).
  - `fd0c065` `deref-conn` keeps the connection's own config, so a moved store's db names where it is now. Generic.
  - `be25be4` a store's ids: entity ids and transaction ids each issued once across every branch (`datahike.ids`, `set-ids!`, the writer's `with-store-ids`); a transaction takes its id when it begins ([storage](storage.md#ids)). Generic.
  - `5c869bf`, `508918c`, `0783e8b` content entity ids: `:db/contentId`, an identity whose value names its entity (`content-eid`, `content-tempids`, `:entity-id/collision`); the partition `[1, tx0)` counter ids, `[tx0, 2^40)` tx ids, `[2^40, 2^60)` content ids; an existing identity may gain it ([storage](storage.md#ids)). Generic.
  - `deaf43a` a consumer group's probe-driven seek decision compares the probe set with its scan op's estimate, not the group's output estimate: a group probed by one entity scanned its whole attribute (task datahike.query/a-probed-entity-is-sought-not-scanned). Generic.
  - `7de92bb`, `a9adc83`, `1880fcc` `probe-driven-threshold` 18, Jolt's measured break-even (a probe's seek and merge ~5.6k ticks, a scanned datom ~316), not the JVM's 2500 (a JVM build must set 2500); `datahike.test.query-planner-test/test-a-probed-group-answers-the-same-either-side-of-the-seek-threshold`. Jolt.
  - `6db7188` a datom's add path costs its index inserts: a card-one attribute's current datom by one `:eavt` descent (`di/-lookup`, `dd/cmp-datoms-ea`), not a slice's seq; the temporal indexes untouched by a first value; no per-datom record copies, closures or concat the op does not need. A plain datom 12.5k ticks and 18.2 KB before (with Jolt `e0351af8`), 6.7k and 11.1 KB after, its floor ~6.8k and ~10.5 KB (`seon.db-test/a-datom-costs-its-index-inserts`). Generic.
  - `336de2ab` a card-one value replaced is retracted in the transaction report: `transact-report` replaced it in the index (`with-datom-upsert`) but put only the new datom in `:tx-data`, so a reader of a transaction's datoms never saw the old value go (a ref moving a turn to another run left it under the old one in `@ctx`, [ctx](ctx.md)); the report now carries `[e a old tx false]` before the assertion, as DataScript's does. Upstream's behaviour too (replikativ/datahike main `bf1dfdfa4e`). Proofs: `datahike.transaction-test/a-replaced-value-is-retracted-in-the-report`, Datahike's listen test, `seon.ctx-test/ctx-is-the-record-after-every-transaction`. Generic.
  - `e5a55664` a read through a time predicate (as-of, since) keeps index order and stays lazy: `post-process-datoms` grouped the datoms by `[e a]` into a map, so past eight entities `datoms`, `seek-datoms` and `rseek-datoms` answered in the hash's order (ten children as of the sixth: `[7 9 6 12 15 11 13 14 10 8]`) and an `rseek` range was read down to the index's start; each `[e a v]`'s datoms are adjacent in every index, so `current-datoms-xform` takes its latest operation at or before the time point as the datoms stream by, and `tx-kept?` checks a transaction's `:db/txInstant` once as its datoms arrive. `assemble-datoms-xform` and pull's `:reverse` sort go. Datahike's own suite answers the same in every namespace before and after; proof `datahike.test.time-predicate-order-test` (task `datahike.db/a-time-predicate-read-keeps-index-order`). Generic.
  - `872bfa03` a pull attribute's `:reverse` option reads it newest first: `[attr :limit n :reverse true]` (Datomic's `index-pull` word) reads the attribute's datoms last in index order first, a reverse ref by its referrers' ids (its `:limit n` the n newest), a card-many attribute by its values, in `d/pull` and in `q`'s pull (now compiled as `d/pull` compiles it). The pull parser (datalog-parser, a jar) knows only `:as`, `:limit` and `:default`, so `compile-pull-spec` lifts `:reverse` off before parsing; a pattern without it parses as given. A current db reads by `rseek-datoms` and stops at the prefix, so the newest n cost is flat in the collection (`seon.db-test/the-newest-n-cost-what-they-hold`); an as-of or since db reads by `rseek-datoms` the same way since `e5a55664`. Datahike's own suite on Jolt (one process a namespace, kaocha's `*stacktrace-filters*` var stubbed for `datahike.test.core-test`) answers the same in every namespace before and after; proof `datahike.test.pull-reverse-test`. Generic.
  - `ae35add`, `dda24c0` a nested call in a predicate or function clause, `[(f (g ?x))]`, is refused when the query is parsed, naming the clause (`datahike.query/refuse-nested-calls`, `:query/nested-call`): no engine evaluates it, the parser read it as a constant list and a predicate throwing on it read as false, so the query answered nothing (Datomic refuses it; upstream answers the empty set). A quoted form passes only as `q`'s query (`'#{a}` elsewhere is the list `(quote #{a})`, refused). Proof: `datahike.query-test/a-nested-call-in-a-predicate-is-refused`. Generic.
  - `6b668f0` a where clause's call that throws is an error of the query (`:query/call-threw`, its clause, its values, the call's throw as cause); before, a predicate's ClassCastException or IllegalArgumentException read as false, and the query answered nothing. Proof: `datahike.query-test/a-predicate-that-throws-is-an-error`. Generic.
  - `25cc875` the error carries the call's arguments, not the whole row, and a typed error the call threw passes on as is; the fused direct-rel, columnar and secondary-index paths, which read every Exception as "not applicable" and fell back, rethrow it (`datahike.query.analyze/call-threw?`). `<` `>` `<=` `>=` take two values without allocating. Generic.
  - `698a2fa` the built-in `<` `>` `<=` `>=` keep Clojure's semantics (root's ruling, 2026-10-23): a number or nil compares as `clojure.core/<`, any other value as `compare`, so values of different kinds throw and the query raises `:query/call-threw`. The `CollectionOrder` protocol they replace dispatched on the first value's type. The planner pushes an ordering comparison into a range scan only when the store's schema is written and the bound fits the attribute's `:db/valueType` (`datahike.query.analyze/of-the-values-kind?`): then no value of another kind exists for the range to drop, and the scan answers what the comparison answers. Generic.
- Open: the fork repo's ``jolt/main`` is at `fe50764`; `4b93a1b`..`fd0c065` are only in the submodule clone, not yet in the fork repo.

## persistent-sorted-set

`reference-code/persistent-sorted-set` pinned at `1cae7f2`, the tip of ``jolt/forward-cursor-comparator`` (on ``jolt/main`` `9cce727`).
- `jolt/gc-cache-epoch` `700c473` (worktree session store-gc, 2026-10-23; on the pin `fd0c065`, for root to merge onto `jolt/main`): a value key held in the node cache is trusted only in the collection generation it was found in; `gc-storage!` counts up `datahike.index.interface/collection-key` after its sweep ([storage](storage.md#collection)). Proof: `datahike.test.gc-value-key-test` (on Jolt, LMDB), red (`:value-not-found`) -> green.

- A1 (2026-10-20): `a1/jolt-main` = `jolt/main` `fde9f89` (which already holds F2's f2-final `writer.cljc` byte for byte) + `jolt/node-cache-reads` `deee8c2` + `jolt/qcache-off` `21bc8cd` + `jolt/branch-pointer` `7de2e94` (on `jolt/branch-roster` `c0f9149`). `jolt/main` itself is checked out in L1's worktree `l1/qc/dh-main`, so root moves it. Versioning tests: 13 tests, 49 pass, 3 fail, 4 error; `jolt/main` is 1 fail, 4 error. The 2 new failures are `commit-as-db-preserves-only-attached-generation-identity`'s query-cache evidence assertions (miss-owner, hit), which `qcache-off` makes false by design.

- Fork repo: ``https://github.com/seantempesta/persistent-sorted-set``. Upstream: ``replikativ/persistent-sorted-set`` 0.4.137.
- Base: none; written fresh, with no upstream history (root commit `d397c58`).
- Ours: `d397c58` the JVM core as Jolt deftypes; `d565937` EDN node forms; `df6735e` `Arrays/sort`; `9cce727` the node emitter writes `#pss/leaf` and `#pss/branch` without the generic printer; `1cae7f2` `forwardCursor` takes a `java.util.Comparator` (every planned `d/history` query threw). Jolt.
- It is an overlay, not a whole library: `deps.edn` puts its `src` on `:paths`, ahead of the mvn 0.4.137 jar Datahike depends on, which still supplies the other namespaces.

## konserve

`reference-code/konserve` pinned at `84d8bc0`, the tip of ``jolt/lmdb-env-release`` (on ``jolt/lz4-ffi`` `3dbd4d5`).

- Fork repo: ``https://github.com/seantempesta/konserve``. Upstream: ``replikativ/konserve``.
- Base: `4991e67` "baseline", a history-less snapshot of Seon's fork `af35a97` (unknown: that commit is not in the repo, so the snapshot cannot be checked against it).
- Branch chain: ``jolt/main`` `849d87d` → ``jolt/atomic-append`` `b110d99` → ``jolt/lz4-ffi`` `3dbd4d5`.
- Ours, on ``jolt/main``:
  - `88753d6` Jolt reader conditionals in the serializers. Jolt.
  - `33f81d6` StringSerializer writes and reads UTF-8 EDN through the streams. Generic (fixes a JVM ClassCastException).
  - `4f50c49`, `4ae0b8c`, `e186f51` a POSIX filestore and an LMDB store over `jolt.ffi`, dispatched from `konserve.store`. Jolt.
  - `10e47f6` the lock token channel is `(chan 1)`, a workaround for Jolt's `poll!` missing a pending put. Jolt.
  - `b19e78f` `konserve.jolt.bin`, a measured codec that is not wired. Jolt (candidate to delete).
  - `f7cdc98` FaslSerializer (header byte 4) through `konserve.jolt.fasl`. Jolt.
  - `14a376c` `sweep!` with ``:konserve.gc/sync-io?`` lists and deletes through the store's synchronous operations. Generic.
  - `39ce147` `konserve.jolt.lmdb-ffi/compact-copy!`, a compacted copy of the env that returns a sweep's freed pages. Jolt.
  - `849d87d` jfasl: a stable tagged format in place of raw Chez fasl. Jolt.
- Ours, above ``jolt/main``:
  - `84d8bc0` an LMDB env lives as long as a store of the process holds it (acquired by `connect-lmdb-store`, given back by `-release-store :lmdb`, closed by the last): a store made again at a removed path opens its own files. Generic.
  - `b110d99` an append is one transaction on a store that runs them (`PTransactBackingStore`, the LMDB store's one write transaction), so two processes appending one log never lose an element. Generic in shape, LMDB-only in effect.
  - `37a28e4`, `7c462af` `konserve.jolt.lz4`, LZ4 frames through liblz4's frame API over `jolt.ffi` (the JVM Lz4Compressor's format), loaded from `JOLT_LZ4_LIB` ([storage](storage.md#compression)). Jolt.
  - `3dbd4d5` `konserve.jolt.zstd` and `ZstdCompressor` (header byte 2, `:zstd`), libzstd's simple API, one frame per value at level 3, loaded from `JOLT_ZSTD_LIB` ([storage](storage.md#compression)). Jolt.
- Open: `konserve.jolt.fasl-store` names persistent-sorted-set node types; its cleaner home may be the persistent-sorted-set fork, with konserve's serializer taking the freeze and thaw from the store.
- Open: the fork repo's ``jolt/main`` is at `849d87d`; `b110d99`..`3dbd4d5` are only in the submodule clone, not yet in the fork repo.

## malli

`reference-code/malli` pinned at `26aeb44c`, ``jolt/total-arms-unguarded``, on `0762fb6b` (``jolt/mg-seed``: mg/check takes its `:seed` and returns it on failure), on top of `5a830e14` (``jolt/main``, ``jolt/instrument-arity``).
- `bf35c354`, `26aeb44c` `-instrument`: an arm whose validator cannot throw on a realized value (`-total?`: the built-in types that answer for any value, malli's own predicate schemas but `pos?` `neg?` `zero?`, its comparators (`-safe-pred`), function schemas when no `::function-checker` is set, and the types an option names in `::total-types`; a named reference followed) is checked without a try; a `:ref`, a `:fn` or a user's own simple schema keeps its try, so a throwing validator is still `::invalid-schema-at-call`. A Jolt try is ~160-176 B, so a contracted `[:=> [:cat :int] :int]` call went 352 -> 0 B, `[:=> [:cat :map :int] [:map ...]]` 512 -> 144 B. Seon names its predicate pack in `seon.contract/options`. Proofs: `seon.contract-test`. malli's own core_test under Jolt: the same results as `0762fb6b` (889 pass; the same 3 failures and 21 errors, the environment's). Generic.

- Fork repo: ``https://github.com/seantempesta/malli`` (a clone of ``seantempesta/malli``). Upstream: ``metosin/malli``.
- Base: `80138076`, upstream master (0.20.0 + 14), the merge-base with upstream's master.
- Ours, from Seon's fork (`dd13ff81`, the pin before ours):
  - `ac1d51e0`, `3517a3cd` sci: drop an unsupported preset option; resolve loaded qualified Vars directly. Generic.
  - `606083c5` the resolving registry is the ref validation scope. Generic.
  - `25710a67` generator: resolve a symbol ``:gen/gen``, fail `::no-generator` for a non-generator. Generic.
  - `8725a8cb` eval returns a loaded function Var itself, so a re-evaluated `defn` stays live. Generic.
  - `56394c54`, `befcd302` instrument: a throwing validator is an `::invalid-schema-at-call` report carrying the arm's schema and values. Generic.
  - `a89ebef0` a registry pointer and an outermost ref share their target's cached validator. Generic.
  - `43c6dca8` a passing instrumented call allocates no regex driver, argument vector or guard pair. Generic.
  - `418df441` `-strument!` passes `:registry`; `-strument-each!` continues past a failing Var; `primitive-fn?` and `malli.error/levenshtein` public. Generic.
  - `dd13ff81` levenshtein keeps two primitive int rows. Generic.
- Ours, on ``jolt/main``: `5a830e14` `-instrument` of a one-fixed-arity function schema gets a wrapper of that arity (call 0.24 → 0.06 µs). Generic.

## crypto

`reference-code/crypto` pinned at `902196e`, the tip of ``jolt/main`` and ``jolt/seam-claims``.

- Fork repo: ``https://github.com/seantempesta/crypto``. Upstream: ``jolt-lang/crypto``.
- Base: `e6981b8`, upstream main (Ed25519, PR #16).
- Ours: `902196e` arms claim host tables only (`{:kind :table}` on every `__register-*!`). Generic.

## jolt-fressian

`reference-code/jolt-fressian` pinned at `8f3eb5d`, the tip of ``jolt/main`` (and the fork repo's `main`).

- Fork repo: ``https://github.com/seantempesta/jolt-fressian``. Upstream: ``jolt-lang/jolt-fressian``.
- Base: `7e7922a`, upstream main.
- Ours: `8f3eb5d` decode fixes: hash-map tag lookup, a code-byte dispatch table, a byte-array reader (leaf decode 8.6× faster). Generic.
- Off the default path: `deps.edn` alias `:fressian`, for opening a JVM-written store.

## jolt-overlay

`reference-code/jolt-overlay` pinned at `4ecb7a8`, the tip of ``jolt/main`` (and the fork repo's `main`).

- Fork repo: ``https://github.com/seantempesta/jolt-overlay``. No upstream: `.jolt` copies of two libraries Datahike needs.
- Base: `d2013ff` "baseline" (a `deps.edn`).
- Ours: `355f390` clojure.spec.alpha 0.5.238 verbatim; `3427652` hasch.platform 0.4.100 verbatim, plus `res` in spec.alpha (a resolved deftype is not an emittable literal); `4ecb7a8` hasch's digest names upper-cased (`SHA-512`, `MD5`). Jolt (workarounds).
- **Recommendation: don't fold it into `jolt` as is.** Each change is a workaround for a generic gap elsewhere: (1) jolt-lang/crypto's `MessageDigest/getInstance` is case-sensitive where the JVM's is not, so the fix belongs in crypto, and hasch.platform's copy then goes; (2) `resolve` of a deftype yields a value Jolt cannot emit as a literal, a Jolt compiler gap. Once both are fixed, what remains is spec.alpha verbatim, which Jolt could ship in its stdlib or we could take as a pinned dependency.

## http-client

Pinned in `deps.edn` (not a submodule).

- Fork repo: ``https://github.com/seantempesta/http-client``. Upstream: ``jolt-lang/http-client``.
- Base: `5a9706e0`, upstream main ("Send a default User-Agent").
- Ours, on ``jolt/fiber-aware-read`` `e866277d`:
  - `d9901f9a` a read from a fiber parks the fiber, not its carrier: `jolt.http.net/await-readable!` waits through `jolt.io-poller/wait-ready` on a fiber (the socket's read timeout as its deadline), poll(2) slices on a thread as before. On a main-carrier process (an agent, its test fork) the poll held the one thread, so a server fiber in the same process never answered and the read waited for good (S1, live agents 2026-10-25: one judge's test fork stalled every agent). Proof: the fork's `jolt -M:fibertest` (red: the watchdog after 10 s; green in 0.2 s), its `-M:test` and `-M:timeouttest` green. Generic (an upstream candidate).
  - `4e846e47` on a fiber, recv does not block (MSG_DONTWAIT) and EAGAIN waits again: the poller may wake a waiter with nothing to read (its last wait's registration), and a blocking recv then held the carrier. Proof: `-M:fibertest` reads twice (red 3 of 3 without). Generic.
  - `e866277d` a read's deadline is fixed once per read, so a fiber woken with nothing to read waits again to the same deadline; a fiber on Windows (no MSG_DONTWAIT) reads as a thread does. Generic.

## lmdb

`reference-code/lmdb` pinned at `15420ce`, the tip of ``jolt/sysv-sem-identity``, built from source by `bin/build lmdb` with `MDB_USE_ROBUST` ([failure points](failure-points.md#lmdb-robust-locks)).

- Fork repo: ``https://github.com/seantempesta/lmdb``. Upstream: ``LMDB/lmdb``, branch `mdb.master3`.
- Base: `253ba52`, upstream `mdb.master3`. There is no ``jolt/main`` yet: the pin sits on its one topic branch.
- Ours, on ``jolt/sysv-sem-identity`` `15420ce`:
  - `42c0514` with SysV semaphores (`MDB_USE_ROBUST` on macOS) an env takes a semaphore set nobody uses, never one it shares. `ftok` keeps 16 bits of the lock file's inode, so two envs could share one set: the first open of one reset the other's locks, and the last close of one removed the set the other used (its next open: EINVAL). The last close also raced a process waiting for the lock file (EINVAL as well). A set now counts its processes (SEM_UNDO) and names its lock file; the lock format's type goes 8 → 9. EINVAL from a lock on a live set is the process's undo limit, `MDB_SEM_UNDO_FULL`. Generic (an upstream candidate).
  - `15420ce` a joiner never joins a set being taken (a sixth semaphore held from the claim until the set names its lock file), a take accepts only a set of six, and a failed open stops counting itself (from the branch's review). Proof of both: `target/lmdb-sem` (`native/lmdb_sem.c`), a `bin/check` step: every case red on `253ba52` (the third by a count), all green here. Generic.

## Unmodified submodules

The submodules we use as upstream has them, each with its pin and how it is built.

- `reference-code/zstd` at `f8745da` (v1.5.7), upstream ``facebook/zstd``; built by `bin/build zstd` ([storage](storage.md#compression)).
- `reference-code/awesome-jolt` at `9a04599`, upstream ``jlt-commons/awesome-jolt`` `main`; reference reading only.

## Dropped fork: superv.async

The fork's only change printed a stack trace in the supervisor's error fn; `deps.edn` pins ``seantempesta/superv.async`` `501b4294`, the commit Datahike declares, with `core.async` excluded because Jolt ships its own.

## Unmodified, pinned in `deps.edn`

ring-chez-adapter `fa0674e3`, multipart `ca51dd47`, time `a28ab100`, data.json 2.5.1, edamame 1.4.32, rewrite-clj 1.2.51, tools.reader 1.5.2, aero 1.1.6, fipp 0.6.29, core.cache 1.0.225.
