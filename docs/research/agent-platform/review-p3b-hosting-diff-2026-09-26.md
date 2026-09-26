---
type: review
status: complete
date: 2026-09-26
reviewer: gpt-6-astra
source-pin: 5bed888d02c300ca3dc798109687f2b88471a436
verdict: REVISE
blocking-findings: 2
---

# P3b branch hosting: independent diff review

The host composes branch acquisition, an ordinary armer/listener and the agent's ordinary evaluation entrance. That is the right direction. The remaining blockers are authority obtained from a connection lookup and admission decided before the writer.

## Scope and evidence

Read `CLAUDE.md` end to end first; read `one-repl-language-2026-09-25.md`, the existing `review-p3b-hosting-2026-09-26.md`, and `landing/fix-dogfood-api-2026-09-26.md`. Reviewed the actual changes in `8e3a6315a`, host introduction `07a03cdd4` (identified through host history), `2fe36dca1`, `33ea69298`, `b013e6244`, `fdd9926e8`, and `fbd0897df`, with their callers and final committed source at the pin above. The design and commit descriptions supply the hosting lifecycle explanation; the dogfood landing covers the later parent-row/message change. I found no separate complete installed landing for the entire host series.

All source line numbers below refer to that pin. This is a static review: no JVM, tests, adoption, browser or provider calls. Later shared-tree changes are outside the verdict. The earlier review's blanket atom objection is superseded by the owner's subsequent one-roster-per-dynamic-scope ruling; this review does not revive it.

## Findings

### H1 — blocking: message delivery manufactures foreign custody

**Location:** `src/seon/cluster/message.clj:723`, especially `:727` and `:746` (`fdd9926e8`).

**Failure scenario:** an isolated agent holds only its branch connection. Its inherited root row names the parent branch. `send!` looks up that branch's process connection, then binds the looked-up connection as custody and transacts there. The sender has gained a parent write through a branch-name fact, without an admitted capability granting that write. A branch-local edit of a recipient's home can also redirect this lookup to another open branch. The message writer may validate the message; that does not establish the caller's authority to acquire its connection.

Datahike explicitly describes `active-connection` as borrowing an existing connection without acquiring an owning reference (`reference-code/datahike/src/datahike/connections.cljc:5`). It is a lifecycle lookup, not an authority grant. The new call violates “authority comes only from held custody.” The intentional product behavior—sending root a merge request—does not justify making the registry an authority source.

**Smallest fix:** have the host admission supply the narrow message-delivery capability for the admitted destination, and execute delivery with custody held by that owner. Refuse a foreign destination when no such capability was supplied. Keep the existing message writer; do not introduce another dispatcher or use `call-with-custody` to bless a registry lookup.

### H2 — blocking: two branch hosts can claim the same parent identity

**Location:** `src/seon/cluster/host.clj:48`, `src/seon/cluster/agent.clj:233` (`fdd9926e8`).

**Failure scenario:** callers hosting the same new agent id on sibling branches both read an absent parent row at `host.clj:49`. Their separate child rosters each reserve a host. Both then submit `creation-tx` to the parent. Its identity-bearing map upserts the same agent and supplies its own `:seon.agent/branch`; it does not recheck the absent-row assumption. Both branch agents can run while the parent row names only the last branch. Delivery and retirement then operate on ambiguous identity/custody. Sequential checks for an existing conflicting branch do not prevent this interleaving.

Datahike's identity upsert resolution is at `reference-code/datahike/src/datahike/db/transaction.cljc:659`; `:db.fn/call` receives the in-transaction database at `:1171`. Serialization of two transactions does not serialize the pre-reads that chose them.

**Smallest fix:** move the absent/existing/home/admission decision into a transaction function on each target writer, composing `creation-tx` only after the check. The parent writer must refuse another home and an admission-closed identity. Preserve the already admitted matching identity without recreating its components. Add one concurrent sibling-host behavior regression through the existing host entrance.

### H3 — should-fix: cached host lookup ignores the requested agent

**Location:** `src/seon/cluster/host.clj:111` (`33ea69298`).

**Failure scenario:** after hosting agent A on branch B, `host!` called for agent C on B returns A's host directly. Neither the fast path nor the shared delayed claim verifies the requested identity. A caller can receive a successful-looking handle for another agent, although the API says it hosts the requested id.

**Smallest fix:** after resolving either path, compare the admitted host identity with the request and return a named custody/identity refusal on mismatch. Keep one host per branch and the existing reservation; no second map is needed.

## Dependency use, simplicity and laws

The agent still submits through `evaluate-source!`; the host adds lifecycle composition rather than another evaluator. Reusing the ordinary wake route is preferable to a host-specific scheduler. Flow synchronously initializes a proc through `step args` (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:263`), supporting listener-before-prime ordering. Flow `stop` sends stop and closes graph channels (`:174`); it does not itself prove arbitrary host execution exited. Retaining `disarm-agents!` and its joins is necessary.

SCI's actual fork seam is `reference-code/sci/src/sci/core.cljc:345`; a forked SCI environment and a Datahike branch are distinct held values. The host correctly carries the acquired execution rather than merely changing a branch label. Malli's instrumented input/output checks (`reference-code/malli/src/malli/core.cljc:2202`) cannot supply the missing writer-time check or validate authority obtained from a global lookup.

The host's roster entry refers to live objects instead of copying them into facts. Parent and branch agent rows are intentional roster facts, but their agreement must be checked by the writers. The new partial `ex-info` refusal map at `host.clj:43` is also weaker than the declared flat-error convention; use the named refusal for H2/H3 instead of proliferating ad hoc thrown maps.

## Size, cost and proof limits

Summing the seven named commits' numstats, without crediting unrelated deletions: source/bridge **+330/−141 (net +189)**; schemas **+21/−0**; tests **+275/−54 (net +221)**. This is commit churn, not a claim about final file size. The design's small net-deletion estimate does not describe this full series. The dogfood landing acknowledges growth, but dividing it into small commits does not establish the law's same-cut deletion justification.

The fast-path comment reports historical 409 ms reacquisition versus 240 ms first hosting; this review did not reproduce either. There is no new installed, armed, parent/change timing or attributable memory evidence here. Before acceptance, root needs positive proof for sibling identity conflict, matching-identity reuse, branch-local writes, admitted root messaging, admission-close versus submission, failed start, and real retirement exit. The review neither resets default nor substitutes a source citation for installation.

**REVISE**
