---
type: landing
status: fork committed and pushed (49ce5cb9); gitlink bumped; RESET NEEDED; Seon regression unrun (root runs below)
lane: fix-sci-fork-vars-opus
created: 2026-09-26
authority: docs/seon/issues/letfn-and-defrecord-with-an-inline-protocol-fail-in-the-agent-repl.md
---

# Landing: vars born in a SCI fork are its own (lane fix-sci-fork-vars-opus, 2026-09-26)

Our SCI fork decides copy-on-write ownership by a var's `:sci/generation` stamp (fork commits 72150fd4,
e663d9e2, 409888f6; `utils/owned-var`, `evaluator/eval-def`). This slice stamps the generation at every var
creation point, so a var is owned by the context in which it was created.

## Broken first

1. **RESET NEEDED.** Default loads SCI from `reference-code/sci` (`deps.edn:58`, `:local/root`) at JVM start.
   Until root runs `bin/seon start --head`, the agent REPL still fails both forms, and the new Seon regression
   is expected to be red.
2. **The Seon regression has not been run** (the lane load rule applies). Root runs the commands below.
3. These dogfood #19 items are out of scope and still open: records print as
   `#object[sci.impl.records.SciRecord]`, and `Math/abs` is unresolved (`:classes`).
4. The SCI suite has 5 failures on the parent 409888f6, and all 5 were already there before this change:
   `function-results-dont-have-metadata` ×2, `error-location-test` ×2 and `error_test/arity-error-test`.
   A `git archive` of HEAD shows the same 5. This slice adds no new failure.

## Model error (reproduced with bare SCI, no Seon code)

The owner is `(= (:sci/generation (meta v)) (:sci/generation env))`. Three places create vars during
evaluation without a stamp, so inside a fork each of those vars counted as inherited:

- `init-var!` (`src/sci/impl/analyzer.cljc` ~805) predeclares a def during analysis. `eval-def` then bound a
  *copy* of it, so defprotocol's `(var P)`, captured during analysis, stayed unbound:
  `SciUnbound cannot be cast to Associative` (`deftype.cljc:436`).
- `-new-var` / `-new-dynamic-var` (`namespaces.cljc` ~1708) create letfn / with-local-vars vars. letfn's
  `alter-var-root` passed through `owned-var`, which tried to copy a var that has no namespace:
  `HasName … nil`.

Fix: `init-var!` stamps its meta with `generation-meta`. The two anonymous constructors go through one
`new-local-var` that stamps the generation of the current ctx (`store/get-ctx`, the same one
`sci-alter-var-root` already reads). `generation-meta` now stores no key when the env has no generation
(unforked), instead of `:sci/generation nil`. Otherwise upstream's `conditional-var-test`
(`(keys (meta #'x))` = `[:name :ns :file]`) turns red. Upstream babashka/sci has no related change:
its `fork` is `(atom @env)` with no generations, and its letfn commits (#480, 25538765) don't touch this.

Repro (`clojure -M -i repro.clj` in reference-code/sci), before → after:

| form in `(sci/fork (sci/init {}))` | before | after |
|---|---|---|
| `(letfn [(f [x] (if (pos? x) (g (dec x)) :done)) (g [x] (f x))] (f 3))` | `HasName … nil` | `:done` |
| `(defprotocol P (m [_])) (defrecord R [a] P (m [_] a)) (m (->R 1))` | `SciUnbound … Associative` | `1` |
| with-local-vars, declare+defn, defmulti, alter-meta! on a private def | ok | ok |
| unforked letfn | `:done` | `:done` |

## Cost (bare SCI, warm, 2 runs each)

- `sci/fork`: 0.68 / 0.61 µs before, 0.68 / 0.68 µs after. `fork` itself is unchanged, so Seon's 4–8 ms
  agent-fork cost is Seon's own context work and is not affected here.
- fork + `(declare q) (defn q [x] (inc x)) (q 1)`: 152 / 156 µs before, 158 / 163 µs after. That is +3%,
  within noise, and well under the 20% bound.

## Changes

- Fork 49ce5cb9 on `seon-env-hook`, pushed to `fork`. `analyzer.cljc` +6/−3, `namespaces.cljc` +9/−2,
  `utils.cljc` +6/−2. New `sci.core-test/vars-born-in-a-fork-are-owned-by-it-test` (+18). It covers letfn,
  with-local-vars, a parent fn's letfn called in a fork, a fork of a fork, the protocol/record form, the
  generation of the predeclared var, and that the parent never sees the fork's vars. It is red on 409888f6
  (5 errors) and green after. Full fork suite, `clojure -M:test:clj-test-runner`: 399 tests, 1502 assertions,
  and only the 5 failures listed above.
- Seon: gitlink `reference-code/sci` 409888f6 → 49ce5cb9. New
  `seon.sci.eval-test/letfn-and-an-inline-protocol-record-evaluate-in-an-agent-fork` (+14). It runs a real
  cluster fork (`test-support/fork-cluster-ctx`) through `eval/evaluate`, asserts the ctx carries a
  generation, and checks that the letfn form answers `:done` and the protocol/record form answers `1`.
- Net: fork source +12 lines, tests +32. No Seon source changed.

## Root runs

    bin/seon start --head        # RESET NEEDED: default must reload SCI from the bumped fork
    bin/test-check default --test seon.sci.eval-test/letfn-and-an-inline-protocol-record-evaluate-in-an-agent-fork
    bin/test-check default --ns seon.sci.eval-test

Then, in MCP SCI on a disposable branch: evaluate both forms and expect `:done` and `1`.

Verification limits: nothing ran against a Seon JVM. clj-kondo on the edited test shows only warnings that
were already there. No Seon `src/` or schema was touched, so the static pre-commit checks don't apply.
