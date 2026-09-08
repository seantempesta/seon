---
type: research
status: complete
date: 2026-09-08
tags: [research, test, instrumentation, contract, fixture]
---

# The instrumented gate's backlog, classified and drained

Written by the `instrumented-gate-backlog` lane against
[AGENTS.md](../../../../AGENTS.md) §2.4, §5 and §7,
[the refusal-class issue](../../../seon/issues/agent-facing-refusals-are-asserted-where-contracts-refuse-first.md),
[the repair-2 landing note](storage-bound-repair-2-landing-2026-09-07.md)
(§8.2, the instrumented gate), the `tmp/repair2/all2.log` tally, and the
`clojure-testing` skill — each read end to end.

## 1. The starting tally

`bin/test --all` at `4ec6bd82a`, the first run with the gate arming the same
contract instrumentation a live cluster arms (`07394e485`):

**1,439 tests, 11,374 assertions, 314 red** (227 failures, 244 errors) —
255 uncaught `invalid-input` contract violations and 11 `invalid-output`.

Attributed one function at a time, the violations were NOT 314 problems:

| violated function | count | class |
|---|---|---|
| `seon.cluster.loop/turn` | 63 | fixture: handle missing `:seon.cluster.loop/completion` |
| `seon.sci.kernel/context-projection` | 24 | **production**: an absent ctx read unguarded |
| `seon.error/prepare` + `seon.error/commit-tx` | 16 | **production**: an undeclared handle member |
| `seon.fs.jvm/*`, `seon.edit.jvm/edit`, `seon.web.jvm/*` | 20 | fixture: hand-rostered config |
| `seon.cluster.run/settlement-projection` + `receipt-settle-tx` | 11 | fixture: partial handle, partial rows |
| `my.run/*`, `my.message/*` | 12 | test: agent-facing refusal asserted at the wrong boundary |
| `seon.render/render-ai`, `/selection`, `/render-call`, `/request-profile` | 12 | fixture: stored nils and an enum value that never existed |
| everything else | ~120 | one or two per seam |

## 2. The four classes, and what each one turned out to be

### (a) A fixture handing a shape the contract forbids — the bulk

The dominant instance was one map: `:seon.cluster.loop/cluster`, built out of
the members a given assertion happens to read. `seon.cluster.loop/turn` alone
was refused 63 times for a missing `:seon.cluster.loop/completion` — a
channel every armed agent carries and no turn test reads.

The repair is one choke point, `seon.test-support/cluster-handle`: the three
declared channels plus the admission caps and five dials, all from the
SHIPPED decisions (`seon.config/defaults`), with the cluster's own identity —
connection, name, process, SCI ctx — left to the caller, and the caller
always winning. Six suites use it; no suite carries structural constants of
its own any more.

The second instance was hand-rostered config. `seon.fs.jvm`'s five handlers,
`seon.edit.jvm/edit` and `seon.web.jvm`'s fetch and search all declare
`:seon.config/effective` — the whole compiled decision set — and three suites
handed them a map of the eight or three dials they read. Each policy now
compiles the shipped defaults and changes only what it varies, so a new
config member cannot silently drop out of coverage.

The third was stored nils in optional keys, the same shape BR1 fixed in
`seon.print/elision-node`: a settle request built as a literal map over
`(:seon.cluster.eval/result-edn evaluation)`, a nil owning namespace on a
render request, a nil `:seon.sci.admit/caps`, `(:seon.blob/staged-writes
result)` passed through where production always publishes a vector.

### (b) A test pinning a refusal the contract now makes first

Decided once, and decided by WHO the refusal is for:

- `my.run` and `my.message` are AGENT-FACING, and **an agent never invokes a
  Var** — its reply is read into forms that cross `seon.sci.eval/evaluate`.
  So `seon.test-support/agent-value` drives one form through that boundary
  and the assertions are about the flat value an agent genuinely reads. The
  direct-call tests keep exactly the inputs the contract ADMITS (a blank but
  non-empty string), where the function's own typed refusal is the subject.
  This is the shape the issue asked for, and it is now available to every
  suite;
