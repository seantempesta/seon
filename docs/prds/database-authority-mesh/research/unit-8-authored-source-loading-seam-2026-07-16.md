---
type: research
status: complete
tags: [research, cljs, capability, database, flow]
---

# Unit 8 — authored source loading seam

## Decision

The execution child should compile agent-authored ClojureScript directly with
the existing Shadow bootstrap and `cljs.js`, then invoke the resulting function
through `seon.eval/lookup-value`. It should not reconstruct a second SCI lexical
environment and should not embed a local Datahike replica.

One coordinate-pinned `seon.db/execute-many` acquires ordinary program-graph
rows directly from the database authority. A pure source constructor turns
those rows into namespace source strings. The existing ClojureScript load
function recursively loads the target namespace's persisted require edges from
that in-memory map. Database access, compilation, and invocation therefore
share the invocation's one complete coordinate without passing a database value
across either process boundary.

Keep one compiler state per agent child and one active invocation per child.
Reuse it when a later invocation sees the same loaded sources. If an
already-loaded namespace source, require edge, function contract, or schema form
changes, retire the child and retry once in a fresh child. Do not implement
mutable namespace unloading: ClojureScript and Shadow keep loaded namespace
registries and emitted vars in process-global state, and re-evaluation does not
reliably remove a definition that disappeared from source.

The invocation's `{function-symbol, source-digest}` is the one entry-function
grant. The current additional set of function symbols is redundant: it only
checks that the same symbol appears twice and cannot constrain calls made by
already-compiled code. Delete that set after callers create the invocation only
after their existing policy check. In the child, independently prove at the
invocation coordinate that the current source has the supplied digest and that
its current source transaction belongs to the invocation's agent and REPL
process. Process isolation and the database function surface remain the
security boundary; SCI is not one.

## Dependency ledger

- Seon `2f3a9b83fccf`: `src/seon/execution.cljs`,
  `src/seon/execution/host.cljs`, `src/seon/eval.cljs`,
  `src/seon/client.cljs`, `src/seon/render/sci.cljs`,
  `src/seon/web/reactive/call.cljs`, `src/seon/schema.cljc`, and the program
  schemas in `src/seon/agent.cljs`.
- ClojureScript `946d75f3483c` under `reference-code/clojurescript`:
  `src/main/cljs/cljs/js.cljs`.
- Shadow CLJS `4e72595f5761` under `reference-code/shadow-cljs`:
  `src/main/shadow/cljs/bootstrap/node.cljs`.
- SCI `b4917436550c` under `reference-code/sci`: `src/sci/core.cljc`,
  `src/sci/interrupt.cljc`, `src/sci/impl/fns.cljc`, and
  `src/sci/async.cljs`.
- Datahike `670cd1ada404` under `reference-code/datahike`; no new Datahike
  operation is required. The existing Seon authority protocol already owns the
  coordinate and result bounds.

The working tree contained concurrent source edits while this read-only audit
was performed. No lifecycle command, build, test, or production-source edit was
made for this report.

## Shortest falsifiers and findings

### Can the child use the existing compiler instead of SCI?

Yes. `seon.eval/init-bootstrap!` creates a fresh `cljs.js` compiler state,
initializes the Shadow bootstrap, loads its analysis caches, and puts compiled
core code on `globalThis` (`src/seon/eval.cljs:569-613`). `cljs.js/eval-str`
accepts an explicit compiler state, eval function, and load function
(`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:1138-1203`). Both
Shadow-compiled and self-hosted functions resolve through the one existing
`seon.eval/lookup-value` path (`src/seon/eval.cljs:641-671`).

The smallest executable falsifier is a cold execution child that receives
ordinary rows for a target namespace and one required agent namespace, compiles
only the target namespace, and invokes the target. It must resolve the required
namespace through the supplied load function without a pod-local connection.

### Does `cljs.js` already own dependency loading?

