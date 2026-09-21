---
type: research
status: complete (independent plan review; changes integrated into plan/ at the same commit)
created: 2026-09-21
tags: [agent-platform, review, second-perspective, isolation, cuts, proofs]
---

# Second perspective on the integrated agent-platform plan

Read end to end: the brief, `AGENTS.md`, the integrated README and all eight
specs (A1, A2, B1, B2, B3, B4, C1, D1) at `433934b61` plus the README's
uncommitted paragraph on preparation scope; the astra reviews for the README,
C1 and D1; the fresh-start landing note. Verified before writing the main
change: `install-function-contract!` is the per-context wrapper seam
(`src/seon/sci/eval.clj:695-711`: `sci/bind-root!` of `instrument/wrap-interpreted`
under the context's projection) and SCI's `copy-var*` copies the Var's VALUE at
copy time (`reference-code/sci/src/sci/core.cljc:112-140`). No source, tests,
schemas, operator, branch or JVM were touched; one path-limited documentation
commit. Codex's baseline work and landing evidence were left alone.

## What was retained

The integration is right on its spine and I kept it: the projection carried on
the database value (A1), one read-currency mechanism over Datahike's cache
context (A2), the unpublished-branch → report → caller lint → head-move
publication with per-definition digest (B1), acquire-once-then-install-differences
and the walk as the only history (B2), one error map validated at the wrapper and
one task family with trigger/start as separate writers (B3), one `seon.test/run`
with per-member reuse evidence and conservative static selection (B4), inclusive
observations only (C1), explicit merge on a scratch branch with the tested head
as precondition and staged export through B1's analyzer (D1). The four cuts are
the right granularity.

## Material changes, ranked (each integrated into its owning section)

| # | Change | Why | Where |
|---|---|---|---|
| 1 | **Isolation by construction, not by proof.** Each cluster's SCI context installs its own wrapper over the ORIGINAL loaded function under its own projection at the seam interpreted rows already use; the JVM Var is armed once for the development cluster. B2's "bind JVM Var objects" is withdrawn (a fork observes root replacement, which would let a reload change a sovereign cluster). | Dissolves A1-2's per-call projection scan, the two-generation gate, and C1's custody lookup in one stroke; the probe confirms rather than decides. The plan had four specs circling this seam with proof gates; the existing mechanism already answers it. | README §3, §7 gate row; A1 §5 A1-2; B2 §2a; C1 §2 |
| 2 | **Commits inside a cut are not test-gated.** Gate = HEAD loads + the named REPL probe; a deleted mechanism's tests die with it; replacement tests are written at the END of the cut, one per behaviour class; platform tier once per cut. | Owner's instruction, and the numbers: reach is nearly saturated (`transact!` selects 1,398 tests after the dispatch declarations), so "tests reaching my change" on a core file is the suite at hours per commit. The specs' per-commit "run only reaching tests" sentences are superseded by this rule. | README §6 |
| 3 | **Order inside the cuts.** A commit-level producer→consumer order (1.1 constructor, 1.2 digest, 1.3 wrapper, 1.4 carried seam, 1.5 B1 deletions freeing `cluster.clj`/`fn.clj`, 1.6 A2 validator + B3 kind cut, then 2.x–4.x). | The specs' stop rules formed cycles at lane granularity (A1↔B1↔A2, A2↔B3, B3↔B4). Stated once in the README so no lane waits on a lane that waits on it. | README §4 |
| 4 | **Acquisition reads are not evaluation evidence** — ruled. | 407 of 435 retained reads were program-acquisition pulls captured under an evaluation's read capture. Removing the capture removes them; A2's scoped selector stays for cost only. | A2 §6.1; B2 §2a |
| 5 | **B3's durability gate dissolved by an existing ruling** (2026-09-23: blob = complete rendering, text = capped, object = `result/e<id>`). | The integration asked the owner a question already answered; `data-edn`/`data-size`/`capped?` are the duplicates, the blob reference stays. | B3 §2a |
| 6 | **No "coherent independent-store export" mechanism for blob tests.** Blob-mechanics tests open a fresh empty store; the two tests needing program facts and an independent store keep `fork-database` with a numbered allowance. | The astra review had added a new mechanism to serve four tests. | B4 §2d, §5 |
| 7 | **Profiling findings decided:** a declared bound that fired opens a task through B3's writer; ranking is a read agents consult. No 2 s dial, no self time. | Meets the owner's intent (automatic issues for agents) with the criterion that is already a fact; removes an open decision. | C1 §2 |
| 8 | **No bulk promotion of issue notes to tasks;** class A notes deleted now, class B at their spec's landing. | Owner's ruling ("keep only the issues that will still be around"); a directory of notes turned into a directory of tasks is the "fake tasks" case. | README §8; B3 §2b |
| 9 | **Size, stated plainly:** the specs sum to ≈55 K source lines, a 40 % cut; the tenfold norm needs a second dissolution pass over the surviving mechanisms after D1. | The plan implied more than it delivers; the owner reads it personally. | README §5 |
| 10 | **Unvendoring recorded as done** (89 removed, 20 kept, forks verified pushed); README §8 had described it as future work. | Fact correction. | README §8 |

## Smaller mechanisms to probe first (added, not decided)

- **The completion observer is the transform's own wait** (B2 §6): submit through
  `futurize`, `.get` under the bound, commit the fault on timeout, then `.get`
  without a bound until exit, then settle. Flow's loop cannot take the next wake
  before the transform returns, so no overlap is possible by construction and
  exit evidence precedes the next wake. Out-of-proc callers send through the
  wake in-port. If the probe holds, the 252-line observer, the permit and
  `backstop-state` leave.
- **The cold unresolved-callers readback** (B1 §2b, 4.9 s): on a complete
  publication the analysis-time `:seon.fn/unresolved-references` rows and the
  database not-join are produced from the same analysis and should be equal by
  construction; a probe comparing them on one reset decides whether the
  readback runs on the cold path at all.

## Coherence: can the four cuts land?

Yes, with the §4 order. The remaining cross-cut dependencies are real and are
now stated as producer-first steps rather than mutual stops: the constructor
(1.1) and the digest (1.2) are additive and unblock everything; A1's wrapper
(1.3) precedes B2's context work; B1's deletions (1.5) free the two held files
that B3's kind cut needs; A2's validator narrowing (1.6) waits only on A1's
render-property facts and B1's arity facts, both produced in 1.3–1.5. Two reset
batches (after 1.6 and after 3.x) carry every stored-shape change; RESET items
are marked in the specs.

## Proofs: sufficient and affordable?

Sufficient where they confirm a construction (isolation, currency, publication
authority) and no longer sufficient-by-exhaustion: I removed proof gates that
asked the owner questions already ruled (B3 durability) or that could be
dissolved by choosing the mechanism that is correct by construction (isolation).
Affordable only under change 2: per-commit reaching-test runs would cost hours
on core files. What remains owed is the platform tier once per cut and one
regression per behaviour class written at the end of the cut on the canonical
fixture with real SCI and armed contracts. Reference-code reading lists and REPL
protocols in every spec are intact and are the lane's first act.

## Unresolved decisions for the owner

| Decision | Recommendation first | Alternatives |
|---|---|---|
| Lane concurrency in the implementation session | three lanes on disjoint files, one JVM each (the measured load cap) | four (faster wall clock, the seven-JVM incident is the risk); two (safest, slowest) |
| When the second dissolution pass (toward tenfold) starts | after D1, as namespace-agent work on a codebase whose contracts and tests make it safe | a third human cut now (slower start, no agents yet); accept 55 K as the end state (gives up the norm) |
| D1's write-back gate | automatic path-limited commit after explicit acceptance, required tests, isolated analysis and callable proof (D1 §8) | plus the platform tier per export (more execution); plus human approval (latency) |

## Verification boundary

The README carried one uncommitted paragraph from the Codex session (preparation
scope, 14:40); it is preserved verbatim and travels in this commit. The
Markdown hook's seven gitlink findings name files in the still-present
steward-platform directory and one datahike skill reference, not the plan; they
are deleted or fixed at the clean-write deletion step. No runtime claim is made.
