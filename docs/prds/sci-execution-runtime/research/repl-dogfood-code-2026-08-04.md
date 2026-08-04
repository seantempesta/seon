---
type: research
status: complete
tags: [research, repl, agent, sci]
---

# Agent-facing REPL code dogfood — 2026-08-04

## Verdict

The current-source pass reached discovery and the beginning of authoring, then
hit a reproducible bootstrap blocker: the bootstrap's second definition of
`largest` records `:seon.sci.eval/install-source-mismatch` and leaves every
new agent at 9 of 13 receipts. Per the lane's stop rule, I did not rearm,
resume, edit, or work around either agent session. The requested authored
function, test, lifecycle, messaging, transcript, and execution-error probes
therefore remain unverified rather than being green-washed from a stale JVM.

No production source was edited. I read the named bootstrap design,
`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md`,
end to end before probing. I also read the localized runbook and the REPL,
data-oriented Clojure, Datahike, and Clojure-testing skill instructions.

## Environment and method

- Isolated operator root: `tmp/repl-dogfood-0804-root`.
- Scratch cluster: `repldogfood0804`; process `15866`; prepl `57144`.
- Published `current-src` commit ID:
  `6a726510-8594-5e31-b0fa-f07aefd81285`.
- Agents: `dogfood-a` and `dogfood-b`, created through the running cluster's
  real `seon.cluster.agent/ensure-entity!` path.
- Evaluation evidence: ordered run forms, receipt facts, durable error facts,
  and the real agent evaluation return values. No direct host `eval` was used
  as substitute proof.
- Shutdown: `bin/seon --root tmp/repl-dogfood-0804-root stop repldogfood0804`
  completed; the following status reconciliation removed its stale
  advertisement and reported no live cluster.

An initial pass accidentally joined an Aug-03 shared JVM. It produced pre-fix
string definition faces, so all of its semantic observations were discarded.
The private root was initialized from the current tree and is the sole source
of positive claims below. That setup trap is already tracked in
[docs/seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md](../../../seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md).

## Ranked findings

### 1. Blocker — bootstrap redefinition fences every new agent

Exact reproduction:

1. Publish the current tree into an empty isolated operator root.
2. Start a scratch cluster.
3. Create two agents with `seon.cluster.agent/ensure-entity!`.
4. Query each bootstrap run's ordered forms, receipts, closure, and durable
   errors.

For both agents, forms 0 through 8 produced receipts. Form 7 is the first
`largest` definition and form 8 redefines it. Both definition receipts carry:

```clojure
#:seon.print{:face :seon.print/var,
             :name "my.agents.dogfood-a/largest"}
```

The durable fault is:

```clojure
{:seon.error/kind :seon.sci.eval/install-source-mismatch
 :seon.error/message
 "Committed declaration source does not match install request."}
```

Each run remained open with 9 receipts for 13 forms. This blocks the first
agent-authored run, `my.run/wait`, two-agent messaging, transcript settlement,
and the new execution-error face at one shared boundary. The elegant target is
one declaration installation rule in which a same-symbol redefinition commits
and installs the same exact source, then lets the ordered run continue.

Tracked in
[docs/seon/issues/bootstrap-redefinition-fences-agent-runs.md](../../../seon/issues/bootstrap-redefinition-fences-agent-runs.md).

### 2. High — bootstrap help teaches the opposite of the live map rule

The first `(help)` receipt says:

```text
The contract is checked, so write it honestly: input maps must say
{:closed true}, and a return may not be a bare [:maybe ...].
```

The next `largest` contract uses an open map and is accepted. The prose is
therefore not merely stale maintenance detail; it directs every new agent to
author a forbidden contract and contradicts the behavior it immediately sees.
The elegant target is bootstrap guidance that says declared keys are validated
and extra keys are accepted, with separate accurate guidance for durable
function returns.

Updated evidence in
[docs/seon/issues/closed-map-contracts-survive-outside-schema-population.md](../../../seon/issues/closed-map-contracts-survive-outside-schema-population.md).

### 3. High — fresh runtime status cannot render the cluster's problems

The selected private cluster's current MCP status face returned:

```clojure
{:seon.error/kind "seon.instrument/contract-violated"
 :seon.error/message
 "seon.problems/problems violated its contract (invalid-output): #:seon.problems{:error-signatures [#:seon.error{:fact ...}]}"}
```

The diagnostic surface needed to explain the held run is itself unusable. The
elegant target is a bounded, schema-valid problems projection for every durable
error shape. This exact current regression was already recorded by an
independent fresh-cluster pass in
[docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md).

### 4. Medium — a clean boot emits the search owner's contract fault

The fresh private cluster recorded:

```text
seon.search/apply-report! violated its contract (invalid-input):
[[{:value "/Users/sean/src/seon/tmp/repl-dogfood-0804-root/data/clusters/repldogfood0804/derived/lucene",
   :message "invalid type"}]]
```