Yes. Its load-function contract is source-oriented ordinary data:
`{:lang :clj :source string}` or precompiled JavaScript/cache data
(`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:74-103`). `require`
tracks loaded namespaces, asks that function for missing source, evaluates it,
and marks it loaded only after success
(`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:268-335`). Seon's
current `guarded-load` already uses this exact seam for stored agent source
(`src/seon/eval.cljs:996-1074`). The child needs a map-backed implementation of
that branch, not another dependency scheduler.

Persisted `:seon.ns/require-edges` are already the analyzer-derived dependency
facts. Current replay reconstitutes whole namespaces and lets `cljs.js` request
transitive dependencies (`src/seon/eval.cljs:938-994` and
`src/seon/client.cljs:1100-1127`). Reuse that behavior. Do not parse source to
invent another graph.

### Can compiled definitions persist safely?

Only within one unchanged source population in one child. Shadow bootstrap
tracks loaded provides in process-global `env/loaded-ref` and
`cljs/*loaded*`, then evaluates JavaScript through `goog.globalEval`
(`reference-code/shadow-cljs/src/main/shadow/cljs/bootstrap/node.cljs:50-58,
85-120`). Its initialization index is also process-global
(`reference-code/shadow-cljs/src/main/shadow/cljs/bootstrap/node.cljs:177-202`).
`cljs.js` similarly skips a namespace already in `*loaded*`, except for explicit
reload modes (`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:268-313`).

That makes the already-selected one-child-per-agent topology important: never
put several agent compiler states in one Bun process. Within a child, serialize
compilation and invocation as the current host already does. A fresh definition
can overwrite a global, but deleting a definition from stored source does not
delete the old `globalThis` member or all analyzer state. Restarting on a changed
loaded source is therefore both less code and stronger than a custom unload
procedure.

### Is SCI still needed for the invocation boundary?

No. SCI's interrupt hook runs on interpreted function and loop entry
(`reference-code/sci/src/sci/core.cljc:256-287` and
`reference-code/sci/src/sci/impl/fns.cljc:20-75`). Host sequence operations
need optional replacements because they otherwise never hit that hook
(`reference-code/sci/src/sci/interrupt.cljc:1-18`). Current render SCI must
therefore rebuild aliases, expose compiled host values, initialize a fresh
context, interpret the target source, and deep-force the result
(`src/seon/render/sci.cljs:267-361,392-530`). Compiled helpers and arbitrary
native JavaScript still cannot be forcibly stopped by an in-thread timer.

The Bun child supplies the missing hard boundary: the host sends cancellation,
then uses `SIGKILL` after a bounded grace (`src/seon/execution/host.cljs:428-475`).
The child exit affects one agent, not the pod or another child. Direct compiled
ClojureScript should therefore replace SCI for throughput while keeping the
valuable ordinary-result validation and eager realization behavior.

## Exact source acquisition

### One immutable read

For every invocation whose coordinate differs from the child's last verified
coordinate, issue one `seon.db/execute-many`. The protocol already limits a
group to 64 independent members, applies an aggregate result bound, echoes the
exact coordinate, and supports cancellation by the existing request id
(`src/seon/db/protocol.cljc:583-608,853-866,1286-1301`). The CLJS facade already
binds all members to one resolved coordinate and returns their positional
results (`src/seon/db.cljs:2010-2032`). No source-specific authority operation is
needed.

Use these members:

1. One query returns every current `:seon.fn/source` authored by the invocation
   agent's REPL process, its `:seon.fn/sym`, optional `:seon.fn/spec`, and a pull
   of its owning `:seon.ns/name`, optional `:seon.ns/source`, and
   `:seon.ns/require-edges`. The query must join the current source datom's
   transaction to `:seon.db/user` and `:seon.db/process`, following the existing
   provenance idiom in `src/seon/agent/ctx/render_fns.cljs:327-346`.
