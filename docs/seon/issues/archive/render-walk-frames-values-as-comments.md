---
type: issue
status: resolved
severity: friction
tags: [issue, render, context, architecture]
---

# Make the rendered walk an ordinary REPL value

## Problem

The walk AI assembly places a `;;` header before every rendered unit and uses
more comments for the call description, branch metadata, elision guidance, and
volatile-context marker. The framing makes real computed outputs look like
source comments.

## Evidence

`seon.render.walk/prose` documents and implements the comment-per-unit contract
at `src/seon/render/walk.clj:549-652`. The exact `;; d`, call, branch, elision,
and metadata strings are built at `:590-651`. `src/seon/render.clj:777-782`
then appends a separately assembled REPL-state line. The superseding ruling is
decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

The strict-dogfood audit on 2026-08-12 confirms this is also a ruling-28
context-assembly violation: the comment headers, branch guidance, and volatile
marker are neither declared render outputs for reached values nor executed
receipts. The provider path now uses `seon.render.walk/history`, but an agent
calling the public `seon.render/walk` still receives this manually assembled
surface.

## Owner

`seon.render.walk` owns assembly of the rendered neighbourhood value.

## Acceptance

Calling `seon.render/walk` displays the call as a form and the rendered
neighbourhood as its actual computed value. Unit identity, branch handles,
elision guidance, and volatile metadata remain queryable and visible without
comment prefixes or decorative comment framing.

## Recurrence — live default cluster, 2026-08-10

Still present verbatim in root's captured `:seon.render/ai` bytes (pid
31570, run `2ddfec05-01ba-4957-a65a-d310e85daad2`, basis `536871204`). The
header is exactly the banned `;; =>` shape — a form, an arrow, and its result
inside a comment:

```text
;; (seon.render/walk {:root [:seon.cluster.agent/id "root"], :depth 2}) => root=[:seon.cluster.agent/id "root"] depth=2
;; Some branches are elided · inspect with (seon.render/walk {:root [:seon.cluster.agent/id "root"], :depth 3})
…
;; Volatile context metadata
;; branches-elided=9 elided-tokens=110
;; unit=25752 branch=[:seon.render.walk/neighbours 0]
;; REPL state namespace=my.agents.root basis=536871204 time=#inst "2026-08-10T19:38:48.066-00:00"
```

The error path uses the same shape (`door`-mode `(seon.render/walk)`):

```text
;; (seon.render/walk) => error
No calling agent is bound to this evaluation.
```

All 18 unit bodies are preceded by `;; d<n> · <lookup>`. Line numbers have
moved since the original note: `prose` is now
`src/seon/render/walk.clj:568-671`, and the comment strings are built at
`:606-635`. Measurement context:
[context quality audit 2026-08-10](../../prds/sci-execution-runtime/research/context-quality-audit-2026-08-10.md),
finding 5.

## Resolution

Resolved at the outward crossing by `4bc8104d8`. The public
`seon.render/walk` no longer calls the protected historical
`seon.render.walk/prose` assembler. It returns one printed ordinary map with
the lookup, distance, selected units, optional branch, and REPL state; its
error arm returns a printed flat error value. Prompt assembly already uses the
history projection, and its focused prompt regressions prohibit the former
`;; (seon.render/walk` and `;; REPL state` framing.
