---
type: landing
status: patches for root (test.clj held by fix-dogfood-api); nothing committed to src; untested (root runs the list below)
lane: fix-test-request-cost-opus
created: 2026-09-26
authority: docs/seon/issues/test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads.md; agent-centric-audit-2026-09-26.md F08
---

# Landing: fix-test-request-cost (2026-09-26)

A test request already keys reuse by content: `seon.test/select` compares each green member's stored
reach digest (`seon.test.runner/reach-digests`) with the current one. It then throws that evidence away
for any member reaching an identity the request *names* as changed. `my.test/check` always names the
agent's differences from the published commit, so an identical re-check never reused. It paid
`clean-request-handle` (about 1 s) every time. The cut makes the named change select candidates
without counting as an observed change. The reach digest decides reuse, which is the content key it
already is. Two reads are also restated so their cost follows the change, not the whole program.

## Broken first

- **Nothing of mine is installed.** Every cut is in `src/seon/test.clj`, which the fix-dogfood-api lane
  holds. Per the assignment, the hunks are patches for root:
  - `tmp/fix-test-request-cost/test-clj.patch` (+34/−19);
  - `tmp/fix-test-request-cost/selection-test.patch` (+17/−0), the regression.

  Both pass `git apply --check` against HEAD 62eaac4e8 (test.clj at 7c7edcbc7).