2. One query returns current `:seon.schema/key` and `:seon.schema/form` rows
   needed for the agent overlay, with the same current-source provenance join.
   The execution artifact's active projection supplies the compiled core forms.
   Build and validate the complete candidate before publishing it, using
   `seon.schema/build-projection` and `activate-projection!`
   (`src/seon/schema.cljc:291-397`).

The first implementation must include a falsifier for that core-plus-agent
schema merge. If database boot forms at the invocation coordinate can differ
from the execution artifact's initial projection, acquire all current schema
forms and function contracts as a third member and build the complete database
projection, matching current replay (`src/seon/client.cljs:978-999,1153-1160`).
Correctness wins over the smaller response until artifact/database identity is
proved.

These query forms and arguments must remain byte-for-byte canonical across
children. Then children at the same coordinate use the authority's shared query
work instead of creating per-child variants. Results are returned only to the
requesting child; there is no broadcast and no parent proxy.

### Pure row-to-source transformation

Refactor the pure part of `seon.eval/reconstitute-ns-source` so it accepts
ordinary namespace/function rows. It should retain the current behavior:

- use the stored namespace form verbatim so aliases survive;
- synthesize a namespace head from persisted require edges only when members
  exist;
- concatenate distinct current function source strings once; and
- exclude test sources from production invocation loading.

The current function couples this transformation to a local Datahike value and
also includes test sources (`src/seon/eval.cljs:938-994`). The new pure function
becomes the one owner used by source acquisition and any remaining replay code.
The execution child stores a map from namespace symbol to the resulting source
string and gives that map to the existing `cljs.js` load seam. Core namespaces
continue through Shadow bootstrap or the already-loaded `globalThis` fallback.

Start compilation with the target namespace only. Let its stored namespace form
and `cljs.js` recursively select the reachable dependencies. Do not eagerly
compile all of an agent's authored namespaces.

### Ordinary internal result

No new durable entity or identifier is required. Normalize the two member
results to existing namespaced facts plus the authority coordinate:

```clojure
{:seon.db/coordinate coordinate
 :seon.execution/function-source-identity
 {:seon.execution/function-symbol 'my.agent.a/render
  :seon.execution/source-digest "<sha256>"}
 :seon.execution/functions
 [{:seon.fn/sym "my.agent.a/render"
   :seon.fn/source "(defn render ...)"
   :seon.fn/spec "..."
   :seon.ns/name :my.agent.a
   :seon.ns/source "(ns my.agent.a ...)"
   :seon.ns/require-edges [{:seon.ns.require/target :my.helpers}]}]
 :seon.execution/schemas
 [{:seon.schema/key :my.agent.a/input
   :seon.schema/form "[:map ...]"}]}

```

`functions` and `schemas` are invocation-local collections, not new database
attributes or protocol operations. Prefer an internal closed Malli schema over
untyped maps. Never include a Datahike database value, connection, compiler
state, JavaScript function, Promise, or Bun object.

## Source identity and capability verification

Compute SHA-256 over the exact UTF-8 bytes of the current stored
`:seon.fn/source`. Bun's existing artifact verifier already demonstrates
`Bun.CryptoHasher("sha256")` and lower-case hexadecimal output
(`src/seon/execution.cljs:408-442`). Before compilation or lookup, require all
of the following:

- the execute-many response coordinate equals the invocation coordinate;
- exactly one current function row matches the qualified symbol;
- the row's current source transaction is authored by the invocation agent and
  `:seon.db.process/repl`;
- SHA-256 of that exact source equals the supplied source digest; and
- the target is reachable from the caller's policy-specific gate.

The last condition belongs at invocation construction. Interactive calls keep
their stricter home-namespace policy (`src/seon/web/reactive/call.cljs:62-117`);
render functions may live in a different authored namespace. Do not force those
two policies into a generic child-side namespace rule. The child enforces the
common proof: exact source identity plus current agent provenance.

