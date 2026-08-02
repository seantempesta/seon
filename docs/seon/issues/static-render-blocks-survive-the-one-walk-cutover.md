---
type: issue
status: open
severity: friction
tags: [issue, deletion, rendering, testing]
---

# Delete static render blocks left by the one-walk cutover

## Problem

The one fresh namespace walk removed seeded static block membership, but a
large static page implementation remains in fresh source with only tests,
comments, and CSS as readers. The namespace page and prompt now render family
lenses reached by `seon.render.walk`; these old root, transcript, namespace,
execution, and fleet blocks are a second render vocabulary that nothing in
the live route or schema selects.

Because fresh source is indexed into the program graph, this residue is not
inert archive: agents and maintainers still encounter callable functions and
docstrings that describe the superseded static-block model.

## Evidence

- Commit `29794272b` removed the seeded block vectors and replaced prompt/page
  selection with one walk, but left `src/seon/render/root.clj` and most of the
  old static functions in place.
- No production namespace or schema references
  `seon.render.root/header-html`, `agents-html`, `messages-html`,
  `tokens-html`, `text-html`, or `problems-html`. The only executable reader
  is `test/seon/render/root_test.clj`, which tests `agents-html`; an unused
  `seon.render.root` require also survives in
  `test/seon/ai_stream_fold_test.clj:36`.
- `src/seon/render/agent.clj:87-335` retains `agent-header-html`,
  `transcript-html`, `namespace-ai`, and `namespace-html`. Their readers are
  confined to `test/seon/render/agent_test.clj`; the live schema selects only
  the family lenses `agent-ai` and `agent-html` at
  `resources/seon/schema/run.edn:17-18`.
- `src/seon/context.clj:58-70,162-168` retains the old scaffold
  `execution-ai` and a duplicate `contribution-tokens` estimator with no live
  reader. `seon.cluster.prompt` calls `tokens/estimate` directly while
  `contribution-hash` remains genuinely live.
- `src/seon/oversight.clj` has no production or schema reader. Its only
  executable reader is `test/seon/oversight_test.clj`; CSS selectors and a
  stale comment at `src/seon/oversight.clj:51` still claim the cluster
  requires `seon.render.root`, which current source does not.
- `src/seon/render/walk.clj:230` still cites
  `seon.render.root/messages-html` as an existing consumer even though no
  call remains.
- The supplied `src/seon/render/value.cljc` lead is narrower than a dead JVM
  require: `seon.schema/sha-256` is called on the CLJ branch at `:32-35`, but
  the `seon.schema` require at `:7` is unconditional and therefore unused on
  the CLJS branch. Clj-kondo reports exactly the CLJS-side warning. The cleanup
  is to make the require CLJ-only while preserving the live digest owner, not
  to delete it wholesale.

## Owner

The one `seon.render.walk` namespace-page and prompt pipeline. Family lenses
selected by schema facts survive; static blocks with no selected membership
do not.

## Acceptance

- Delete `seon.render.root`, `seon.oversight`, their test/CSS closure, and the
  unselected static functions in `seon.render.agent` and `seon.context`.
- Delete or rewrite tests so the recurring web/prompt gates exercise the live
  namespace walk and family lenses, not direct calls that manufacture readers
  for removed blocks.
- Remove comments/docstrings that describe seeded block sets or cite deleted
  render functions.
- Make `seon.render.value`'s `seon.schema` require reader-conditional so both
  halves of the portable namespace have an honest dependency set.
- A source/schema/test reference chase finds one live render selection
  mechanism, and current namespace page, debug page, prompt, and SSE proofs
  remain green.
