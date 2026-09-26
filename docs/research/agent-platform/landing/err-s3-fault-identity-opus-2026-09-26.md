---
type: landing
lane: err-s3-fault-identity-opus
status: implemented, unverified (default not booted); three patches for root
commits: d6fa8a65e
date: 2026-09-26
base: 7c40396b3
---

# err-s3-fault-identity-opus — fault identity, storage and observation (audit §4 row 3)

## Broken / unverified first

- **MCP JVM evaluation replaces every value** while the cluster's projection state is absent: `(+ 1 2)` returned the
  `seon.cluster/mcp-effective` refusal "The MCP config read has no cluster projection state." instead of `3` (18:51Z) — a
  totality defect in the bridge's configuration read (err-s4 / cluster.clj owner), not only a boot symptom.
- A read-only sweep sub-agent was started before root's no-delegation reminder; this lane could not stop it (ownership
  refused TaskStop). Its output is not used; the sweep below is this lane's own scripted pass.
- **Default was not usable for this slice.** After `start --head` at 7c40396b3 the cluster never reached
  `:seon.boot/config-result` (status 18:45Z: missing layers config-result, ctx, work-launcher, graph, served), and every MCP
  `eval_clj` in JVM mode answered `seon.cluster/mcp-effective` "The MCP config read has no cluster projection state."
  Consequently **none** of the REPL proofs, the two pre-commit static checks, the audit probes P5a/P5b/P6 or the stored-size
  measurement were run. Unavailable evidence is not a pass; root runs the list at the end once default boots.
- The operator could not read that boot failure because an error value carried a whole schema projection (below, finding A).
- Cross-owner edits are patches for root (turn.clj, config.clj, seon.error.edn); until the turn.clj patch lands, an agent's
  own message-delivery refusal (unknown recipient, chain limit, no limit) escalates to root instead of mailing nobody.
- The attributed agent of a core fault is no longer mailed (`:your-run`); it reads the fault through `for-agent` (its
  occurrence). A sleeping agent whose run a Throwable interrupted is therefore not woken by the fault — root/steward is.

## Design (what exists, the one owner, what is deleted)

What exists: `seon.error/recording` prepares one fact and hands `seon.error/commit-call` (a Datahike tx fn, the one writer
decision) the occurrence, the notifications and the error row; `seon.fault/record!`/`fault!` is the core-fault route;
`occurred-since` and `for-agent` are the two readers agents' observations use; `seon.error.refusal/chain` is the one cause
normalization. Principal approach: the writer decides from held relations and the producer's own request; readers return
the stored observation (`observation-selector` + `latest-fact`, the existing reconstruction) instead of a new summary.

1. **Responsibility without guessing** (`commit-call`, task `c75ab14cb365`). Deleted the `interrupted?` inference from
   `:seon.error/exception-class` and the `(nil? agent-id)` gate. Recipients: the steward of the fault's function, else the
   escalation owner the producer's request names; none when the producer names none (an agent mistake already returned).
   Same fault as Throwable or flat value → same owner.