The current `::capabilities` value is a set of symbols and the child only checks
whether it contains the target symbol (`src/seon/execution.cljs:36-73,251-318`).
Because the target identity already contains that symbol, the set adds neither
identity nor authority. Replace it with the one source identity rather than
expanding it into another identifier system. A caller that is not permitted to
construct that identity cannot invoke it; a caller that is permitted needs no
duplicate symbol.

## Reuse, invalidation, cancellation, and memory

### Reuse rules

- If the complete coordinate equals the last verified coordinate, invoke the
  already-loaded function without another database request.
- At a new coordinate, reacquire the ordinary rows. Compute SHA-256 for each
  reachable namespace's exact constructed source and each relevant schema form.
- If all already-loaded source digests match, keep the compiler state and invoke
  at the new coordinate. Database reads inside the function remain pinned by
  `db/with-tx-context`, as current execution already does
  (`src/seon/execution.cljs:300-326`).
- If any already-loaded source differs or disappears, return one internal
  reload-required error, retire the child, spawn a fresh child, and retry the
  same invocation once. A second reload request is a core fault, not an
  unbounded retry loop.
- Adding an as-yet-unloaded namespace can be loaded normally. For the first
  implementation, restarting on any reachable-source-set change is acceptable
  and simpler; measurements can later justify the narrower rule.

Keep only the current compiler state, namespace source map, and digest map. Do
not cache multiple coordinates or source generations. The existing 30-second
idle retirement releases all of them (`src/seon/execution/host.cljs:144-176`).

### Cancellation and partial compilation

`cljs.js` compilation and arbitrary emitted JavaScript are not cooperatively
cancellable. Closing the authority session cancels pending database work, but
it cannot make a synchronous compiler or user loop stop. On timeout or cancel:

1. close the child's authority session;
2. mark the child unusable so no partially compiled state is reused;
3. send one terminal error if the event loop can do so; and
4. let the host's bounded grace end in `SIGKILL`.

The current child settles a cancellation immediately after closing its one
session (`src/seon/execution.cljs:336-347`), while the host kills only if the
invocation remains active. That combination can clear the host's active marker
before the kill timer fires. Source-loading integration must fix this: a child
that was canceled during compilation or invocation must exit, and the host must
retire that exact child regardless of whether it already delivered the cancel
value. This is an implementation blocker, not an optional hardening.

### Memory bounds

Apply explicit limits to source acquisition before compilation: maximum result
weight on execute-many, maximum namespace/function/schema row counts, maximum
individual source bytes, and maximum aggregate source bytes. Retain the existing
bounded result serialization and one-active-invocation rule. Eagerly realize
returned lazy data before validation and Transit encoding, preserving the useful
part of `src/seon/render/sci.cljs:499-520`.

Bun process isolation prevents one child's crash or out-of-memory exit from
crashing its parent, but `Bun.spawn` alone is not a hard RSS quota. The current
design bounds retained generations, input/source/result bytes, concurrency, and
idle lifetime; a strict per-child RSS ceiling would require a measured
platform-specific process limit in the supervisor. Do not claim one until that
mechanism is selected and proven on supported platforms.

## Reuse and deletion implications

Reuse and strengthen:

- `seon.eval/init-bootstrap!`, `lookup-value`, and the underlying `cljs.js`
  `eval-str` configuration;
- Shadow's bootstrap loader for compiled/core namespaces;
- persisted `:seon.ns/require-edges` and the whole-namespace source construction
  semantics;
- `seon.schema/build-projection` and atomic activation;
- the execution host's one-agent child, artifact verification, one-active
  invocation, idle retirement, crash containment, coordinate/run-fence result
  checks, and bounded IPC; and
- `seon.db/execute-many`, request cancellation, and the child's direct authority
  session.

Delete after parity:

- `seon.render.sci/fn-source`, `require-info`, `expose-ns`, SCI context creation,
  compiled-helper exposure, interpreter deadline, and fire-and-forget recovery;
