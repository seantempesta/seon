---
type: architecture
status: ruled 2026-09-22 (owner); implementation in the agent-platform plan, track 1.3d
---

# Clusters, branches and contexts — one JVM, many realities

Ruled by the owner on 2026-09-22 in dialogue with the orchestrator. The plan
([README §4 row 1.3d, §7](../../prds/agent-platform/plan/README.md)) implements it;
the three data packs of that day ([program rows](../../research/agent-platform/program-rows-data-pack-2026-09-22.md),
[host-bound rows](../../research/agent-platform/host-bound-rows-data-pack-2026-09-22.md),
[merge and write-back](../../research/agent-platform/merge-and-write-back-data-pack-2026-09-22.md))
supply the facts each operation reads.

## Vocabulary — Datahike's and Clojure's words

- **Cluster**: a named Datahike branch plus the agents working on it. Its head is the
  shared context; the cluster row holds the pointer we advance.
- **Branch**: a private line of commits off a cluster's head (`d/branch!`): a roster
  write, no copy, no environment. Not a git worktree; git worktrees are not used.
- **Context**: the SCI world acquired from a database value's program rows.
- **Program rows**: functions, tests, schemas, namespaces, render pairs, contracts and
  their analysis facts — the shared thing that survives. Everything else (turns,
  evaluations, messages, errors, tasks, results) is data: disposable, never merged.
- **Merge**: program rows from a branch onto a cluster's head, through the gate.
- Retired words: "reload" as a system to start, "refork", "environment", "worktree".
  A reload is `require :reload` of changed namespaces and their dependents, nothing more.

## The model

1. **The default cluster's program is the files.** Two things derive from the files
   and are recomputed when a file changes: the PROGRAM ROWS (the indexer's output — the
   database is the index and cache of the files, and every query, gate, merge and context
   acquisition reads it) and the LOADED NAMESPACES (`require`'s output — the compiled
   functions that execute). Default's data can be dropped for a fresh default. It is the
   one reality that runs compiled: a JVM holds one set of Vars.
2. **Every other reality interprets its differences.** A branch whose program rows
   differ from the compiled head interprets the overridden rows and their affected
   callers in its own context (B2 §2a); everything else binds the compiled Var.
3. **Host-bound rows change only through the files.** A declaration SCI cannot
   interpret (host-defining forms: `deftype`, `defrecord`, `defprotocol`,
   `definterface`, `gen-class`, `proxy`, `extend` onto host classes, and rows that
   construct such types) is a computed per-declaration fact from analysis, never a
   namespace roster. An override of one is refused by name.
4. **An agent's mode is one attribute: its branch.** Live: the attribute points at
   the cluster's branch; every evaluation reads the current head and every `defn` it
   transacts is there for all agents at their next evaluation. Isolated: a branch off
   the cluster head; nothing moves under it until it merges. Custody hands the agent
   that branch's connection; the agent never names a branch. The task sets the mode at
   start; the agent can also branch and request a merge itself through `my.*`.
5. **Stability comes from the branch, not from a captured value.** No reload runs
   under an admitted evaluation; the loaded namespaces advance only at a boundary
   between evaluations; a context is reacquired from its branch head at turn start and
   cached by commit id.
6. **A test is an isolated agent that lives for one body**: branch off a captured
   commit, fork the context onto it, run under custody, unlink the branch. The same
   three functions as an isolated agent; the runner is never a second mechanism.
7. **Merge is program rows only, git-like.** Non-conflicting rows land on an
   intermediate branch; conflicts stay for the agent to fix there, so the problem
   shrinks; the combined program is tested in a context forked from that branch. The
   gate is green reaching tests plus contracts; a named accept (root or the owner)
   then advances the pointer. Write-back to files is the same gate; its diff is a
   separate spec (branch + current codebase in, minimal recomputation, spans or whole
   files out).
8. **Filesystem lanes (Codex, Claude) are live agents on one candidate branch of
   default.** The hook indexes every session's edits into that branch; the reaching
   tests run there; when green, default advances, the files are already the truth, and
   the changed namespaces plus dependents reload sub-second (`require :reload`) at the next boundary.
   Isolation between filesystem lanes remains file ownership. Seon agents doing all
   updates is the goal; the filesystem path is bootstrap.
9. **Retirement is unlink, then GC.** A finished branch leaves the roster; its data is
   reclaimed by the retention sweep, never by a per-branch cleanup.