2. **Notification is the error value** (`message-tx`). Content is the readable EDN of `seon.error/value` (flat base members,
   id, signature, data-blob reference) instead of `ai-prose (notice …)`; `:seon.message/about` stays the signature. The
   notice arms for `:your-run`/`:no-attributable-agent`/`:recurring` are deleted (only turn's `:failover` notice remains).
3. **Observation returns evidence** (`occurred-since`, task `f58c3aab9277`). Per touched signature: the recorded
   observation (`latest-fact` of the pulled error: layer, operation, message, declared schema, stored evidence, occurrence
   references, cumulative `:seon.error/occurrence-count`), the touched branches, and `:seon.error/resolution` when resolved.
   Deleted: the resolved-tx `not-join` that hid a fault resolved before observation, the hand-built 5-key summary, the
   summed-cumulative `count` presented as a window count. Additive to `:seon.repl/branch-error` (open map).
4. **Root sees orphan faults** (`for-agent`): the query adds errors whose notification the writer sent to this agent
   (`:seon.message/about` = signature), so the escalation owner of a fault no steward answers for finds it in its next
   observation, resolved or not. Cross-branch visibility of cluster copies for branch agents needs a second database value
   and belongs to err-s4's observation path (not done here).
5. **One normalization keeps suppressed causes** (`refusal/chain`, task `4c559398c4be`). The chain walks causes itself
   (a cause cycle ends at its first repeat, as `printStackTrace` does) instead of `Throwable->map` + an unguarded
   `iterate ex-cause`, and each link carries `:seon.error/suppressed` — the chains of `.addSuppressed` throwables (cleanup
   failures). `root-frame` uses the same walk. `fault!` already attaches the storage failure as suppressed on its throw,
   so a storage failure now preserves both failures in the data.
6. **References, never worlds** (task `5e008f619131`). See finding A.

## Finding A — the projection inside an error (root's 12:40 boot failure)

Evidence: `data/clusters/default/logs/operator-start-20260926T124017.853.edn` — edamame "Map literal contains duplicate keys
[:seon.db/write-validator …]" while reading the prepl reply: the printed value held a `:seon.schema.projection/compiled`
cache keyed by compiled schemas. `seon.log:109623` shows the writer error "Transaction report validation rejected."
(`reference-code/datahike/src/datahike/db/transaction.cljc:1231`) on `:cluster-default` at 18:40:17Z.

Mechanism: `seon.db/write-observation` (db.clj:4617) copies the caller's whole `transaction` into
`:seon.error/data :seon.db.write.attempt/transaction`. Two producers put a projection inside a transaction:
- `seon.config/apply-compiled!` → `[:db.fn/call #'reconcile-call projection desired request]` (config.clj:750) — boot's config
  reconciliation, the transaction the retired dial made the validator reject. Already removed in the working tree by
  fix-dial-retirement-adoption-opus (config.clj:779 now `[:db.fn/call #'reconcile-call desired request]`); no patch here.
- `seon.error/recording` → `[:db.fn/call #'commit-call request]` with `:seon.schema/projection` assoc'd into the request —
  every error-recording transaction. Fixed here: the call carries `(dissoc request :seon.schema/projection)`; `commit-call`
  reads `(db/carried-projection database)` (its old `or` fallbacks were dead: carried-projection is total).
Recommendation for db.clj's holder: `write-observation` should keep the attempt's request id and bounded operations (as
`error/prepare` already projects them for storage, error.clj ~594) rather than the live transaction in the returned value.

Sweep of other inlined worlds: one scripted pass, `tmp/err-s3-fault-identity-opus/sweep.py` (61 candidate lines in
`sweep.txt`: error members `:seon.error/offending|data|expected`, `::request`, write-attempt/store transactions whose value
names a request/projection/connection/db/report/ctx/environment/unit, and `[:db.fn/call …]` arguments naming a world),
then hand classification. Confirmed world or unbounded copies (owner, reference that replaces it):
- `db.clj:4633` `write-observation` — the caller's whole transaction (projection inside it via config/error tx fns);
  keep request id + bounded operations. `db.clj:4732` timeout evidence `:seon.store/transaction transaction` and
  `db.clj:4779` `:seon.error/offending transaction` — same class. Owner: db.clj holder.
- `render/transcript.clj:781` and `:1040` — `:seon.error/offending unit`: a render unit carries `:seon.db/db` (a database
  value). Replace with the unit's run/agent ids and the database's commit id. Owner: err-s4 (transcript.clj).
- `error.clj` `recording` → `commit-call` request with `:seon.schema/projection` — **fixed here**.
- `config.clj` reconcile-call projection argument — fixed by its holder (above).
Suspected, not proven world-bearing (request contents vary by caller): `turn.clj:336` `::request request`,
`effect.clj:715/777/804`, `issue.clj:846–1541` (tx-fn requests; issue.clj already dissocs `:seon.db/connection` before its
tx fns at 1519/1562/1608), `edit.clj:348–480`, `context.clj:70`, `shell/jvm.clj:457`, `my/program.clj:42/617`,
`env.clj:90/106` (only reached with a non-environment value), `render/web.clj:1780`. None of the 22.8 GB class
(`:seon.db/db` / connection / SCI ctx stored on a fact) was found in a *stored* member: `error/prepare` projects write
attempts to bounded operations before storage (error.clj ~594); the confirmed copies are in returned/thrown values.

## Patches for root

- `tmp/err-s3-fault-identity-opus/seon.error.edn.patch` — declares `:seon.error/suppressed` on `:seon.error/link`
  (`[:vector {:min 1} [:ref :seon.error/chain]]`) and narrows `:seon.error/reason` to `[:enum :failover]`.
- `tmp/err-s3-fault-identity-opus/turn-error-tx.patch` — `turn/error-tx` (only caller: message-delivery refusals, an agent's
  own mistake) stops naming the escalation owner.

## Lines

Source: error.clj +66/−80, refusal.clj +27/−11 (src net −2; most additions are docstrings). Tests: error_test +34/−24
(`the-writer-not-the-evidence-shape-chooses-who-acts` replaces `only-a-throwable-tells-the-attributed-agent`),
refusal_test +21 (`a-cleanup-failure-survives-beside-the-primary`), error/branch_test +11/−4 (resolution no longer hides;
evidence asserted). Tasks: 3 in seon.error.edn, 1 new seon.error.refusal.edn.

## Root runs (once default boots)

1. Static: `bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`
   and `--test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn` (after the schema patch).
2. `bin/test-check default --ns seon.error-test`, `--ns seon.error.refusal-test`, `--ns seon.error.branch-test`,
   `--ns seon.fault-test`, `--ns seon.cluster.fault-message-test`.
3. Probes P5a/P5b/P6 (audit Appendix D) on a fresh MCP branch, plus: record one fault with a two-link chain and a suppressed
   cleanup through `seon.fault/fault!` on a disposable branch, then `(count (pr-str (seon.error/occurred-since db since)))`
   and the stored occurrence's `:seon.error/data-edn` size (expected: kilobytes, no projection keys).
