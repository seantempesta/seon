---
type: research
status: completed
tags: [research, pod, cljs]
---

# ClojureScript evaluator footprint audit

## Decision

The loaded execution child is not exhibiting an unexplained 500 MB private
memory leak. A clean Bun package containing the official self-hosted
ClojureScript compiler, Seon's current bootstrap analysis caches, and only a
small namespace/`defn`/call workload uses about 205 MB private physical memory.
That is within 14 MB of the measured 219 MB Seon execution child.

SCI 0.15.56 is the strongest smaller evaluator for Seon's actual authored-code
contract. A clean package performing namespace loading, host calls, protocol
and record definition, function metadata discovery, schema registration,
ordinary calls, and `^:async`/`await` uses 117 MB private physical memory in the
representative run. The simpler three-run workload used 112, 112, and 114 MB.
Replacing `cljs.js` with SCI should therefore save about 90 MB private memory
per loaded execution child while retaining self-contained children.

This is not a dependency swap. Seon's runtime currently consumes the official
analyzer's internal maps for persistence, namespace operations, warnings,
failed-eval cleanup, and introspection. SCI is a viable replacement only when
one evaluator owns all authored code and those consumers are rewritten against
SCI namespaces, vars, metadata, and Seon's existing source-derived database
rows. Do not ship both evaluators in an execution child: Seon already does that
because rendering uses SCI while authored eval uses `cljs.js`.

## Dependency ledger

- ClojureScript: `946d75f3483c0c8e784e6668bff2c71a25619a77` in
  `reference-code/clojurescript`.
- Shadow CLJS: `4e72595f57618f5c43388ad13d5136cd3bede566` in
  `reference-code/shadow-cljs`.
- SCI 0.15.56: `9e9c78f4f358ede939b94352ff4edc03b0186c7a` in
  `reference-code/sci`.
- Bun 1.3.14, revision `0d9b296a`.
- Cherry: `4fa784acde6f37498e227d957447358fd4791af1`.
- Squint: `c3db1a55f0c9b9c116469fb722132a4a7a63c48e`.
- NBB: `26adf6e870a7217f98aedc4857126609c3803ec6`.

The Cherry, Squint, and NBB checkouts were disposable source audits under
`tmp/cljs-footprint`; they are not selected dependencies.

## Measurement method

The clean Shadow project lives under ignored `tmp/cljs-footprint`. Every build
was a `:node-script` release run by Bun. Each process called `Bun.gc(true)` at
phase boundaries and reported `process.memoryUsage` plus
`bun:jsc/heapStats`. macOS `footprint -p` measured private physical memory
after the final workload while the process remained alive.

The official self-host workload created one persistent `cljs.js` analyzer
state, loaded bootstrap analysis caches, evaluated an `ns`, defined a function,
called it in a later evaluation, and evaluated a `^:async` function containing
`await`. The SCI workload preserved one context and namespace across the same
operations. The representative SCI extension also defined a protocol and
record, exposed compiled host functions, registered a schema through a host
function, and enumerated namespace publics and var metadata.

RSS is included for diagnosis but is not the capacity number: it includes clean
mapped pages that the operating system can share or reclaim. Private physical
memory is the relevant per-child pressure.

## Results

| Package and workload | Private physical | Final RSS | Final JSC heap | Objects |
|---|---:|---:|---:|---:|
| Compiled CLJS baseline | 40 MB | 64–67 MB | 4.2 MB | 29,000 |
| Minimal official self-host | 160, 175, 180 MB | about 220 MB | 26.0 MB | 218,000 |
| Official self-host with Seon bootstrap | 205, 205, 206 MB | about 272 MB | 32.0 MB | 315,000 |
| SCI 0.15.56, simple parity workload | 112, 112, 114 MB | 144–147 MB | 16.4 MB | 62,000 |
| SCI 0.15.56, representative Seon workload | 117 MB | 167 MB | 14.7 MB | 45,000 |
| Cherry 0.5.34, synchronous workload | 35, 35, 36 MB | about 60 MB | 5.0 MB | 22,000 |

The representative SCI result proves the required values, rather than merely
loading namespaces:

```clojure
{:value 42
 :protocol-value "Ada"
 :aliases (host schema)
 :publics
 {add-one
  {:arglists ([x])
   :doc "Adds one."
   :malli/schema [:=> [:cat :int] :int]
   :seon.fn/agent-facing? true}}}

```

The host-side schema registration received
`{:seon.schema/name :scratch.agent/person}`. The subsequent async evaluation
also completed.

### Where official self-host memory appears

