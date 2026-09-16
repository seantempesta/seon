---
type: research
status: active
tags: [research, render, class/n1, verification]
---

# N1 result substitution — 2026-09-15

## Result and exact boundary

Implementation commit: `563034709` on `steward-platform`.

**AI result maps cannot select a block renderer: the existing structural
printer renders their attributes and the profile's ordinary elision values.**
This removes the shared construction that replaced a pulled function with a
stale-Var instruction, config with prose, and a turn with an empty string.
Explicit block calls still select their schema pair; HTML selection is unchanged.

The broader N1 umbrella stays open. Operator output, fork logging, and
database-diff prose are outside this bounded assignment. The latter is now
named in [the residual issue](../../../seon/issues/database-diff-renderer-appends-prose-elision.md).
No change to `seon.render`, `seon.render.walk`, schema keys, or protected
turn/evaluation owners was required.

## Authorities and dependency ledger

Read end to end: `AGENTS.md`, `docs/seon/issues/README.md`, the N1 class
note, all three assigned member notes, and
[N1 member verification](n1-member-verification-2026-09-16.md).
Read the class-mining N1 row and structural-kill column. Read the value,
selection, and walk owners; used data-oriented-clojure, repl, datahike, and
clojure-testing skills. The current plan entry and working-edge record
establish orchestrator-only batched gates.

| Mechanism | Dependency / existing seam |
|---|---|
| Fresh private SCI context | `reference-code/sci/src/sci/core.cljc:346` fork; `src/seon/sci/eval.clj:1734` fork-for-turn |
| Real pulls and immutable custody | `seon.db/db` and `seon.db/pull`, explicit `(seon.operator/connection "default")` |
| Result presentation | `src/seon/sci/eval.clj:1985` shown-result → value/prepare |
| Structural map traversal | `src/seon/render/value.clj:260` value-node* |
| One elision constructor / grammar | `src/seon/print.cljc:959` elision, `:455` render-elision-ai, `:1265` fit |
| Canonical fixture | `test/seon/test_support.clj:669` with-database, `:300` fork-cluster-ctx |
| Armed in-process result recording | `src/seon/test.clj:76` run |

History inspected included `c3a8d0f01`, `28e955327`, and `b80f78a7c`
at the value owner. The existing identity projection remains before structural
traversal; this slice does not expose a database object's host internals.

## Baseline and live proof

Default PID 69622, MCP JVM mode. Before production edits, the complete
carried render request reproduced:

| Real pulled entity | Raw characters | Old shown characters | New characters | New estimated tokens | Fresh-context evaluation ms |
|---|---:|---:|---:|---:|---:|
| `[:seon.fn/sym "seon.db/q"]` | 9,655 | 100 (false stale-Var instruction) | 2,725 | 851 | 301.346 |
| `[:seon.config/cluster "default"]` | 3,460 | 179 (prose) | 1,731 | 540 | 25.876 |
| `[:seon.turn/id "62febc6305ee"]` | 4,722 | 0 | 948 | 296 | 49.279 |

The new values came through `seon.sci.eval/evaluate` with real pulls in a
fresh context, on default read-only. All three evaluation values equal the
original pulled maps; each shown value is a nonempty attribute map with
counts, paths, offsets, and requery forms. The retained keys are actual
attributes; omission remains profile-controlled, so not every attribute is
shown in the first window.

[Exact probe forms and returned bytes](n1-render-substitution-evidence-2026-09-15.json)
are committed. Its poll form runs on the canonical isolated fixture, not on
default's domain entities. Its three pull forms only read default.

The test binds the result handle in its private context and executes every
emitted requery form against the original object. Test-only fixed handle
spelling is a supplied root, not a new identity generator.

## Per-member verdict

1. **Entity pull substitution: resolved by `563034709`.** The three failures
   above are one selector mistake, removed at value-node*. The class
   regression adds a full real effect entity to the same assertion.
2. **Two elision representations: superseded with explicit residual.**
   `563034709` deletes the AI prose tail and HTML capped paragraph. Before
   the change, manually setting the prepared projection's truncated flag
   appended the English twin; afterwards it leaves text byte-identical.
   The current display-value supplies the entire value, so that flag does
   not represent a separate active paging omission. The existing print tree
   already carries the real cuts. `seon.db/render-diff-ai` still has its
   separate prose tail; the residual issue owns it.
