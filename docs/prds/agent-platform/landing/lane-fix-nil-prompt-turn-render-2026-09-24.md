---
type: landing
status: landed (4 commits); installed proof on default pending root's checkout restart
lane: fix-nil-prompt-turn-render
created: 2026-09-24
report: docs/research/agent-platform/ambiguous-nil-producers-2026-09-24.md
---

# Lane fix-nil-prompt-turn-render — 2026-09-24

Four producers in prompt, turn and render returned nil, a default or the next
source when a read, parse or derivation failed. Each one now returns or throws
its declared error. A priority regression on default was fixed in the same
render/evaluation handoff: the turn carried a refused render profile as its
profile, which broke every contracted-call failure's rendering.

## Broken first

- **Installed proof on default is not recorded yet.** All proof below comes from a
  `git archive` snapshot root, HEAD `3ff123708` plus this diff. Root then ruled
  that lanes must not boot snapshot roots. The snapshot was stopped and deleted:
  pid 61720 exited, and its directory was removed. Default was still running with
  hook publication off, so it does not have these commits yet.
  **RESET NEEDED / adoption:** once default runs the checkout, run
  `bin/test-check default --ns seon.render-simplification-test`, then
  `--ns seon.cluster.prompt-test` and `--ns seon.turn-test`. Then confirm that
  open faults `c3a91308…` and `17ae2cc0…` stop recurring.
- **Other reds remain in the three namespaces**, both at HEAD and with the diff:
  - timeouts: tests running 10–18 s against the 5 s bound, with load average
    9–23;
  - `prompt/capture-mismatch` returns a return-contract error at `prompt.clj:410`
    (this was already present in fix-config-read's baseline);
  - render-simplification: arity/selection reds with
    "unexpected validator compilation";
  - turn-test: fixture refusals over `:seon.agent/branch`, `settle!` and
    `open-turn`.

  I compared the red-member sets of HEAD and the diff (below). No member is red
  only with the diff, except the new regression's duration on a cold snapshot,
  which passed later. These reds are not attributed to this change.
- **A foreign test had replaced a Var in default.** `runtime_status` listed
  `seon.fn.analyzer/analyze` as replaced by root class
  `seon.fn_test$fn__…`, and every SCI `defn` on my branch then failed with
  "analysis must not run". This is a `with-redefs`-style leak from `fn_test`.
  I did not file an issue for it; root should check.
- **Foreign caller with the same defect:** `src/seon/render/transcript.clj:2030`
  assocs `(render/request-profile request)` as `:seon.render/profile` without
  checking `:seon.render/refused-member`. It is not my file.

## Rows (census `ambiguous-nil-producers-2026-09-24.md`)

1. **`prompt/calibration-for`** (S1, class 4+5), commit `97b17141a`.
   - Probe on default, JVM, schema-less memory store: `db/q` returned
     `{:seon.db/invalid-read true, :seon.error/message "seon.db/q cannot read uninstalled attribute :seon.ai/model."}`,
     but `model-calibration` answered `{:basis :shipped-prior :chars-per-token 3.2 :sample-count 17}`.
     That is the same value a model with no attempts gets.
   - Fix: the invalid read is returned. All three arities of `model-calibration`
     declare `:seon.db/invalid-read-error`, and `prompt` refuses with it (it
     attaches agent-id and a derivation observation).
   - `agent-calibration` already passes `:seon.error/value` through; the
     transcript's `outline-calibration` shows it.
   - Timing: warm, default, 1.39 ms before. After, the invalid read is a
     one-key check.
2. **`turn/receipt-value` / `red-receipt?`** (S2, class 1), commit `48251302b`.
   - The census premise was stronger than stated. Shown text is display text, not
     EDN. On default, 2 of 142 receipts are unreadable (`#'my.agents.root/largest`,
     which is green). An SCI unbound value shows as
     `[#object[sci.impl.vars.SciUnbound]]` (probed on branch
     `fix-nil-prompt-turn-render`).
   - So `unbound-value?` over EDN-read shown text can **never** be true: that arm
     of `red-receipt?` was dead, and every stored unbound receipt answered green.
     Answering "unreadable ⇒ red" would instead have turned every def receipt red.
   - Fix at the producer: `evaluation-facts` records "The admitted result
     contains an unbound var." as the receipt's error, taken from the live
     `:seon.sci.admit/value`. `red-receipt?` reads only the error and interruption
     facts.
   - `receipt-value` now uses `repl/shown-value` (unreadable text stays its
     string, and nil means only no shown text). It no longer uses a
     `(catch Throwable _ nil)`.
3. **Turn dial readers** (S2, class 5), commit `410191cd9`.
   - `agent-dial` reads the overlay, then the carried value, then the effective
     config. A refused overlay or effective read throws that error as its
     `ex-data`, and it never falls through.
   - Callers are `await-turn-permit!` (an absent backstop now refuses by name,
     where before it passed nil to `async/timeout`) and `write-refusal-bound`
     (a refused config used to be blamed on a missing manifest).
   - `gate-function-install` throws a refused config read instead of passing a
     nil case count to accretion.
4. **`render/request-projection`** (S2, class 3+6), commit `c1cd41b25`. A refused
   database read throws as its `ex-data`; nil means only that nothing was
   supplied. I chose throwing over returning the error because the 11 callers
   index the result as a projection, and each caller's contract already declares
   `:seon.db/db :seon.db/database-value`.

## Added by root: profile regression and cross-cutting row 6 (commit `c1cd41b25`)

- **Cause (source):** `turn/evaluate-sources` derived its profile from
  `{:seon.db/db basis :seon.agent/id agent-id}`, which names no cluster. Since
  `91c9079a9`, `request-profile` refuses that request. The refusal was carried as
  the turn's `:seon.render/profile`, and `shown-result` handed it to
  `error/prepare-result`, whose contract then refused.
- On HEAD in default, `seon.cluster.prompt-test` had 12 errors, and all of them
  were this prepare-result refusal.
- **Fix:**
  - `request-profile` reads the request's `:seon.boot/cluster-name` (the caller
    wins), then the context environment.
  - `evaluate-sources` names its cluster, and it throws a refused profile.
  - `shown-result` throws a refused profile.
  - `evaluate` hands the render the projection it holds and the profile, derived
    once (row 6).
  - `request-projection` asks a supplied projection first.