- the local-Datahike branch inside source reconstruction;
- production replay of `:seon.test/source` into invocation children;
- direct `lookup-value` invocation from reactive call and other in-pod doors;
- the redundant set-of-symbols `::capabilities`; and
- any timeout path that returns while leaving a canceled child reusable.

SCI may remain as a library for unrelated data-expression use, but it is not the
execution isolation mechanism and should leave the authored render path.

## Exact next implementation owner and proof

One owner should implement this cohort across the already-established execution
mechanism; splitting the compiler state and host invalidation handshake between
lanes would create an assumed contract.

1. In `src/seon/eval.cljs`, extract row-based namespace source construction and
   a map-backed authored-source load adapter around the existing guarded Shadow
   load behavior.
2. In `src/seon/execution.cljs`, add coordinate-pinned program acquisition,
   provenance and digest verification, schema activation, cold target loading,
   warm reuse, eager result realization, and poison-on-cancel semantics.
3. In `src/seon/execution/host.cljs`, add bounded fresh-child retry for the one
   explicit reload-required result and unconditional retirement after cancel.
4. First route authored render invocation through this owner. In the same
   coherent change, delete its SCI reconstruction/execution path from
   `src/seon/render/sci.cljs` and callers. Migrate interaction and eval only
   after this first door proves the shared contract; do not leave a runtime
   fallback.

Add focused tests under the existing CLJS runner, owned with these source paths:

- cold target with same-namespace helper, aliased transitive authored namespace,
  async database read, and an authored schema reference;
- exact-coordinate warm hit with no authority read or compilation;
- newer coordinate with unchanged source reuses compiled code but pins database
  reads to the newer coordinate;
- changed dependency, removed helper, changed require edge, and changed schema
  each cause one fresh-child retry and cannot observe the stale global;
- wrong digest, wrong source author, boot-authored target, cross-agent source,
  and duplicate/missing target are refused before compilation;
- interactive home-namespace policy and render-domain policy both construct the
  same generic invocation without weakening either caller's rule;
- cancellation during authority acquisition, compilation, Promise wait, and a
  synchronous infinite loop retires the child and leaves the pod plus another
  agent child responsive;
- lazy result realization, invalid result, source/result byte bounds, crash,
  idle retirement, and late-result rejection release every request and return
  ordinary errors; and
- two agent children execute concurrently with distinct PIDs and direct
  authority sessions.

The live graduation proof should record cold compile latency, warm invocation
latency, authority request/cache-hit counts, bytes transferred, per-child RSS,
pod RSS, cancellation latency, and RSS after idle retirement. Compare the same
authored render through current SCI and direct compiled execution. The change
graduates only when direct execution preserves output/error behavior, stale
source is unobservable, cancellation is process-bounded, warm latency improves,
and no local Datahike value or SCI environment remains on the path.

## Remaining falsifiers

- A standalone non-function `(def ...)` is not represented by a dedicated
  current program row. Current replay only concatenates namespace, function, and
  test sources (`src/seon/eval.cljs:938-994`). Prove that every data constant
  required by a production function is retained in one of those source strings;
  otherwise extend the existing program graph at the analyzer tee instead of
  scraping live `globalThis` as SCI does today.
- Prove that the artifact's core schema projection exactly matches database boot
  forms for every accepted invocation coordinate. If not, use the complete
  coordinate-pinned schema/function-contract query before optimizing overlays.
- Prove that the execution artifact includes the complete Shadow bootstrap
  assets it locates at runtime. The `:execution` build is a Node script, while
  `init-bootstrap!` reads `out/bootstrap`; packaging must make that dependency
  explicit rather than relying on the pod's working directory.
- Measure whether restarting on any reachable namespace change is sufficiently
  rare. Only if it is material should the implementation distinguish a newly
  reachable, never-loaded namespace from changed loaded source.
- Establish a source-acquisition byte and row limit from measured real agent
  programs. The existing execute-many aggregate bound is necessary but is not a
  compiler-memory guarantee by itself.
