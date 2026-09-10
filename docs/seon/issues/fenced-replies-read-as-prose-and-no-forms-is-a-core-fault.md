---
type: issue
status: open
severity: blocker
tags: [reader, reply, turn, errors, live-test]
created: 2026-09-10
---

# A fenced reply is read as prose, and "no forms" is a core fault instead of the agent's error

## Observed (live run 2, default, 2026-09-10 21:22–21:27; landing `live-run-2-landing-2026-09-10.md`)

Three replies from deepseek-flash wrapped their forms in a Markdown fence:

```
```clojure
;; Stop looping. The deftest macro worked. Now run the tests.
(my.test/run)
```
```

Each produced ZERO evaluations. The reader (`src/seon/sci/reader.cljc`,
`src/seon/cluster/reply.clj`) reported `:seon.cluster.reply/no-forms`
("The reply carried no Clojure forms — its whole text read as prose") as a
CORE fault: it rode the fault committer, interrupted the run, and messaged
ROOT ("Core fault :seon.cluster.reply/no-forms reached 3 occurrences …
further occurrences … will not message you"). The agent never saw the
refusal in its own context and lost three of thirty turns.

## Why it is wrong

- AGENTS.md §2.4: an agent mistake becomes a flat `:seon.error` value the
  AGENT sees; core faults are the platform's. A reply with no forms is the
  agent's mistake — exactly the class the §18b fabricated-response rule
  already returns as `:error` in the agent's history.
- A fence is not prose: the text inside `` ```clojure `` … `` ``` `` is the
  reply. Reading the forms inside a fence is the reader's ordinary grammar
  (models write fences constantly), not a special case; the fence
  delimiters themselves are ignored like whitespace. This is general, not
  tuned to one outcome.

## Wanted

- The reader reads forms inside fences; a reply that still has no forms
  is stored as one evaluation carrying the flat error in the agent's
  history (same path as §18b), never a core fault, never a message to root.
- Regression on the canonical harness: a fenced reply evaluates its forms;
  a prose-only reply yields one `:error` evaluation visible in the next
  prompt and no `:seon.error` fault row.
