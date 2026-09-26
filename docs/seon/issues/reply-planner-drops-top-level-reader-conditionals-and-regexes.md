---
type: issue
status: open
severity: correctness
created: 2026-09-25
tags: [issue, reader, turn, one-repl-path]
---

# The reply planner drops top-level reader conditionals and regexes

`seon.turn/planned-sources` (the model-reply splitter) silently drops top-level `#?(…)` and `#"…"` forms that the one reader
reads, and treats `1 #?(:clj 2)` as prose (design-one-pipeline-opus, one-repl-language-2026-09-25.md rev 4). A form the agent
wrote never evaluates and nothing says so. Owner: the reply splitter must delegate form boundaries to the one reader
(`seon.sci.reader`), never classify by its own text rules; one-reader-collapse slice S4 moves fences/prose there.

## Evidence 2026-09-26: an alias required earlier in the same text drops the form that uses it

Same class, another trigger. Found by design lane design-repl-entry-renders
(`docs/research/agent-platform/repl-entry-renders-design-2026-09-26.md` §0 D15). Default ran f0efd9cc8, and the
probe was a JVM scratch call on a disposable branch:

```clojure
(seon.turn/planned-sources "(require '[clojure.string :as dre-s3])\n::dre-s3/k\n(+ 1 2)" 'user 100000)
;; => two sources: "(require '[clojure.string :as dre-s3])" and "(+ 1 2)"; `::dre-s3/k` is gone, no refusal
```

The planner reads the whole text in `user` before any form runs. The alias `dre-s3` does not exist yet, so the
auto-resolved keyword fails to read, and the planner drops it without a word.

The same forms handed to `seon.turn/evaluate-sources` as separate `:seon.cluster.reply/form` entries evaluate
correctly: `(require …)`, then `::dre-s2/k` ⇒ `:clojure.string/k`. That is because `evaluate-for-install` reads
each form in the SCI namespace table the earlier forms left (`sci/eval.clj:4467-4489`).

This confirms the owner direction above: form boundaries must come from the one reader, form by form, in the
namespace in effect.

Regression: a reply of `(require '[clojure.string :as a])`, `::a/k`, `(+ 1 2)` evaluates three forms, and the
second shows `:clojure.string/k`.