- system-side functions (`seon.db/pull-many`, `seon.error/commit-tx`,
  `seon.operator/start!`, the call-preparation probes) assert the typed
  contract refusal as the value it is, naming the same function. In the
  caller-wins case the refusal is a STRONGER proof than the old boolean: the
  offending argument in the diagnostic is the caller's own value, unreplaced,
  observed one frame before the body could have computed a boolean about it.

### (c) Production defects the contracts exposed

Six, all of them "a check that reports health because its subject was never
asked" in a different costume:

1. **`:seon.cluster.loop/cluster` never declared
   `:seon.config.error/max-evidence-bytes`**, which `seon.cluster.loop`,
   `seon.schedule` and `seon.cluster` all read OFF THE HANDLE and hand to
   `seon.error/prepare`, whose contract requires an integer. Boot has always
   supplied it; leaving it undeclared meant a handle without it validated
   right up to the moment a fault had to be recorded — **the failure path,
   and only the failure path, broke**. Declared now, and defaulted in
   `cluster-handle`.
2. **`seon.render/project-node*` read `:seon.sci.eval/ctx` off a render unit
   and handed it to `seon.sci.kernel/context-projection`.**
   `:seon.render/unit` declares no ctx — the value floor's own entry points
   take a bare value — and `seon.render.value/registered-layout`, three
   functions away, already guards the same read the same way. Every ctx-less
   floor render was a thrown contract violation at the one boundary §2.4
   requires a value from. The repair says the whole thing once: **no
   projection means nothing is declared and nothing is selected**, because
   both halves of selection (which shapes this value matches, and whether the
   producer's output satisfies the output schema) are projection questions.
3. **`seon.cluster/mcp-value` wrote `:seon.schema/projection` into the
   admission request as a PRESENT NIL** whenever the MCP instance held no SCI
   ctx, so every storeless MCP evaluation refused — the same
   optional-key-present-as-nil shape as BR1.
4. **`script/seon/dev/docstring.clj` emits a `:comment-shaped-result`
   finding** (ruling 45) that its own `rule-schema` never declared, so
   `check-source` violated its output contract on any source carrying one.
5. **`:seon.render.profile/composition
   :seon.render.profile.composition/context`** appears in two suites and is
   not one of the three values the enum has ever had, so
   `seon.render/request-profile` violated its own output contract on every
   request carrying one. (Fixture-side, but the enum is the authority and the
   invented value had propagated.)
6. **`seon.render.web/join-package`'s package keys** must be
   `:seon.render/surface-id` (nine characters minimum) and a fixture used
   `"one"` — the declaration is right and was never asked.

### (d) `seon.instrument-test` under an armed JVM

`apply!` IS the arm: boot calls it on a JVM with no contracts installed, and
that is the only state in which its own `:seon.instrument/invalid-mode`
refusal is reachable. The test now reproduces boot's state
(`instrument/remove!` first, with the suite's snapshot/restore fixture
putting the worker's wrappers back), then arms and asserts the contract's
refusal at the same crossing. Its three other reds were ordinary fixture
defects: a nil `:seon.sci.admit/caps`, a projection state holding nil (the
declared predicate is `deref`, which an atom holding nil fails), and
`wrap-interpreted` handed nil caps.

## 3. Commits

| commit | subject |
|---|---|
| `2fa2e1e17` | Hand every fixture the cluster handle its contract declares |
| `063092435` | Hand the filesystem, edit and web handlers their whole effective config |
| `d934f4355` | Assert the refusal the armed boundary actually gives |
| `f325d9dfd` | Stop handing an absent ctx and an absent database to functions that require them |
| `8a2e4d495` | Hand the render service and the program rows their declared members |

## 4. What remains

(filled in from the final `--all` below)
