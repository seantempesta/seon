---
type: landing
status: partly-landed
created: 2026-09-24
tags: [agent-platform, sci, contracts, entry]
---

# Lane fix-entry-eval-2: printed output, nested definitions, refers (2026-09-24)

Owned: `src/seon/sci/eval.clj`, `test/seon/sci/entry_gate_test.clj`, `test/seon/sci/eval_test.clj`, this note.
Default was not touched. Hook publication is off, so default still runs archive `ce7384682`.
**RESET NEEDED** (root's call) before the live JVM shows commits `fbbc9c596` or `8a531008a`.

## After the root ruling (option A, output bound, refers): what landed

- **`fbbc9c596`: printed output is streamed under `max-bytes`.** This is the patch below, applied
  as ruled, with its regression.
- **`8a531008a`: the gate and arming now apply to every definition** (option A). The gate reads
  `definition-rows`: the indexer's `seon.fn/source-rows` analysis, matched to the Vars SCI
  actually created or replaced. It no longer reads the reader's top-level event.
  - It refuses an uncontracted `(do (defn …))`, `(let … (defn …))` or `(def f (fn …))`.
  - Rows the analysis marks `:seon.fn/macro?` or `:seon.fn/host-bound?` (the `defrecord`/`deftype`
    factories `->R`/`map->R`) are not gated. `(defrecord Pair [a b])` is still admitted.
  - A refusal puts back each changed Var's prior root and metadata on the same Var object and
    removes Vars the form created. Callers keep their link.
  - Every contracted definition is armed; before, only the first was.
  - `source-rows` now runs once per evaluation instead of once per changed Var.
  - **Limits:** a call inside the defining form runs before the check. The refused form's other
    side effects (requires, atoms, IO) remain. Without `:seon.db/db` in the request, no rows exist,
    so nothing is gated.
  - Net source lines: eval.clj +80 / −27; entry_gate_test.clj +28 / −2.
- **The refers slice is NOT committed. It breaks the shared MCP entrance. Stopped.** Proven on a
  HEAD+diff archive root: after switching the five `:refer`s to `:as operator.runtime`,
  MCP `eval_clj` in sci mode failed even for `(+ 1 2)` (NPE), and the `branch` tool failed too.
  Three consumers outside my ownership read these Vars *through the refer*:
  - `script/seon/dev/mcp.clj:512` and `:569`: `(ns-resolve 'seon.cluster (symbol "running-instances"))`;
  - `test/seon/custody_stability_test.clj:79-81`, `:207-213`: asserts
    `(ns-resolve consumer 'running-instances)`. Three new failures at `:210`.
  - `src/seon/test/runner.clj:627` `(find-var 'seon.cluster/running-instances)`. This one is
    already nil today (`find-var` ignores refers), so its live-cluster drift snapshot is always
    empty. Filed as `docs/seon/issues/runner-live-cluster-snapshot-reads-a-referred-var-with-find-var.md`.

  The slice is kept as `tmp/fix-entry-2-refers.patch` (the five files plus the `ai_test` `defspec` →
  `deftest` + `assert-check!` rewrite). The eval.clj hunk that installs refers for every row is in
  `tmp/fix-entry-2-refers-eval-hunk.txt`.
  With all of it applied, the scratch root booted: acquisition installed every row's refers with no
  refusal, and all edited files load. The rewritten property passed (run `7afa181cf785`, 1/0).
  To land it, grant `script/seon/dev/mcp.clj`, `test/seon/custody_stability_test.clj` and
  `src/seon/test/runner.clj:627`, and switch each to `'seon.operator.runtime/running-instances`.

### Numbers (same machine, scratch roots from `c327d05b5`, warm, 3 runs after 3 warm-ups; `:seon.eval/duration-ms` / allocated)

| probe | parent `c327d05b5` | with `8a531008a` |
|---|---|---|
| `(+ 1 2)` | 1–3 ms / 645,560 B | 1–4 ms / 691,480 B (+7%: the before-namespace and metadata capture) |
| one contracted `defn` | 81–126 ms / 22,676,024 B | 52–168 ms / 22,607,864 B |
| `(do defn defn)`, both contracted | 190–279 ms / 25,231,304 B | 78–94 ms / 37,346,184 B (+48% B: the second definition is now armed, which was ruled; −65% time from one `source-rows` pass) |
| `(do (defn bare …))` uncontracted | admitted (bypass) | refused, 21–34 ms / 10,202,600 B |

The allocation growth for two definitions is the ruled work of arming the second one. Wall time fell.

### Tests (HEAD `c327d05b5`+diff archive roots; parent = the same HEAD without the diff)

- `seon.sci.entry-gate-test`: run `e0bab8ac7b56`, 5 executed, 33 pass / 0 fail, 9,013 ms.
  With the refers slice as well: run `9124e79594d8`, 33 / 0.
- `seon.sci.eval-test`, `seon.custody-stability-test`, `seon.cluster.mcp-test`: **no red name that is
  new against the parent.** The parent's `eval-test` stopped after 32 members. Three members it never
  ran are red with the diff: `both-entrances…` (:2313), `compiled-runtime-metadata…` (:608, instrument
  arglists nil) and `database-provenance…` (timeout under load). All three were red on the previous
  lane's parent (its landing note and `tmp/fix-entry-parent-reds.txt`).
- `seon.cluster.reply-test` (with the refers slice): 18 executed, 124 / 0.
- Scratch boots took 2:44–4:50 (existing `from-zero-boot-takes-minutes`, over ten seconds).

## The first pass (before the ruling)


## Broken or open first

1. **Item (2): the fork-and-adopt design is not supported by SCI's ctx structure. Stopped, as instructed.**
   Adopting a fork's `:env` into the retained ctx replaces redefined Vars with *new Var objects*.
   Existing interpreted callers keep the old Var, so a redefinition is invisible to them.
   - Cause: a fork gets a new `:sci/generation` (`reference-code/sci/src/sci/core.cljc:345-351`, pinned
     `fcbd88628`). `eval-def` copies any Var whose generation differs from the env's
     (`src/sci/impl/evaluator.cljc:37-43`: `lang/->Var @prev …`). `bind-root!` does the same
     (`src/sci/impl/utils.cljc:367-379`). The SCI analyzer holds the Var object in callers.
   - Probe (JVM, pure `sci/init`): defined `f`→1 and `g` calling `f`. Evaluated `(defn f [] 2)` in a
     fork, then `reset!` the parent env to the fork's env (generation restored). `(f)` returned 2,
     `(g)` returned **1**, `identical?` old/new Var false.
   - Transferring roots instead (`sci/intern` → `bind-root!` in place; the existing
     `transfer-evaluated-roots!` / `accept-candidate!` seam, `eval.clj:1110`, `:3696`) keeps `(g)` = 2.
     But it loses every non-Var effect of the form: after
     `(require '[clojure.string :as s]) (def x 5) (defn f [] 2)` in the fork, the parent had
     no alias `s` and no `x`. For this reason `evaluate-for-install` forks only when the reader
     reports a top-level function.
   - Cost is not the obstacle. On default's ctx (122 namespaces), `sci/fork` + a one-def eval took
     6–19 µs and the namespace-map identity diff 29–57 µs (5 warm runs).
   - Three options for root, simplest first:
     - **(A) recommended.** No fork. Evaluate in the retained ctx as today. Take the definition diff
       `evaluate` already computes (`bindings`/`turn-interns`: every created or replaced
       generation-owned Var with value and metadata). Refuse if any fn-valued one lacks
       `:malli/schema`, restoring each changed Var's prior root and meta in place and unmapping
       new ones. Arm every contracted one (today `definition-row` uses `some`, so only the first
       is armed).
       - Guarantee: gate and arming cover `(do (defn …))`, `let`, `(def f (fn …))` and macros.
         Var identity and aliases are preserved.
       - Cost: about 30–40 lines, no extra per-eval work.
       - Sacrifice: the form's other side effects (require, atoms, IO) stay after a refusal.
     - **(B)** Fork always. Transfer roots plus namespace-binding changes
       (`install-namespace-bindings!`) and newly created namespaces.
       - Cost: about 80–120 lines.
       - Sacrifice: still loses other env state the fork mutates, such as schema registration
         and imports of classes, and duplicates the part of adoption SCI does not offer.
     - **(C)** Adopt the fork env (root's design): refuted above.
   - Limit common to all three: a call to `f` inside the same form that defines it runs before the check.
2. **Item (1) was cancelled by root. My change for it was reverted and not committed.**
   The premise was wrong: `evaluate` already returns printed text as `:seon.cluster.eval/output`.
   `(do (println "hi") 1)` on default gives output `"hi\n"`, shown `"1"`. The toy loop read only
   `:seon.eval/shown`. The real defect found is that printed output is **unbounded**: 20,000 prints
   came back as a 200,744-character result, and the namespace docstring falsely claims a
   `max-string` cap. Filed as `docs/seon/issues/agent-printed-output-has-no-storage-bound.md`.
   The reverted patch is kept at `tmp/fix-entry-2-output-bound.patch`. It streams output under
   `max-bytes` and refuses with `:seon.sci.admit/error`.
   Its regression passed on a HEAD+patch `git archive` root: run `cedb990fbe64`, 4 executed,
   18 pass / 0 fail, 7,575 ms.
3. **Item (3): the 7 refer targets without a SCI Var.** A live probe of default's 770 stored
   refers against `sci/namespace-state` finds 7 unresolved. No exemption was added.
   - `seon.cluster` refers `running-instances` and `root-store-holder`; `seon.cluster.boot`,
     `seon.mcp-test` and `seon.cluster.mcp-test` refer `running-instances`; `seon.cluster.store`
     refers `held-flocks`. All target `seon.operator.runtime`. It lives in
     `resources/seon/operator/runtime.clj`, deliberately outside the indexed roots (its ns
     docstring). It has no `:seon.ns` row, so acquisition never installs it into SCI, and
     `custody_stability_test.clj:203-210` asserts that. Its three `defonce` atoms are process-root
     custody, never program identities.
   - `seon.ai-test` refers `clojure.test.check.clojure-test/defspec`, a third-party macro
     namespace not installed in SCI.
   - Proposed owner fixes, which make "install refers for every row" safe with no filter:
     - Convert the five `:refer` sites of `seon.operator.runtime` (`cluster.clj:53`,
       `cluster/boot.clj:22`, `cluster/store.clj:26`, `test/seon/mcp_test.clj:10`, and the
       `test/seon/cluster/mcp_test.clj` ns form) to the alias most callers already use
       (`[seon.operator.runtime :as operator.runtime]`). Aliases now install for every row. An
       interpreted override that reaches process custody then refuses at analysis by name.
     - Convert `test/seon/ai_test.clj:496` `defspec` to `deftest` +
       `seon.test-support/assert-check!`. The testing skill names this as the canonical property helper.
     - After both, a future unresolvable refer should refuse loudly
       (`sci/core.cljc:808-822`), not be filtered.

## Evidence and numbers

- Named probe before (default, archive `ce7384682`, warm): `(do (println "hi") 1)` took 9 ms and
  9,706,480 B allocated. Default's first evaluation on the new branch (acquisition) took 853 ms and 1.70 GB.
- No after-number, because no change landed.
- The scratch root boot for the patch proof took **3 min 42 s** (existing issue `from-zero-boot-takes-minutes`, over ten seconds).
- The scratch root and its source were stopped (`process-exit? true`) and deleted. The logs are kept at
  `tmp/fix-entry-2-start.log` and `tmp/fix-entry-2-test.log`.

## Net lines

Owned source and tests: 0 (reverted). New issue file: 1. This note: 1.
