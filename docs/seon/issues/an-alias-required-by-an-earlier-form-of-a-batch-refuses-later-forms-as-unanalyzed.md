---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, fn, analysis, turn, agent-platform]
---

# An alias required by an earlier form of a batch refuses later forms as unanalyzed

This is the cause of `seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts`
running until its request bound (lane fix-turn-hang-opus, default pid 23282, commit 6ab6c29c).

- **The observed value.** One call pass on a member-handle fixture with the local provider
  gave
  `{:seon.turn.work/situation :call, :seon.turn.loop/forms-run 8, :seon.turn.loop/outcome :error}`.
  Form 0, `(require '[seon.schema :as schema] …)`, carries
  `:seon.cluster.eval/error "Cannot classify host binding: clj-kondo holds no analysis of
  schema. Fill the checkout's cache from this JVM's classpath at its REPL:
  (seon.fn.analyzer/analyze-dependencies!)"`. Forms 1-7 have no shown value and no error.
  The run has no `:seon.error/run` facts. The next work is `:open` again, so the agent is
  offered the same correction, and the stub provider sends the same reply.
- **Minimal reproduction** (JVM REPL, default's database, the program's function symbols as
  first-party). Each case runs `#'seon.fn/runtime-analysis-batch` and then
  `#'seon.fn/host-bound-callers`:

  | requests | answer |
  |---|---|
  | `"(require '[seon.schema :as schema])"` then `"(schema/register! ::item-id :int)"` | the refusal "no analysis of schema"; kondo reports `:to schema`, `:alias schema`, and `schema` is among `::analyzer/unanalyzed-namespaces` |
  | `(do (require '[seon.schema :as schema]) (schema/register! ::x :int))` (one form) | `#{}` |
  | `(seon.schema/register! ::x :int)` | `#{}` |

- **Producer.** `runtime-analysis-batch` (src/seon/fn.clj:919) emits a fresh
  `runtime-namespace-form` from the *stored* namespace row before every form (:945-963), so
  an alias added by an earlier form of the batch is unknown to kondo. `host-bound-callers`
  (src/seon/fn.clj:1012) then treats that alias as an unanalyzed dependency namespace, and
  `unresolved-macro-refusal` (:990) refuses the whole batch. The refusal came in with
  a9449b7c3 (F7, 2026-09-24).
- **Smallest fix at the owner.** Emit the namespace form only when a request's namespace
  differs from the previous request's. Kondo then follows the batch's own `require`, as the
  one-form case shows. Regression: the cross-form pair above answers `#{}`.
- **Second defect.** A platform refusal (`:seon.fn/index-refused`, "fill the cache") comes
  back as an agent-correctable evaluation error. The episode is then re-opened and re-called
  up to `:seon.config.run/max-episode-runs` (100), about 22 s each on this fixture, and the
  bound is reached silently: `opening-deferred?` (src/seon/turn.clj:2715) derives no work.
  A core refusal should be stored and delivered as a fault, and reaching the bound should be
  loud.
