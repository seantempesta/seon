---
title: "Findings patches for held task files"
type: research
status: patches for lane program-context, which holds seon.turn.edn and seon.sci.eval.edn (2026-10-12)
---

# Task entries to add

## `resources/seon/tasks/seon.turn.edn`

```clojure
 #:seon.task{:name "planned-sources-docstring-says-nothing-is-repaired"
             :kind :function
             :intent "`seon.turn/planned-sources` says 'nothing is repaired', but `seon.turn/segmented-sources` in :reply mode runs `seon.sci.forgive/segments`, which repairs unbalanced delimiters and records them as `:seon.cluster.eval/delimiter-repairs` (`seon.turn/repair-data`). Correct the docstring to say the reply is read by `seon.sci.forgive/segments` and unambiguous delimiter repairs are reported beside each source. Proof: the `seon.turn-test` row the implementer writes first asserts a reply with a missing close paren yields a source carrying `:seon.cluster.eval/delimiter-repairs`."
             :must-read #{seon.turn/planned-sources seon.turn/segmented-sources seon.sci.forgive/segments}}
```

## `resources/seon/tasks/seon.sci.eval.edn`

```clojure
 #:seon.task{:name "an-overlay-plan-digests-only-the-authored-rows"
             :kind :function
             :intent "`seon.sci.eval/derive-overlay-plan` calls `seon.sci.eval/loaded-digests` for the branch database as well as the loaded one, so every defn on any branch runs `seon.sci.eval/function-digests` over all ~6,500 functions to find one or two authored rows, and the :seon.sci.eval/loaded-digests entry (a new commit each time) never hits again. Digest only the authored rows plus the functions absent from the loaded program (the plan's `overridden` and `added`); keep `loaded-digests` for the loaded commit only. Proof: the `seon.sci.eval-test` deftest `an-overlay-plan-digests-only-the-authored-rows` the implementer writes first asserts, through `seon.profile/measure`, that the plan for a one-defn branch calls `function-digests` over fewer than 10 functions."
             :must-read #{seon.sci.eval/derive-overlay-plan seon.sci.eval/loaded-digests seon.sci.eval/function-digests seon.profile/measure}
             :evidence #{"docs/research/agent-platform/2026-10-12/restart-audit/cache-hits.md"}}
```
