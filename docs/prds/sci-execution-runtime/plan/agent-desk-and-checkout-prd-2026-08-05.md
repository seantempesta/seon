---
type: prd
status: active
tags: [prd, runtime, agent, session, git]
---

# The agent desk, the checkout, and the git framing — PRD (2026-08-05)

Owner-ruled in conversation on 2026-08-05 (recorded in
[README.md](README.md) "Third ruling batch"). Implementation waits for
the rename + reset + rebuild pass
([rename-pass-2026-08-05.md](rename-pass-2026-08-05.md)); this document
is written first so the conversation's decisions land whole.

## 1. The two-world contract (P13 settled)

Owner verbatim: agents "should have their own context and the shared
context in the system… defs and atoms are temporary for experimentation
and when their session ends it will be lost and they are to convert
everything durable to be using the database and writing proper
functions with schemas and tests… sometimes atoms are good for early
experimentation so I want to support them in the agents context."

- **The DESK** — the agent's session world: `def`s and atoms, fast,
  experimentation-first, not durable, not shared. Supported and taught.
- **The SHARED SYSTEM** — database facts and contracted functions with
  schemas and tests, entering through the ONE admission seam.

This dissolves the recorded tension between ruling #17 (selective
corpus admission — no scratch litter in the shared program) and ruling
#28 (stateless resume): #17 governs the shared world, the desk governs
the agent's own. The bootstrap teaches the contract explicitly.

## 2. Desk facts — `:seon.def/*` (the session-image mechanism dies)

The separate `:seon.code.def` session-image family and its write path
are DELETED. The capability survives as agent-scoped **desk facts**
written through the one admission seam:

- **When:** at RUN SETTLEMENT, the run's session definitions commit as
  `:seon.def/*` facts scoped to the agent (invisible to the corpus and
  to other agents).
