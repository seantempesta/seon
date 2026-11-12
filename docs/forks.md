# Forks

Every library we change is a git submodule under `reference-code/`, pinned at a commit of our fork, ``https://github.com/seantempesta/<lib>``. A fork is upstream's head plus a short list of our changes, each **Generic** (an upstream candidate) or **Jolt** (needed only on Jolt). Each pin's own history is ``git -C reference-code/<lib> log``; each bump's reason is in this repository's ``git log -- reference-code/<lib>``.

## Branch scheme

How a fork's branches are named and where a pin sits.

- ``jolt/main`` is a fork's working line; ``jolt/<topic>`` holds one fix, cut from ``jolt/main`` or the topic it needs. A pin may sit on a topic branch not yet merged into ``jolt/main``.
- ``upstream/<topic>`` is a generic fix cut from upstream's main, the only candidate for an upstream pull request, which the owner opens.
- A pinned commit is pushed to its fork on a ``jolt/<topic>`` branch by whoever made it: never the fork's `main`, never forced. Fork commits carry no AI trailers. `persistent-sorted-set` and `jolt-overlay` are private.
- A pin moves only in a landing on main ([merging](merging.md#conflicts-and-pins)).

## Following upstream

([decisions](decisions.md#the-forks-follow-upstream-theirs-when-almost-as-good-ours-written-to-be-offered-owner-2026-11-10))

- **The difference from upstream is kept small.** Where upstream's solution is almost as good as ours, we take theirs. Ours stays for the Jolt port, for a measured gain upstream cannot give, or for something Seon calls that upstream lacks.
- **Merge upstream often, in slices**, each built and gated. A fork far behind is rebuilt instead: upstream's head with only what Seon needs applied as fresh commits.
- **Fork code is written to be offered:** upstream's style, naming, tests and changelog rules, one reason a commit.
- **A pin proves whole before it lands:** `bin/prove` on its own branch, the [own suites](#own-suites) included. A Jolt pin also runs `bin/jolt-gates` (its header is its manual): green when the gates not passing are exactly those `test/forks/jolt-gates-red.txt` names.
- **A stricter upstream compiler is a law.** What upstream refuses at compile (a malformed `ns` form, an overlapping `fn` arity, a method declared twice) Seon's code may not contain; Jolt's `tools/ns_audit.clj` reads a source tree for the first.

## Own suites

A pin bump runs the forks' own tests on Jolt, so a fork commit cannot change semantics unseen. `bin/check`'s `forks` step (`bin/forks`, whose header is its manual) runs each unit `test/forks/baseline.edn` names (a Datahike test namespace, a konserve or persistent-sorted-set test file) in a few Jolt processes (`test/forks/run.jolt`, on the `:dhtest` alias), and is red when a unit's counts differ from the baseline's. A unit with no counts, or right alone and wrong beside others, is run again alone and its log kept under `target/forks-died/`. Its key is `:pins` (```seon.proofs/check-steps```): every submodule's pin and the checkout's `:env`, so a bump of jolt, datahike, konserve or persistent-sorted-set runs it and a change to Seon's `.clj` does not. A commit that moves a unit's counts records the new ones in the baseline.

## jolt

`reference-code/jolt` at `5dca3389` (``jolt/deps``), upstream ``jolt-lang/jolt`` main `8d3ba3c9` plus our commits merged on top. Seon's agents run on one OS thread with fibers ([processes](processes.md)), fork from root, and are measured by ticks and bytes ([cost](cost.md#what-is-measured)); most of what we carry serves those three. Jolt's one red gate (`buildsmoke`) is listed with its cause in `test/forks/jolt-gates-red.txt`; catching up with upstream is task `jolt.host/jolt-is-at-upstreams-head`.

- **The main carrier** (Jolt): a program and its fibers on its one thread; it wakes on a fiber another thread queues, on a timer, on signals; decides it is stuck only under the lock its wakers take; `main-carrier-run!` returns into an ordinary process, so root boots there and then starts its threads. A process nothing can wake tells each waiter `{:jolt/deadlock true :parked dump}` in its own frame ([failure points](failure-points.md)).
- **Fork safety** (Generic): ``jolt.host/fork-alone`` is the one way to fork; `jolt-after-fork-child!` drops the bookkeeping of threads fork(2) did not copy, remakes every process lock and object monitor, and runs none of the parent's fibers or shutdown hooks; a forked child re-protects MAP_JIT code (chez-patches 0006, 0007, macOS arm64).
- **Fiber correctness:** a fiber is a second mutator (``jolt-mt?`` set at the first fiber, so the single-threaded fast paths end); `with-out-str` and host winders hold across a park; an uncaught fiber or go-block error reaches the default uncaught-exception handler, a channel's transducer error is reported off the channel's lock (gate `parkcheck`), and an unread one is reported when the fiber is collected; a class a library provides is found by every context at once; a promise-chan keeps its value for parked takers; a port that is not a channel is read through `ReadPort`; an atom's (and agent's, ref's) watch and value are ordered by a fence; a var's watches and validator live in its cell, so a def validates, stores and notifies in `def-var!` with the cell in hand and a watch added during a def never misses the root (Chez's ordered CAS, no lock). Gates `fibersecondmutator`, `fiberuncaught`, `fiberunread`, `providerfirstuse`, `atomwatchorder`, `varwatchorder`.
- **Diagnostics:** `(jolt.host/fiber-dump)` and SIGINFO list every carrier with its OS thread, what holds a main carrier's quiescence off (`quiescence: held by ...`), and every live fiber; with `JOLT_DUMP_DIR` set, SIGINFO's dump also goes to `<dir>/dump-<pid>.txt` (a forked test child's descriptor 2 is a pipe to its parent); running fibers first, each with where it was spawned and where it is parked, a running one printing its own frames at its next safe point, written to descriptor 2; an `OutOfMemoryError` carries a per-type histogram and is raised after a collection's rendezvous; a runaway recursion overflows its stack before the heap ceiling; the stack-limit hook never throws its own failure; `exit-watch` retries EAGAIN.
- **Measurement:** one tick count owned by the timer (``jolt.host/ticks``), `run-interruptible` with a tick and a byte budget, `System/gc`'s allocation and the runtime's own not charged to the caller ([cost](cost.md#what-is-measured)).
- **Allocation and speed** (Generic): a uuid is its two halves, its text kept only when it was made from text and written from the halves when `str` asks; a keyword decodes from its UTF-8 bytes in place (`keyword-from-utf8`); a class named in code keeps its interned token at the site (`jolt-class-for-site`: an `instance?` of a deftype 59 -> 4 ticks); `jolt.ffi`'s byte moves copy once; an InputStream whose port knows its length reads what remains once (`readAllBytes`); flow reports an output nothing takes (`::flow/undeliverable`); a fiber waiting on the poller (a subprocess, a pipe, a socket) keeps a main carrier from quiescence, a pump's or a signal's wait does not; an evaluated form runs in a fn named as the JVM's Compiler.eval names it (`ns/eval<n>`), so a trace and a REPL error name it, and a tail try/finally keeps its fn's frame (`jolt-frame-keep`); an assoc of several declared fields copies a large record's values once; a string literal with no escape is read as one substring; a method call on a deftype or record keeps the type's declared method at its call site (``jolt-host-site-callN``), so a hit calls it with its arguments in place (2 ticks and no allocation, against 39 ticks and 144 bytes through `record-method-dispatch`); deftype field access by slot, record methods bind only the fields they read, host sites read persistent collections directly, analysis allocates only its IR, `reduce-kv`, `tree-seq`, `(== a b)` and lazy claims allocate nothing extra, `HashSet` and `ArrayList` bulk methods are linear, an `ffi/cfn` site and a literal C entry name are looked up once (gates `foreignlookuponce`, `jolt-ffi-site-cache`), a large byte array copied from one is immobile, the printer writes to one port, string literals emitted in one pass.
- **Build and AOT cache** (Generic): one cache shared by a project's checkouts, kept under `JOLT_CACHE_MB`, the oldest written evicted first, so a hit writes nothing, an empty entry is no hit; `runtime-fingerprint` keys compiled units; a build's evals compile outside its unit; `JOLT_COMPILER_OPTIONS` sets `*compiler-options*` (`{:image-fn-forms false}` skips image registration); a built app carries Jolt's sources and binds `*command-line-args*`.
- **Libraries Seon calls:** `jolt.async.remote` (channels to another process: typed send errors, an unencodable value answered in its place; fiber pumps by default, so a process holding ends stays one thread and can fork; an end's `:sent` answers once its writer is done); `jolt.async.remote.flow` (a remote proc's out-port end is its consumer's last value, in order; `fork!` forks a child as a proc of a running flow: the child holds only the streams it is given, `jolt.async.remote/close-streams-but!`, the one rule for any fork, and a throw in it is its last value, `{::flow/pid ::flow/ex}`); `go` on fibers by default; Flow's `update!` changes a running flow in place (given a fn, of the config last applied, under the flow's lock), rewiring only the out coords a change touches, and `stop` awaits every proc; `::flow/error` is a fixed buffer, never dropping (upstream slides); `::flow/report` slides, its drops counted in ping's metadata (`::flow/report-dropped`, `jolt.host/chan-dropped`); a remote end's pumps are never told a deadlock, and `jolt.host/on-quiescent!` hears a main carrier's quiescence once with the dump (an idle process is quiescent too: what it means is the handler's); `java.util.Arrays/compare` and `hashCode`, `Semaphore`, `HashSet` bulk methods, `File.isFile` regular only, `createTempFile` exclusive; `System/exit` runs no `finally`; sorted and atom fns reach a deftype through its declared interfaces; the analyzer env's `:private?` function; ``jolt.host/*resolve-unresolved*`` for a REPL; `jolt.analyzer/suggestions-for` public.
- ``upstream/*`` branches (`arraycopy-object-block`, `deftype-field-access`, `fiber-park`, `fork-reprotect-jit`, `host-site-coll-reads`, `remote-chan`, `static-code-cow`) exist only in the main tree's clone of the fork; none is opened. `static-code-cow` stays ours.
- Open: the fork's ``jolt/main`` (`1fad8b83`) is under the pin; it should move to the pin's line. A background-GC hang in Datahike's pipelined-writes test is task `jolt.async/a-permit-released-by-a-callback-is-never-lost`.

### A fiber's state is safe by construction

An agent's eval is a fiber on the agent's one OS thread, so Jolt keeps each fiber's state right across a pause and keeps shared state whole by construction, not per site (owner, 2026-11-11: "I want the classes eliminated so the guard is unnecessary"). The rules:

- **A pause unwinds and a resume rewinds every wind hook but a `finally`** (``host/chez/fibers.ss``). So state a fiber owns is never set in an entry hook: it lives in the slice the scheduler saves and restores at every pause (bindings, namespace, transaction), and scoped constructs are built from constructors that take no entry hook from their caller.
- **A table the whole process shares** is filled before a second fiber or thread exists, or is an immutable value in one box replaced by compare-and-set, or locks inside its own operations.
- **A fast path for one thread** is chosen by "a second fiber or thread has ever existed" (``jolt-mt?``), never by counting OS threads. Its price is in [cost](cost.md).
- **What is kept per OS thread** (a ``ThreadLocal``, an eval's byte count, the error position) is not read as a fiber's own: it moves into the slice.
- **Not eliminable:** `with-redefs` changes a var for the whole process, as on the JVM; a weak table keyed by live objects; Chez's string-keyed tables, which lock inside; the refusal to pause while a counted lock is held, a typed error. LMDB is not a case: its read transactions are not tied to a thread.

The open work is tasks `jolt.host/a-fibers-own-state-lives-in-its-slice`, `jolt.host/a-scoped-construct-has-no-entry-hook` and `jolt.host/a-shared-table-is-frozen-swapped-or-locked-inside`.

## chez

`reference-code/chez` at `84eb5939` (``jolt/ordered-cas-acquire-release``), upstream ``cisco/ChezScheme`` v10.4.1 `e95a7ef`; fork ``seantempesta/ChezScheme``. All Generic.

- Jolt's chez-patches 0001–0007, as Jolt's CI builds Chez (lock-free thread activation, fork re-protects MAP_JIT, static code leaves MAP_JIT so forks share it).
- vfasl fixes for negative bignums and fxvectors, with mats.
- `(cs)collect_sweepers` fixes the parallel collector's sweeper pool size.
- `(cs)thread_bytes_allocated`: a per-thread mutator byte count that excludes the collector, its handler and the runtime's stacks ([cost](cost.md#what-is-measured)).
- A signal pending during `S_condition_wait` runs its handlers before the caller waits again.
- Ordered accesses for a box, a vector slot and a record field: a load-acquire, a store-release and a sequentially consistent CAS (nine primitives), so Jolt orders a cell's value and its watches without a lock.
- `strip-fasl-file` options `inspector-expressions` and `inspector-variables`, used by `bin/build`'s release strip ([machine](machine.md#what-a-process-owns-in-memory)).
- Open: the fork's ``jolt/main`` (`fdd1e1e`) is under the pin.

## datahike

`reference-code/datahike` at `61f03e49`, on ``jolt/deps``: upstream ``replikativ/datahike`` history with our changes as fresh commits on top. The fork's ``jolt/main`` (`fde9f89`) is the old pre-rebuild line and is not under the pin.

- **The `:sync` writer** (Jolt, our design): group commit by `transact-batch!`; run-ahead writes (`apply!`, `commit-pending!`, `abandon-pending!`), a run-ahead value shown on the connection ([storage](storage.md#a-run-ahead-value-shown-on-the-connection)); a streaming writer's deref reads no store; merges go through the writer's own transact, so Seon's `transact-checked` sees every one ([storage](storage.md#the-write-checks-every-entity-it-touches)); a writer waits for its connection on a watch; a released connection says `:connection-has-been-released`; `force-branch!` moves a live `:sync` connection to the new head ([storage](storage.md#a-moved-head-moves-its-live-connection)).
- **Ids** (Generic): entity and transaction ids issued once across every branch; content entity ids (`:db/contentId`) partitioned from counter and tx ids; a compact branch head carries its ids ([storage](storage.md#ids)).
- **Content addressing** (Jolt): a leaf's and a branch's address are one SHA-256 of fasl bytes, not hasch's walk; so is the key a commit stores a changed schema under (`datahike.writing/schema-meta-uuid`, over a canonical form: equal schemas key the same whatever order built them). A store reads its schema by the key its commit recorded, so one written under hasch keys reads as it is. `:crypto-hash?` fixed when a store is made.
- **Storage** (Generic): large values out of line ([storage](storage.md#values-out-of-line)); `:db.type/data` and a total order on plain data; the branch roster an append log; collection runs synchronously, keeps the commit graph, a dry run writes nothing, a value key trusted only in its collection generation ([storage](storage.md#collection)); a node cache weighed by bytes retained (Jolt); `after-fork!` keeps the node caches and drops the parent's connections.
- **Reads** (Generic): a node restore reads its node with konserve's `get-immutable`, and the node cache weighs a node in one loop over its key array (Jolt); a db carries its schema's key, so a commit, a restore and a connect hash no unchanged schema; a pull of named attributes reads each entity's `:eavt` range once and charges the datoms it reads to its budget; a diff skips the unstored children both values hold, and the diff of two whole histories follows what differs; `with-reads` records the index ranges a read scanned, `datoms-diff` included; `datoms-diff` costs the nodes that differ and answers for temporal and filtered values ([storage](storage.md#merges-read-what-changed)); `commit-at-tx` finds a branch's commit at a transaction ([storage](storage.md#a-points-commit)); pull's `:reverse` reads newest first; a time predicate keeps index order; an attribute a db predates answers no datoms; a unique attribute's value is one descent.
- **Queries** (Generic): a call that throws is the query's error (`:query/call-threw`); a nested call is refused at parse; `<` `>` `<=` `>=` keep Clojure's semantics; `probe-driven-threshold` 18 (Jolt's measured break-even); a recursive rule keeps its predicates; attribute comparisons enter no `try`.
- **Transactions** (Generic): a datom copies the db record once (a transient index changes in place, and its op-count, max-eid and hash are one assoc); a replaced card-one value is retracted in the report; values compare by content; a carried datom names an issued transaction; listeners are told before the caller has its result (upstream's order).
- **Upstream fixes replayed**, each with its test: #1117, #949, #950, #956, #1092/#905, #954, #940. Not on this line: #912, #913, #927, #885.
- Tests changed so the core suites load and run on Jolt, as [own suites](#own-suites).

## persistent-sorted-set

`reference-code/persistent-sorted-set` at `b5a8798` (``jolt/upstream-0.5.145-count-fixes``). Written fresh as Jolt deftypes (no upstream history); upstream ``replikativ/persistent-sorted-set``. An overlay: `deps.edn` puts its `src` on `:paths` ahead of the mvn 0.4.137 jar Datahike depends on, which supplies the other namespaces. Jolt.

- The JVM core as Jolt deftypes, with EDN node forms and `forwardCursor` taking a `java.util.Comparator`.
- A `:weak` ref type: a branch keeps no child it restored, so the node cache is the one holder of nodes read back.
- Upstream 0.5.145's count B-tree fixes, one commit each, with upstream's tests as `.jolt` files in the [own suites](#own-suites). Not taken: the node codec, `diff`, `walk-delta`, MST, measures.
- A transient node's array grows by its length (846 B a conj into Seon's EAVT, 1,246 with upstream's growth by 8).

## konserve

`reference-code/konserve` at `4e84568` (``jolt/deps``), upstream ``replikativ/konserve`` head `63ad4ad` with our commits replayed on it. The fork's ``jolt/main`` is not under the pin.

- Jolt stores over `jolt.ffi`: a POSIX filestore and an LMDB store, `konserve.store`'s `:file` and `:lmdb` backends on Jolt; an LMDB env lives as long as a store holds it, a read after its release is `{:type :lmdb-env-closed}`, envs under a deleted directory are closed first. Jolt.
- A node read (`k/get-immutable`) takes no lock, probes or revision: a key written immutable never changes. Jolt.
- An append is one transaction on a store that runs them, so two processes appending one log lose nothing. Generic.
- Serialization: jfasl (a tagged fasl format: a sorted map or set by the default comparator and a queue store and come back as themselves; a function, an atom, an array, a regex or an exception has no stable encoding and is refused, naming it) and `FaslSerializer`; a value serialized once; LZ4 and zstd through liblz4 and libzstd (`JOLT_LZ4_LIB`, `JOLT_ZSTD_LIB`, [storage](storage.md#compression)); a store key's SHA-512 one libcrypto call. Jolt.
- `sweep!` with ``:konserve.gc/sync-io?``; `compact-copy!` returns a sweep's freed pages. Generic, Jolt.
- The lock token channel is `(chan 1)`, a workaround for Jolt's `poll!`. Jolt.
- Open: `konserve.jolt.fasl-store` names persistent-sorted-set node types; its cleaner home may be that fork.

## malli

`reference-code/malli` at `4dc9e305`, upstream ``metosin/malli`` master `80138076` plus ours. All Generic.

- Instrument: a throwing validator is an `::invalid-schema-at-call` report; a passing call allocates nothing; a one-fixed-arity schema gets a wrapper of that arity; an arm whose validator cannot throw (`-total?`) is checked without a `try`; `-strument!` passes `:registry`.
- Validators: a registry pointer and an outermost ref share their target's cached validator; a nested `:ref` compiles on first use (upstream #1270); recursive ref transformers are reused (upstream #1250).
- `::mg/=>seed` seeds quick-check and a failure carries its seed; sci and generator fixes; eval returns a loaded Var; `levenshtein` public and on int arrays.

## crypto

`reference-code/crypto` at `990293d` (``jolt/deps``), upstream ``jolt-lang/crypto`` main `e6981b8`. Arms claim host tables only (`{:kind :table}`); a digest is one allocation for its input, the digest and its length. Generic.

## jolt-fressian

`reference-code/jolt-fressian` at `8f3eb5d`, upstream ``jolt-lang/jolt-fressian`` `7e7922a`. One commit: decode fixes (leaf decode 8.6× faster). Generic. Off the default path: the `deps.edn` alias `:fressian`, for opening a JVM-written store.

## jolt-overlay

`reference-code/jolt-overlay` at `6bf3985e`. No upstream: `.jolt` copies of two libraries Datahike needs, clojure.spec.alpha 0.5.238 and hasch.platform 0.4.100.

- Workarounds (Jolt): digest names upper-cased, because jolt-lang/crypto's `MessageDigest/getInstance` is case-sensitive; a `res` in spec.alpha, because a resolved deftype is not an emittable literal. Fixing both gaps where they are would leave spec.alpha verbatim.
- hasch encodes a `clojure.lang.Ratio` as a number. Generic.
- hasch's digests in fewer host calls, every id still hasch's on the JVM (`seon.db-test/hasch-ids-are-the-jvms`). Jolt.

## http-client

Pinned in `deps.edn` at `e866277d` (``jolt/fiber-aware-read``), upstream ``jolt-lang/http-client``. A read from a fiber parks the fiber, not its carrier: it waits through `jolt.io-poller/wait-ready` with a fixed deadline and receives without blocking, so a server fiber in the same main-carrier process still answers. Generic.

## lmdb

`reference-code/lmdb` at `989f3e07` (``jolt/an-env-inherited-across-fork-is-refused``), upstream ``LMDB/lmdb`` `mdb.master3` `ba47717`, built by `bin/build lmdb` with `MDB_USE_ROBUST`. No ``jolt/main`` yet. All Generic (upstream candidates):

- Locks the kernel gives back: where LMDB chose SysV semaphores it locks with fcntl open-file-description record locks, and a dead process's reader slot is cleared ([failure points](failure-points.md#lmdb-locks)).
- ``MDB_SAFENOSYNC``: commits as `MDB_NOSYNC`, and an OS crash rolls back to the last sync instead of corrupting ([failure points](failure-points.md#lmdb-crash-safety)).
- ``MDB_ENV_FORKED``: a transaction through an env inherited across fork() fails.

## superv.async

Pinned in `deps.edn` at `6f6c8231` (``jolt/an-idle-supervisor-sets-no-timer``), on the commit Datahike declares, with `core.async` excluded because Jolt ships its own. The stale-exception sweep is armed only while an exception is pending, so an idle store sets no timer (`seon.db-test/an-idle-store-sets-no-timer`). Generic.

## Unmodified submodules

The submodules we pin but do not change.

- `reference-code/zstd` v1.5.7, built by `bin/build zstd` ([storage](storage.md#compression)).
- `reference-code/llama.cpp`, built by `bin/build embed` for the embedding model ([vectors](vector.md#the-model)).
- `reference-code/datastar` 1.0.4, the page's client ([render](render.md)).
- `reference-code/awesome-jolt` and `reference-code/clojure-mcp`: reference reading only.

## Unmodified, pinned in `deps.edn`

The rest of `deps.edn`'s dependencies, each with its pin and reason there.