- **`src/seon/test/runner.clj`: no change is committed.** I wrote an O(1) fast path for
  `program-written-since?` (the value's basis ≥ `basis-t` gives false), then reverted my own edit. It is
  not exact for an in-transaction value: datoms already written by earlier ops in the same transaction
  carry tx basis+1, while `db/basis-t` still reads the pre-transaction basis. The exact version needs the
  provenance's Datahike attribute revisions carried to the writer; see option 2 below. The 75 ms per
  admission stays.
- **A changed program still pays about 1 s in `clean-request-handle`.** This covers the first check
  after a `defn`, and every re-run of a red test. A changed one-test check measures 1,485–1,584 ms, so
  the target "well under 1 s" is met only for the reused case. See "Remaining".
- **Verification limits.**
  - The REPL proofs ran patched copies of `select`, `selection-admission`, `run`, `definition-digests`
    and `changed-definition-symbols`. The copies were read from the running archive with the same edits
    and loaded into scratch namespaces (`fix-trc.scratch3`/`4`, since removed).
  - Those copies are unarmed. Their callees ran armed. The real select and run results were validated
    against `:seon.test.selection/result` and `:seon.test/run-result`, and new
    `definition-digests` output against `[:map-of :qualified-symbol :seon.source/digest]`: all true.
  - No test request was run (root runs them).
  - The pre-commit static checks cannot run until the hunk is applied. No `:malli/schema` or schema
    resource changed.

## Evidence (default pid 55348, archive 04f3a159b; branch agent via MCP `branch` + `eval_clj` sci)

Reproduction: `user/word-count` plus one REPL `deftest` (2 assertions), then `(my.test/check {})`.

| Probe | Before | After (scratch, patched) |
|---|---|---|
| identical re-check, unchanged program | 1,555–2,006 ms, `reused-count 0` (3 runs each, two JVMs) | 117–159 ms, executed 0, reused 1 (7 runs) |
| check after a docstring change to `word-count` | — | 1,507–1,584 ms, executed 1 (digest changed), then 142–198 ms reused |
| red test re-check | — | 1,486–1,503 ms, executed 1 each time (red is never reused) |
| `seon.test/select` for the check request | 300–354 ms | 133–153 ms |
| `definition-digests` as-of, 103 symbols, cold basis | 66.8 ms | 6.0 ms (equal maps) |
| `definition-digests` as-of, 2 symbols (first probe) | 180 ms | ~1 ms |
| effect reach per executed request | `gate-set 'seon.effect/request*` 65–90 ms (1,539 names) | from the cached reach index; 80-test agreement with the gate set (40 in, 40 out) |
| `with-declarations` growth per executed check | 13,969 | 1,237 |

Profile of the unpatched re-check, from the agent's own `seon.profile/begin`/`explain`:

```
1715 ms elapsed · seon.test/run 1698 · with-declarations x13680 1119 · clean-request-handle 958
seon.db/q x84 887 · transact! x3 746 · changed-definition-symbols x2 367 · select 285
definition-digests x4 279 · admit-run 226 · program-written-since? 71 · member-result 61
```

### Producers found

1. **Reuse never hit.** In `select`, `reached` is the gate walk over `observed-changed` together with
   `requested-changed`, and reuse requires `(not (reached test-symbol))`.
   - `my.test/check` passes its changes against the published base as `:seon.test/changed`, so every
     reaching test is excluded before its digest is compared.
   - The recorded member was green. Its reach digest `43c2fd80…` equalled the current one. It carried a
     loaded-commit id and no tested-branch.
   - Only that clause blocked reuse (checked at the REPL).
   - The reach digest already covers each reached definition's `:seon.program/definition-digest`
     (source, contract, effective metadata, resolver context; `seon.program/definition-digest`,
     `src/seon/program.cljc:388`) and schema forms.
2. **`clean-request-handle`, 958–1,025 ms per request.**
   - Overlay write, 480–620 ms for 8 datoms: `seon.db/write-report-error` accounts for 211 ms and the
     rest is unarmed Datahike commit.
   - `seon.sci.eval/acquire!`, 414–450 ms: the base-context memo is keyed by `program-identity`, which
     is memoized by commit id (`src/seon/sci/eval.clj:3085-3098`). A new request commit always misses.
   - `program-overlay-tx` 20 ms, `changes` 17 ms and release 10 ms are small.
   - It is not whole-store work. It is a fresh write and a fresh acquisition per request, whatever the
     content.
3. **The 13,680 `with-declarations` calls are about 6,840 reads counted twice.**
   - The 3-arity delegates to the armed 4-arity.
   - 6,297 of the reads are `db/datoms` in `seon.fn/reverse-closure`, walking the whole reverse closure
     of `seon.effect/request*` to answer "does this member reach effects?"
   - Their time is 56 ms. The count misled. Evidence was added to
     `docs/seon/issues/profile-explanations-charge-other-threads-and-binding-wrappers.md`.
4. **`definition-digests`.** An `or` over `:seon.fn/sym`/`:seon.test/sym` leaves the attribute unbound,
   and Datahike scans: 38 ms current, 180 ms as-of. Two bound queries take about 1 ms each.
5. **`program-written-since?`, 75 ms per admission even with nothing written since.** Datahike's
   `SinceDB` searches its origin under a time predicate
   (`reference-code/datahike/src/datahike/db.cljc:729-732`), so all 170 program attributes' histories
   are filtered.

## The cuts (in `tmp/fix-test-request-cost/test-clj.patch`)

1. **Reuse follows content.** `observed-reached` is the gate walk over changes observed since the
   recorded evidence. Only it excludes reuse. A requested change's walk (`requested-walk`) selects
   candidates.
   - `reached` is the union, which is equal to the former single walk because a reverse closure is
     union-closed.
   - `requested-reached` stays named-only.
   - Gate walks per request: named check without observed change 2 → 1; named with both 2 → 2;
     incremental with a requested change 1 → 2.
2. **`definition-digests`:** one bound query per identity attribute.
3. **Effect reach:** the members an attempt evaluation serves are those whose
   `runner/reach-memberships` names `seon.effect/request*`. That is the reach index `run` already
   reads, instead of `functions/gate-set` over the owner's reverse closure. It walks the same edges
   (calls, references, subject).

Deleted: the `(not (reached …))` pre-filter semantics for requested changes, the per-request
whole-closure effect walk and the unbound `or`. Net source +15 lines, mostly comments (+34/−19).
Regression +17 lines, in the existing class test
`seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content`:
- a request naming `leaf` as changed reuses `direct`'s green while nothing is observed;
- after `(defn leaf [] 4)` it executes `direct`.

Before the patch the first assertion fails; after it both pass (by the REPL run of the same select).
The test's 8 s declared bound covered a 6.3 s maximum; it adds two selections of about 0.15–0.3 s each.
Root should re-measure it.

The earlier assertion there ("A test already reaching changed code cannot reuse green; do not derive its
reach digest") still holds: after `leaf` changes, the namespace request observes it.

## Root runs (serially, after applying both patches)

```
git apply tmp/fix-test-request-cost/test-clj.patch tmp/fix-test-request-cost/selection-test.patch
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn
bin/test-check default --test seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content --include-long
bin/test-check default --ns seon.test.selection-test --include-long
bin/test-check default --ns seon.test.one-request-test --include-long
bin/test-check default --test seon.test.effect-attempt-test/an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt --include-long
bin/test-check default --ns seon.test.overlay-world-test --include-long
bin/test-check default --ns seon.cluster.host-test
```

Then repeat the probe through MCP on a branch after adoption. Expect the second identical
`(my.test/check {})` to show `:seon.test/reused-count 1` in about 0.15–0.25 s, armed.

## Remaining: changed-program checks (a design decision; F08)

About 1 s of a changed check is the per-request world (overlay write about 0.5 s, SCI acquisition about
0.45 s). Astra's F08 says the fix is convergence onto the agent acquisition path. That is right for
the handle assembly (`open-world!`, the hand-assoc'd ownership keys, the fresh roster). It does not by
itself remove the two costs. The content key that would:

1. **Simplest viable constraint (recommended).** Fold the overlay into the checkpoint. Key it by
   `[base cluster effective process (id/digest overlay)]` and write it in `prepare-checkpoint!` before
   the source record. The request is then `registry/branch!` from that head plus the ordinary
   `acquire-context!`.
   - A branch at an already-acquired commit reads its parent's program identity, so the base context
     hits.
   - This removes the per-request overlay write and the hand-built handle (F08's −205/+60).
   - Cost: once per distinct program content (about 1 s).
   - Sacrifice: concurrent agents with different content retire each other's checkpoints under the
     current prefix sweep. That is correctness-safe, but reuse is lost under contention.
2. **Carry Datahike attribute revisions in the provenance.** Use `runner/program-revisions`, and
   compare them in `admit-run` instead of `program-written-since?`.
   - This removes the 75 ms per admission, exactly, including in-transaction values: speculative
     contexts give touched attributes fresh revisions (`datahike/db.cljc:447-465`).
   - Cost: one provenance key with its schema, plus test.clj and runner.clj edits.
   - Sacrifice: a new declared key.
3. **Make the checked writer cheap on a fresh branch connection.** Fix `write-report-error`
   (211 ms) and the commit floor (D15) in `seon.db` and the Datahike fork.
   - This helps every write, not only tests.
   - Cost: hours in the db owner.
   - Sacrifice: none here, but it is cross-owner.

## Files

- Changed and uncommitted, handed to root: `tmp/fix-test-request-cost/test-clj.patch` and
  `tmp/fix-test-request-cost/selection-test.patch`.
- Evidence added: `docs/seon/issues/test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads.md`
  and `docs/seon/issues/profile-explanations-charge-other-threads-and-binding-wrappers.md`.
- `src/seon/test/runner.clj`: unchanged (edit reverted, see above).

## Seen in passing (existing issues)

- A branch forked before an adoption refuses every call (`Host-bound declaration … must change through
  the loaded source files`). Its refusal rendered as the renderer's refusal nested about 6 levels:
  `a-renderer-refusal-renders-through-the-same-renderer-until-the-depth-bound.md`.
- `take-last` answered `#:seon.sci.admit{:opaque "clojure.lang.ArraySeq"}`:
  `sorted-seqs-and-map-key-seqs-print-as-opaque-objects.md`.
- The cluster recorded `class seon.flow.RefusingBuffer cannot be cast to class seon.flow.RefusingBuffer`
  (two DynamicClassLoaders, `seon.flow/work-launcher-step`) during this session. This is a reload of
  `seon.flow` by another lane; I did not file it (the flow lane's area).
- The hook briefly reported `src/seon/test.clj` unreadable at 211:1 while another lane edited it. The
  file was readable again before I read it.

No RESET NEEDED. Probe branches `fix-test-request-cost-1`/`-2`, `fix-trc-probe-1`/`-2` retired;
scratch namespaces removed.