- **What, per definition (the restore ladder, strongest first):**
  1. the **provably pure defining form** — re-evaluation is exact by
     construction (purity = no host interop at SCI analysis AND no
     capability reachability over `:seon.fn/calls`; both already
     built). Owner: keeping forms "makes the context restore stronger" —
     confirmed, this is the strong tier;
  2. else the **store-faithful value** (the existing `store-faithful?`
     class+metadata+value round trip; large values as content-addressed
     blobs);
  3. else **honestly unrestorable** with a reason
     (`:seon.def/unrestorable-reason` — the audit's R9 name).
- **Atoms restore by SNAPSHOT, STATED** (owner-ruled): the atom's last
  settled value is snapshotted at run settlement; restore re-creates the
  atom bound to it and the REPL prints one honest line ("restored
  `scratch` from its last settled value"). Losing an afternoon's
  experiment state to a JVM bounce is the frustration the desk exists
  to prevent; honesty is preserved by saying it.
- **Rehydration:** each run's fresh fork = cluster base ctx + this
  agent's desk facts. Run boundaries, JVM bounces, and stateless resume
  are ONE path. There is no second restore machinery.
- **Session end is EXPLICIT ONLY** (owner-ruled): the desk lives as
  long as the agent. Clearing = the agent's own act, an operator order,
  or cluster reset/refork. Nothing expires on a timer — "temporary"
  means not-durable-not-shared, never vanishes-silently.

## 3. Freshness is per TURN (owner: "why wait for the run?")

New functions, schemas, and tests are visible in context next turn
(ruling #16 already derives all context fresh per turn) and CALLABLE
the moment admission installs them, even mid-run: the per-run fork
copy-on-writes only its own redefinitions, so foreign installs show
through. Only the database value one form reads at its instant is
pinned — snapshot isolation (L9) is preserved, not weakened.

## 4. The checkout — two time-modes, one mechanism

Every run evaluates against one immutable database value
(`:seon.cluster.run/opening-commit-id` is already a fact). The ONLY
difference between agent kinds is which value a run opens at:

- **Live namespace owner** (declares nothing): every run opens at the
  branch head. Constant updates arrive by construction — commits are
  wakes, and the next run derives from the current value.
- **Fork-in-time agent** (editor, replayer, side-world debugger): the
  agent entity carries its CHECKOUT — an opening basis (commit id) and
  branch — and runs in its own little world (Datahike branch fork,
  ~17 ms). A single run may OVERRIDE the checkout (the curation proof
  replays one span at its opening basis). Most specific wins — the
  initial-forms resolution rule.
- **The ctx is not a third surface:** the SCI context derives from the
  database at a basis, so checking out the database IS checking out the
  program world. Nobody builds ctx versioning.
- **Cost sits where it belongs:** live forks are sub-microsecond off
  the live ctx; a pinned basis pays one cold ctx build, cacheable by
  commit id (bases are immutable).

## 5. The git framing (owner: "agents know git really well")

Adopted as the agent-facing story — the O1-inversion lesson applied to
ourselves: stop teaching invented vocabulary where a strong prior
exists. The mappings told to agents:

| Seon | Git |
|---|---|
| the desk (uncommitted session defs/atoms) | the working tree |
| run-settlement desk commit | commit |
| pinned agent (checkout fact) | detached-HEAD checkout |
| cluster fork from a published commit | `checkout -b` |
| `current-src` publication (expected-current guard) | `push --force-with-lease` |
| acquisition at a basis | fetch/pull |
| messaging a namespace's owner agent for a change | a pull request |
| the dependents-test quality gate | CI |
| curation (revision → mechanical proof → adoption) | `rebase -i` |
| single-future adoption, losers deleted | a fast-forward-only repository |

- **The ff-only + rebase framing is RULED**: merge does not exist here
  and the bootstrap says so up front (no index, no remotes, no
  conflicts — the gate refuses instead).
- **Two substrates stay separate** (owner probe, resolved in
  conversation): our database branches get the new verbs; the REAL git
  repository on disk needs no wrapper at all — `my.shell` + the git CLI
  is the strongest prior an agent has. Same verbs on both is ordinary
  polysemy; unifying them would blur exactly the differences that
  matter. Where they meet — publication — the link is ONE provenance
  fact: the published `current-src` commit records the source git SHA
  that produced it, so the two histories join by query.
- **Context gains git-shaped views, RULED**: a STATUS block (desk vs
  committed — teaches the two-world contract every time it renders) and
  a LOG view (branch history), both ordinary declared renderers over
  existing facts. No new state.
- **RULED (owner, same day): the namespace is `my.branch`** — the noun
  git and Datahike already share; honest about what it operates on, no
  promise of being git ("my.git would imply normal git"). The verbs
  carry the prior: `my.branch/checkout`, `my.branch/log`,
  `my.branch/diff`, `my.branch/status`, each docstring opening "like
  git <verb>, except…". Real git on disk stays raw CLI via `my.shell`.

## 6. Wave order (after the rename pass)

1. **W-A — desk facts**: `:seon.def/*` shapes declared; run-settlement
   write path through the admission seam; fork rehydration; the
   `:seon.code.def` write path and restore machinery deleted in the
   same commit. Proof: define fn + data + atom in run 1 → JVM kill →
   next run sees all three, atom snapshot stated, REPL states anything
   lost; explicit clear empties the desk.
2. **W-B — checkout**: the agent checkout attribute + per-run override
   resolution in the run loop; pinned-basis ctx build cached by commit
   id. Proof: a pinned agent at commit X does not see a function
   committed after X while a live agent does; a per-run override
   replays a span at its opening basis.
3. **W-C — the verbs + views**: the agent-facing branch/checkout/log/
   diff/status surface (name pending ruling) + the status/log
   renderers + the publication source-SHA provenance fact. Proof: a
   live drive in which an agent reads its own status, checks out a
   scratch branch, and walks log across the publication link.
4. **W-D — bootstrap teaching**: the two-world contract + ff-only
   story in the initial forms; measured against the O1-inversion
   lesson (teach the mapping in sentences, not pages).

Each wave is one lane, path-limited, live-proven, in that order.
