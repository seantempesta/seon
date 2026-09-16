---
type: research
status: awaiting owner decision
created: 2026-09-16
tags: [research, schema, database, agent]
---

# Issue family: verified ownership boundary

The requested guarantee is: every indexed issue follows publication and a
started issue retains its success tests until verified settlement resolves it.
That guarantee requires changes outside the assignment's owned paths. No
production edits were made. This is the assignment's pre-edit design gate,
not its conditional stop after slice 2: agent creation itself is reusable.

## Authorities and scope

Read AGENTS.md and docs/seon/issues/README.md end to end; read
docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md end to end,
the namespace-data-model's required sections 0, 3.3, 8 and 9, and the task
prototype including sections 1–4. Read the existing script and CLI end to
end. Loaded data-oriented-clojure, data-modeling, datahike, repl,
clojure-testing and datastar-web-ui skills. Read the active roadmap entry
and the class-mining structural-kill table. The specific issue-family
assignment names no class issue or member list; no class/member closure is
claimed from the generic lane preamble.

Baseline HEAD: `3c35a62127424075c2c7f585fb88e04e10c652c7`, branch
`steward-platform`. Concurrent edits were present in program, turn, test,
schema bridge and rendering owners. They were preserved.

## Dependency ledger and exact seams

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:1152`
  applies a transaction function to its current transaction database.
  `src/seon/cluster.clj:2272` demonstrates composition of agent creation
  and generated opening in one transaction function.
- `src/seon/cluster/agent.clj:121` exposes `creation-tx`;
  `src/seon/turn.clj:729` exposes `generated-run-tx`. Both use the same
  `namespace:<symbol>` tempid. No cluster edit is needed merely to compose
  these public functions. The creation contract also requires
  `:seon.cluster/name`, omitted by the prototype's abbreviated example.
- `src/seon/program.cljc:849` owns exact attribute replacement. Reuse is
  possible without editing this protected owner; preserve the issue
  identity and separate indexed attributes from worker-authored attributes.
- `src/seon/cluster.clj:1383` owns complete source population, with
  `seon.fn/index!` at line 1419. `source-roots` at line 1449 determines the
  digest inputs. Issue markdown is absent from those roots.
- `src/seon/cluster/source.clj:448` owns incremental source publication;
  `src/seon/cluster.clj:1935` onward separately adopts schema/program facts
  into default. Calling a new issue indexer only from the CLI would not
  establish either automatic publication or development adoption.
- `src/seon/db.clj:2810` checks map assertions and `:db/add`, not
  retractions; `transact-call` at line 2818 then submits to Datahike.
  An additive `my.issue/tests!` API does not prevent a worker using the
  already callable database API to retract a test, retract the attribute,
  or retract the entity. A writer invariant needs coverage of expanded
  transaction functions as well as direct operations; a caller pre-read
  is insufficient. No such invariant is declared by the proposed schema.
- `src/seon/plan.clj:554` records step completion only. The turn owner
  invokes `plan/settle-call` at lines 2204, 3423, 3462 and 4642.
  Test execution belongs before the settling transaction, under the
  existing execution bound; it must not run within a Datahike transaction
  function. Writing issue `resolved-tx` also needs the completion seam.
  The issue namespace alone cannot insert these behaviors.

## Live evidence

All probes used MCP JVM mode on default with explicit
`(seon.operator/connection "default")`. No production definition was
evaluated or changed; no transaction was submitted. The script containing
the successful forms is the adjacent
`issue-family-probes-2026-09-16.clj`.

`bin/seon status`: default alive, PID 69622, prepl 55914, web port 7994,
no orphan Seon JVMs. MCP runtime status: 4 error signatures, 31 errored
evaluations (tool key `errored-receipts`), 1 failed run, 17 stale Vars.
These are inherited observations, not attributed to a cause.

At basis 536872692 the complete small probe returned:

```clojure
{:basis 536872692
 :issue-declared? false
 :retraction-preflight nil
 :source-roots ["src" "test" "config/default.edn"]
 :verified-arglists ([database test-symbol]
                    [database test-symbol program-digest])}
```

The retraction probe only called the pure preflight on an existing agent's
plan attribute; it did not retract anything. It establishes preflight's
absence of a retraction check, not a completed issue-retraction experiment
(the issue schema is not installed).

The creation/opening probe returned 3 and 2 transaction entries,
respectively, including `steward-call` and `open-call`. Its envelope was
windowed (4789 bytes, blob digest
`a7ec67f9e2e155e0119a3ba95bfb0da60f43ad3f2c593374dc34ed9d0ce5e645`),
so it is evidence of returned transaction shape, not an atomic commit
proof. The small follow-up evidence above was not windowed.

Two exploratory forms failed before mutation: creation without required
cluster name, and `contains?` on Malli's composite registry. Corrected
forms use the complete creation request and `malli.registry/-schema`.
The live `verified?` metadata has two arities while the source file read
still has three arguments only; do not infer that reach-digest is landed
from live metadata. Verify semantics and adoption with that lane's final
commit before choosing the final completion predicate.

## Three priced options

Estimates below are engineering effort, not measured runtime.

1. **Index and inspect first — recommended, simplest viable constraint.**
   Authorize narrow changes to publication/digest/adoption owners, then
   deliver schema, exact indexing, CLI refusal report and query/render
   surfaces. Keep worker start and automatic resolution out of this
   checkpoint. Guarantee: issue rows and refs follow the selected source
   publication. Cost: approximately 4–8 hours plus serial integration.
   Give up: executing issue workers in this checkpoint.
2. **Deliver the full guarantee across owners.** Authorize coordinated
   publication, database writer and plan/turn settlement changes alongside
   the issue family. Prove non-removal through direct operations and
   transaction functions, then automatic verified settlement and virtual
   turn behavior before the paid session. Cost: approximately 1–2 engineer
   days plus integration, depending on the writer constraint mechanism.
   Give up: the current lane's path isolation and independent landing.
3. **Explicit indexing and cooperative worker API only.** Keep current
   path ownership; provide explicit `index!` and additive `tests!`, with
   manual resolution. Change the acceptance criteria to state that direct
   database retractions remain possible and note-only edits do not publish
   automatically. Cost: approximately 4–8 hours. Give up: the requested
   structural guarantee and automatic publication/settlement. This is a
   narrower product, not completion of the approved spec.

The decision is required by AGENTS.md's owner design gate and the
assignment's equivalent instruction to stop before hours of cross-owner
work. The prohibited files are explicit in the spec's section 5. No skill
introduced an additional approval requirement.

## Verification boundary and handoff

No implementation slice, schema installation, in-process regression,
test JVM, canonical gate, virtual worker turn or provider session ran.
There is no claim of completion. No default stop/refork/restart, background
shell or scratch root was created. The gate request records that no code
gate is ready. The residual is tracked in
`docs/seon/issues/issue-family-guarantees-cross-prohibited-owners.md`.

The documentation hook reported 12 Markdown lint errors. Its visible
diagnostics name stale dependency-pin citations in the existing
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`;
the hook elided the remainder, so not all 12 are attributed here.
`git diff --check` passed. The shared index was not edited by this lane;
the new open note needs an owner schedule entry under the current checker.