This is a core fault before the engineering session begins. The elegant target
is one separately declared process index ID whose producer and consumer share
the same shape. It is already tracked in
[docs/seon/issues/search-index-property-collides-with-process-index-id.md](../../../seon/issues/search-index-property-collides-with-process-index-id.md).

### 5. Medium — operator status says the proven prepl is unreachable

After the cluster had served the agent creation and receipt queries on prepl
port 57144, operator status printed:

```text
roster unreadable: A recorded JVM is alive but its prepl is unreachable; the
offline reader was not allowed to contend for its flock.
```

The elegant target is one readiness observation that cannot describe the same
process as both reachable and unreachable. This independently reproduces
[docs/seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md](../../../seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md).

## Faces that held up

### Agent creation is exactly the promised four-field value

Both creations returned ordinary data without a transaction report spill:

```clojure
{:seon.cluster.agent/id "dogfood-a"
 :seon.ns/name my.agents.dogfood-a
 :seon.cluster/name "repldogfood0804"
 :seon.cluster.run/id "bootstrap:dogfood-a"}
```

`dogfood-b` returned the same four keys with its own IDs. Commit `89fe1a287`
therefore reaches the real caller face.

### Discovery is compact and useful

The bootstrap discovery receipts returned:

```clojure
(dir my.run)
complete
wait
nil
```

The bootstrap design's find-functions-by-schema query returned:

```clojure
#{["my.run/complete"]}
```

This is a good answer: small, query-derived, and free of maintenance metadata.

### `doc my.run/complete` reads as agent guidance

The current doc face begins:

```text
Finish this run with a reply for its requester.
Takes non-blank reply text ...
```

It then shows the graph-resolved input and output contracts. Nothing in the
docstring reads like a maintenance diary. The rewrite in `ed41a90f7` reaches
the real bootstrap receipt. No other `my.*` docstring was reached before the
bootstrap fault, so no broader claim is made.

### The new definition face reaches the real receipt

Both the first definition and the redefinition returned:

```clojure
#:seon.print{:face :seon.print/var,
             :name "my.agents.dogfood-a/largest"}
```

This verifies the new `d6329faa4` Var face on current source. It is compact,
names the installed symbol, and avoids the old prose string. The update face
is currently indistinguishable from the initial definition face; whether that
is sufficient could not be judged because the update then fenced the run.

## Requested matrix

| Probe | Current-source result |
|---|---|
| `dir`, `doc`, source/schema discovery | Verified through bootstrap receipts |
| Four-field creation face for two agents | Verified |
| New Var definition face | Verified twice |
| Open argument map | Accepted by bootstrap form 7; stale help contradicts it |
| Uncontracted `defn` and persistence explanation | Not reached |
| `:any` refusal | Not reached |
| Bare `[:maybe ...]` refusal | Not reached |
| Repaired authored function, call, arity, edge errors | Not reached |
| Authored redefinition/update semantics | Blocked by bootstrap redefinition |
| Write and run a small test | Not reached |
| `my.run/wait` | Not reached |
| Message exchange between the two agents | Agents created; exchange not reached |
| Settled transcript faces | Not reached |
| New `c91de41a5` execution-error face | Not reached |

The discarded Aug-03 shared-JVM pass displayed the praised 08-01 `:any` and
bare-maybe refusals and let an uncontracted Var resolve without a
`:seon.fn/spec`, but it also returned the obsolete string definition face.
Those observations are explicitly not current-source evidence and are not used
to close any requested probe.

## Verification boundary

All three owned Markdown files pass `seon.dev.markdown/validate-file`, and
`git diff --check` reports no whitespace defects. `bin/issues-index --check`
is blocked at the shared schedule boundary. Another lane already has
uncommitted changes in `docs/seon/issues/index.md` and four untracked issue
notes; the checker reports their four missing rows plus the new bootstrap
issue's missing row:

```text
time-limit-face-exposes-interpreter-interrupt-marker.md: missing-schedule-row
contract-violation-serializes-print-tree-inside-error-data.md: missing-schedule-row
nested-error-data-hides-the-throw-site-message.md: missing-schedule-row
bootstrap-redefinition-fences-agent-runs.md: missing-schedule-row
mcp-door-top-level-string-bypasses-value-window.md: missing-schedule-row
```

I did not edit or commit the overlapping schedule because doing so would absorb
the other lane's in-flight diff. This blocks only the index gate, not the
path-limited commit of this lane's coherent evidence.

## Graduation boundary

The next honest dogfood pass begins only after
[docs/seon/issues/bootstrap-redefinition-fences-agent-runs.md](../../../seon/issues/bootstrap-redefinition-fences-agent-runs.md)
is repaired on a newly published isolated cluster. Its first falsifier is two
fresh agents with 13/13 settled bootstrap receipts and no durable install
fault. Only then can the remaining authored code, test, lifecycle, message,
transcript, and execution-error faces be evaluated without mutating a fenced
session.