For the minimal official compiler, loading the compiler module reached about
106 MB RSS and 7 MB JSC heap. Loading `cljs.core` reached about 164 MB RSS and
22 MB JSC heap. Loading the minimal analysis caches reached about 206 MB RSS;
the small authored workload finished near 220 MB RSS and 26 MB JSC heap.

Seon's full bootstrap contains 46 analysis-cache files and occupies about
16 MB on disk, compared with 25 files and 10 MB for the minimal bootstrap. It
reached about 248 MB RSS after cache loading and 272 MB after the small
workload. The difference between minimal and full bootstrap is material but is
not the dominant cost; the official self-host compiler and core analysis state
are.

## Exact Seon responsibility inventory

### Build-time indexing is separate

`src/seon/indexing.clj` runs inside the JVM Shadow compiler. Its
`public-fn-vars` and `first-party-ns-strs` read Shadow's
`:cljs.analyzer/namespaces` to emit the compiled first-party program boundary.
This does not require a self-host compiler in each execution child.

The program-source artifact can preserve compiled host functions, schemas,
tests, namespace source, and require information produced at build time. It
cannot represent new agent-authored definitions created after the package was
built, so it does not by itself replace a per-child evaluator and mutable
authored namespace state.

### Runtime bootstrap

- `src/seon/eval.cljs` creates `cljs/empty-state` and initializes the Shadow
  bootstrap loader.
- `src/seon/eval/bootstrap_cache.cljs` reads every Transit analysis cache and
  calls `cljs.js/load-analysis-cache!`.
- `src/seon/repl.cljs` retains the compiler state.
- `src/seon/execution.cljs` initializes that state lazily for a child.

### Runtime compilation and evaluation

Every authored form ultimately uses `cljs.js/eval-str`; it is not limited to
function definitions. The same operation also:

- creates and changes namespaces;
- loads and replays persisted authored namespace source;
- evaluates ordinary calls and special REPL forms;
- emits JavaScript and executes it in the child; and
- preserves definitions for later forms.

`src/seon/worker_eval.cljs` also uses `cljs.js`, but it is an independent worker
oracle and not the compiler state retained by every execution child.

### Runtime analyzer consumers

`src/seon/analyzer_info.cljs` directly reads
`:cljs.analyzer/namespaces`. `src/seon/eval.cljs` relies on its projections for:

- definition snapshots and changes after an eval;
- function symbol, namespace, arglists, docstring, privacy, Malli metadata,
  agent-facing metadata, and test metadata;
- persistence into the existing `:seon.fn`, `:seon.test`, and `:seon.ns`
  database rows;
- require, alias, refer, and `:as-alias` connections;
- undeclared-var warnings and error classification;
- removal of phantom definitions after a failed eval;
- hot-redefinition detection;
- `in-ns`, `alias`, `ns-unmap`, and `ns-unalias` behavior;
- autocomplete and namespace introspection; and
- generated result-var registration and cleanup.

Schema discovery is already separate: it diffs Seon's schema registry rather
than reading the analyzer. That can remain a host boundary exposed to SCI.
Database queries, rendering derivation, and Datahike are not compiler-state
consumers.

### Compiled host vars are another boundary

Some Seon paths inspect compiled functions through `globalThis`. SCI vars are
interpreter values and are not those emitted JavaScript vars. Existing Malli
instrumentation and direct var lookup cannot silently continue unchanged.
Either SCI invocation enforces the function schemas, or Seon publishes narrow
host wrappers deliberately. This is a core prototype exit, not a packaging
detail.

## Evaluator comparison

| Responsibility | Official `cljs.js` | SCI 0.15.56 | Cherry/Squint |
|---|---|---|---|
| Persistent namespaces and definitions | Native | Proven | Compiler state exists, different dialect/runtime |
| Ordinary forms and host function calls | Native | Proven with explicit namespace maps | Proven synchronously in Cherry |
| `^:async` and `await` | Native | Proven by probe and SCI tests | Cherry emitted invalid JS for Seon's exact form |
| Protocols and records | Native | Proven | Not accepted as parity evidence |
| Var metadata and namespace enumeration | Analyzer maps | Proven through SCI vars and `ns-publics` | Non-canonical compiler metadata |
| Canonical CLJS emitter and JS interop edges | Yes | No | No |
| Current analyzer map shape | Yes | No | No |
| Current warning and failed-definition semantics | Yes | Must be replaced and tested | No |
| Existing `cljs.test` behavior | Yes | Requires an SCI config/integration | No parity proof |
| Arbitrary CLJS macros | Yes with bootstrap caches | Only exposed or SCI-compatible macros | Dialect-specific |
| Hard process termination | Child supervisor | Child supervisor | Child supervisor |