- `evaluate-sources` also stopped merging a refused agent-overlay read into the
  cluster handle (same class).
- Not done from the row 6 plan: deleting `request-projection`'s context
  fallback. Only the reorder was granted.

## Evidence (snapshot root, HEAD `3ff123708` + diff; load average 9–23)

| test (snapshot) | result |
|---|---|
| `render-simplification-test/an-evaluation-renders-with-the-profile-its-caller-names` | pass 5/5, 11.5 s run (cold) |
| `render-simplification-test/a-supplied-projection-is-the-one-a-render-asks` | pass 1/1 |
| `render-simplification-test/a-refused-database-read-is-not-an-absent-database` | pass 4/4 |
| `prompt-test/an-unread-usage-history-is-not-an-empty-one` | pass 4/4 |
| `turn-test/an-unbound-result-settles-red-and-unreadable-shown-text-does-not` | pass 9/9 |
| `turn-test/an-agent-dial-read-that-refuses-is-not-an-absent-dial` | pass 3/3 |
| `prompt-test/prompt-prices-the-exact-retained-history` | 9 pass, duration-only red (15.8 s); at HEAD: prepare-result error |

Namespace runs (the 120 s check limit cut all three; the rest were pending):

| namespace | HEAD on default (`ce5ad30dd`) | snapshot, HEAD + diff |
|---|---|---|
| `prompt-test` | 41 pass / 0 fail / **12 error** (all prepare-result) | 65 pass / 6 fail / 2 error (durations, capture-mismatch, one unfinished) |
| `render-simplification-test` | 65 / 35 / 11 | 81 / 38 / 10 |
| `turn-test` | 135 / 13 / 10 | 132 / 10 / 6 |

Red-member diff (sets of `red` lines): no member is red only with the diff.
The evaluation-profile regression was red only on duration in the first cold
run, before its fixture dropped `seed-cluster!`/`config/apply!`. Raw outputs:
`tmp/fix-nil-prompt-turn-render/{head-ns,ns}-*.txt` and `final.txt`.

## Lines

- Source: `prompt.clj` +34/−26, `turn.clj` +62/−33 (including an 11-line move
  of `unbound-value?` above its new caller), `render.clj` +20/−12,
  `sci/eval.clj` +10/−1. Net about **+54**, with the move counted once.
- Tests: `prompt_test` +26, `turn_test` +76, `render_simplification_test` +58.

## Commits

`c1cd41b25` render/eval profile, projection and refused-read handoff ·
`97b17141a` prompt calibration · `48251302b` unbound receipt · `410191cd9` agent dials.