3. **Background poll cost: superseded by current shape and profile contract.**
   Today's poll accepts one ref and returns a narrow descriptor, which does
   not satisfy the full effect schema pair. The earlier full-effect probe
   therefore was not a real poll. Real poll evaluation on canonical stored
   effect entities now measures **130 estimated tokens for both 8,000 and
   80,000 payload characters**, retaining id and duration and eliding only
   the payload. The full effect entity's result-substitution path is also
   removed by this slice. Direct block `seon.effect/render-ai` still prints
   its payload; this change makes no claim about that explicit block API.

## REPL-first sequence and recorded in-process runs

Before editing files, evaluated new forms for `value-node*`, `prepare`,
and `render-ai-data` through MCP JVM mode, called them with real default
data, and ran the class regression. Re-armed with `seon.instrument/apply!`
using default's carried projection: **1,057 registered / instrumented**.
Then ran the regression again before editing.

Every test below used exactly:

```clojure
(seon.test/run #'<namespace>/<test> (seon.operator/connection "default"))
```

| Test | Before file edit / development run | After adoption |
|---|---|---|
| `seon.render.value-test/result-maps-retain-attributes-through-real-evaluation` | 64/0/0 at 23:47:38Z and armed 23:47:55Z; added token assertions: 68/0/0 at 23:53:44Z | 68/0/0 at 23:54:20Z, run eid 67228 |
| `seon.render.value-test/block-pairs-remain-explicit` | 15/0/0 at 23:48:57Z | 15/0/0 at 23:54:24Z, eid 67229 |
| `seon.render-simplification-test/nested-ai-values-retain-data-and-html-uses-declared-faces` | 11/0/0 at 23:49:40Z | 11/0/0 at 23:54:27Z, eid 67230 |
| `seon.render.value-test/background-poll-keeps-identity-while-payloads-grow` | 15/0/0 at 23:52:52Z | 15/0/0 at 23:54:30Z, eid 67231 |

Numbers are passed assertions / failures / errors. Final focused result:
**4 tests, 109 assertions, 0 failures, 0 errors**.

Intermediate outcomes were read, not hidden: the old nested test expected
prose (6 passes / 4 failures / 1 error); its initial replacement tried to
read the fixture's invalid keyword `:seon.test-support.fixture/1` as EDN
(0/0/1), now [recorded separately](../../../seon/issues/fixture-branch-keywords-are-not-readable-edn.md).
The first poll fixture omitted required effect refs (3/12/0); it was
corrected to complete real agent/turn/effect entities before file edits.
Two hand-assembled probe forms had delimiter errors and were corrected;
they did not execute. The initial source/current probe omitted its store
argument and was corrected using the live instance's store.

## Adoption, gates, and remaining boundaries

- Adoption was observed at `6aa9d9dc-366b-543c-bb78-501397725cab`;
  current-src and default agreed and the stored value-node* source contained
  the new rule. Later convergence was
  `6aa9dab3-55a0-5e7c-9f0f-2499a3b803cd`.
- First hook publication hit the existing source-offset issue:
  `Range [44072, 45681) out of bounds for length 45615` at
  `seon.fn/exact-source`. Another hook invocation reported no running
  operator JVM while MCP still answered. Later normal publication converged;
  no restart/refork/stop or foreign repair was performed.
- Automatic post-adoption check hit its 120,000 ms bound, pending
  `my.message-test/a-contract-forbidden-argument-is-a-value-the-agent-reads`.
  This is [recorded as an observation](../../../seon/issues/post-adoption-check-times-out-on-message-contract-test.md),
  not attributed to this slice or another lane.
- No test JVMs or bin/test-fast were launched. Per the assignment's
  orchestrator-gate instruction and active orchestrator-only marker,
  `bin/test --paths ... -- seon.render.value-test seon.render-simplification-test`
  and the platform gate remain **pending orchestrator execution**.
  `tmp/orchestrator/gate-requests/n1-render-substitution.txt` contains only
  those two existing namespaces.
- `git diff --check` passed. Kondo found no new errors after adding the
  explicit clojure.edn require; existing shadow/redundant-let warnings remain.
  Repository Markdown hooks report 12 existing pin-citation errors in
  `agents-md-audit-2026-09-15.md`; that foreign evidence file was not edited.
- Foreign edits were preserved: initially turn.clj/fn_test.clj; later
  render/walk.clj, test.clj, sci/eval_test.clj, and test_support.clj.
  No browser paint claim, paid provider call, scratch cluster, worktree,
  or lane-owned background shell.

The owner must reconcile the issue index for the three archive moves and
new residual notes; this lane did not edit the owner's ranked schedule.