### SCI

SCI 0.15.56 is current, already used by Seon's renderer, and provides a
persistent context, namespaces, vars, metadata, protocols, records, host
namespaces, permissions, and asynchronous evaluation. Its
`sci.async/eval-string+` and async/await tests cover nested awaits and error
paths. Its interrupt-aware functions can improve cooperative termination, but
the process supervisor remains the hard stop for host calls or other work that
cannot reach an interpreter interrupt check.

NBB demonstrates the relevant production idiom directly: its REPL utilities
implement `ns-map`, `ns-publics`, `all-ns`, `find-ns`, and `ns-aliases` by
querying an SCI context. NBB also wires a `cljs.test` SCI config. Seon has not
yet selected or implemented an equivalent test config, so tests remain an
explicit parity gap.

### Cherry and Squint

Cherry is dramatically smaller because it emits native JavaScript rather than
retaining the official analyzer. That number is real, but its own documentation
describes it as experimental and permits constructs incompatible with
ClojureScript. The exact Seon async form compiled to an invalid
`await.call(...)` expression; Cherry expects its own async dialect constructs.
Its namespace state is not a substitute for the official analyzer maps.

Squint makes a similar ahead-of-time or runtime compilation trade: small native
output by supporting a deliberately different ClojureScript-like language.
Either could become an intentional restricted language, but adopting one would
change the authored-code contract more deeply than SCI and would not remove the
need to rebuild Seon's analyzer consumers.

### Forking the official compiler

A smaller fork is not the first move. The observed cost is not a removable set
of unused public API namespaces alone: loading `cljs.core` and retaining the
analyzer environment account for most memory, and Seon presently consumes that
environment broadly. Removing emitter or analyzer features while retaining
exact CLJS semantics creates a private language implementation and compiler
maintenance burden. SCI already embodies the more useful cut: interpret the
needed language and explicitly expose the host surface.

## Recommended replacement seam

Keep the public `seon.eval` behavior and existing database rows. Replace its
internal authored-code owner with one SCI context per execution child:

1. Build one explicit SCI namespace map for the compiled Seon functions and
   permitted platform APIs.
2. Preserve one SCI context and current SCI namespace for the child's lifetime.
3. Evaluate persisted namespace source and new forms through SCI.
4. After successful evaluation, enumerate affected SCI namespace vars and
   their metadata, then produce the existing function, test, and namespace
   database rows. Keep schema registration on the existing host registry.
5. Derive require connections from SCI namespace data where exact, and from
   the already-owned source parser where SCI does not expose the necessary
   fact. Do not create a second program representation.
6. Apply Malli contracts at the SCI-to-host and function-invocation boundaries
   instead of assuming interpreted vars appear in `globalThis`.
7. Remove `cljs.js`, bootstrap analysis caches, and their analyzer adapters
   from the execution artifact in the same cut once parity gates pass.

The program-source artifact remains complementary: it supplies the compiled
host program and source rows without production source directories. SCI owns
only mutable agent-authored evaluation.

## Required prototype gates

Before committing the runtime replacement, run a focused representative Seon
corpus through both evaluators and compare the existing public envelopes and
database rows. The minimum gates are:

- namespace creation, require, alias, refer, unmap, and later-form persistence;
- `defn`, metadata map, Malli schema, schema registration, protocol, record,
  multimethod if retained, JS interop, Promise, `^:async`, and `await`;
- function and namespace row equivalence, including source and require
  connections;
- `deftest`, async tests, selected test execution, and failure reporting;
- syntax, unresolved symbol, runtime exception, failed definition, timeout,
  and process restart behavior;
- compiled Seon host calls plus agent-authored function-to-function calls;
- instrumentation or replacement contract enforcement; and
- execution-child private footprint after removing `cljs.js` and bootstrap
  assets, not while both evaluators remain packaged.

The acceptance target is semantic parity for Seon's supported authored-code
surface, not every ClojureScript compiler feature. Unsupported forms must
return a clear ordinary `:seon/error` value. If the representative corpus
reveals a required canonical compiler behavior that SCI cannot express simply,
retain `cljs.js`; do not build a hybrid runtime or a private partial compiler.

## Conclusion

The concerning child footprint is normal for the current architecture and is
reproducible outside Seon. The high-impact simplification is to stop retaining
the official self-host compiler and SCI together. SCI 0.15.56 offers the best
measured balance: roughly 90 MB less private memory per child than the current
full self-host baseline, a mature restricted execution interface, and proven
support for the core authored-code forms. It warrants a bounded parity
prototype followed by one replacement cut, not further micro-optimization of
bootstrap caches and not a fork of the official compiler.
