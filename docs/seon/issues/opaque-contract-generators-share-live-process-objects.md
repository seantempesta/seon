---
type: issue
status: open
severity: cleanup
tags: [issue, runtime, schema, test, class/p1, wave/contract-generator]
---

# Stop opaque contract generators from sharing live process objects

## Problem

Several opaque runtime schemas advertise generators backed by one delayed live
object for the lifetime of the JVM: a bound prepl socket, an http-kit server, a
core.async mult/channel, and an SCI context. Generating or mutating one sample
changes the sample every later contract check receives; the server generators
also bind real process sockets even though no cluster owns their lifecycle.

The same defect was filed for Flow handles in
[[archive/flow-generators-reuse-one-mutable-sample]], now resolved. This note
owns the remaining cluster, web, and SCI samples rather than widening the Flow
owner silently.

## Evidence

- ~~`src/seon/cluster.clj:71-75` returns one delayed `ServerSocket`
  forever.~~ **Fixed in `ac831976b`**: `socket-server-generator` constructs a
  fresh UNBOUND `ServerSocket` per generation, so ordinary loading and ordinary
  generation bind no port and open no descriptor.
- `src/seon/render/web.clj:91-100` returns one delayed bound http-kit server;
  `:112-117` returns one delayed mult/channel. **STILL OPEN** — the file was
  owned by a running lane during the 2026-08-07 census cleanup pass.
- `src/seon/sci/eval.clj:155-160` returns one delayed mutable SCI context.
  **STILL OPEN** — same reason;
  `test/seon/custody_stability_test.clj:190,204` also names it.
- The remaining two are `defonce` process objects outside any cluster instance
  and have no per-sample teardown path.

## Owner

The named opaque schema predicates and their schema-driven generator policy.

## Acceptance

Opaque lifecycle objects are either explicitly nongenerative or generated
fresh under a cleanup scope that closes sockets, servers, channels, and
contexts as applicable. Invalidating one sample cannot affect the next sample
or any running cluster, and ordinary namespace loading binds no diagnostic
socket.

`seon.public-contract-test/lifecycle-generators-make-a-fresh-sample-each-generation`
is the standing regression; the two remaining samples join its table when
their owners land.

## Re-verified at HEAD a5cb41426 (2026-09-26, research-system-state-opinion)

Still open, now at new lines:

- `src/seon/render/web.clj:115-124`: `generator-server` is a `defonce` `delay` of
  `(http/run-server … {:ip "127.0.0.1" :port 0})`, handed out as the same bound server by
  `server-generator`.
- `:136-141`: `generator-mult`, one shared mult.
- `src/seon/sci/eval.clj:183-188`: `generator-ctx`, one shared `sci/init` context.

Under the state architecture adopted in 39a026318 (one roster per scope), this server is a live host object that no
roster holds. `boot/stop!` and `web/stop!` never reach it, so it holds a loopback port and its worker threads until the
JVM exits once any generation realizes it. This is the only http-kit server in the process that no cluster instance owns.
Inventory row: `docs/research/agent-platform/system-state-opinion-2026-09-26.md` §3.1.

## SCI sample: the design (2026-09-26, fix-turn-test-reds; read-only, edit sequenced by root)

**What `generator-ctx` serves.** Only contract generation. `seon.sci.eval/ctx-generator`
(`src/seon/sci/eval.clj:186-188`) is the `:gen/gen` of the `:seon.sci.eval/ctx` schemas
(`resources/seon/schemas/seon.sci.eval.edn:192`, `:197`). `test/seon/public_contract_test.clj:95` lists it with
`sci.eval/ctx?`, and `test/seon/schema_test.clj:287` names it. No runtime path reads the context. The
`defonce ^:private generator-ctx` (`:183-184`) exists only so every generated sample is the same delayed
`(sci/init {})`.

**Why it matters beyond sample sharing.** A top-level `defonce` is a program declaration, so installation binds
it into agent SCI: `@@#'seon.sci.eval/generator-ctx` returns the live context
([agent-sci-reaches-the-private-generator-context](agent-sci-reaches-the-private-generator-context.md)). Live host
objects belong in a roster, not in Vars. This one needs no owner at all.

**Move: derive the sample per generation; delete the Var.** A fresh `(sci/init {})` costs 0.04 ms (20 samples in
0.81 ms, all distinct and all `seon.sci.eval/ctx?`, default 33ea69298). This is the same shape as the
`socket-server-generator` fix (`ac831976b`).

```clojure
;; src/seon/sci/eval.clj:183-188
-(defonce ^:private generator-ctx
-  (delay (sci/init {})))
-
-(def ctx-generator
-  "A real ctx — honest by constructing an instance."
-  (gen/fmap (fn [_] @generator-ctx) (gen/return nil)))
+(def ctx-generator
+  "A real ctx, honest by constructing a fresh one per sample: no Var holds a
+  live context, and no sample's mutation reaches the next."
+  (gen/fmap (fn [_] (sci/init {})) (gen/return nil)))
```

Installation then needs no privacy rule. In the same slice:
- `test/seon/custody_stability_test.clj` drops `seon.sci.eval/generator-ctx` from its forbidden roots (`:261`) and
  from the deref sources (`:275`), a test of deleted machinery. The three operator roots stay.
- `test/seon/public_contract_test.clj`'s lifecycle table gains `[sci.eval/ctx? sci.eval/ctx-generator]` with a
  fresh-sample assertion (two samples are not `identical?`), the issue's standing regression.
