---
type: issue
status: open
severity: blocker
tags: [issue, effect, shell, adoption, issue-family]
---

# An agent cannot make its own source edit live

Observed 2026-09-16 in the issue-context trials on the isolated `trials`
cluster.

`my.edit/form!` / `my.edit/exact!` write the file. Nothing then loads it:
`seon.test/check` reloads only the selected TEST namespaces
(`src/seon/test.clj:314-327`, `(require ns :reload)`), so the edited source
namespace keeps its previously loaded Vars and the issue's test stays red no
matter how correct the edit is. There is no `my.*` adoption surface; the only
in-process adoption is the operator's `bin/seon init --dev NAME --changed
PATH` (`src/seon/cluster.clj` `development-source-refresh!`, private, taking a
running instance).

The trials therefore had to TEACH the adoption call as a shell request in the
issue's problem text. One worker (candidate `namespace-picture`) then did
everything right: it wrote a correct two-site fix with two successful
`my.edit/exact!` calls, and immediately ran the taught call

```clojure
(my.shell/run! {:my.shell/argv ["bin/seon" "--root" "/Users/sean/src/seon/tmp/issue-trials-root"
                                "init" "--dev" "trials" "--changed"
                                "/Users/sean/src/seon/tmp/issue-trials-wt/src/seon/sci/eval.clj"]
                :my.shell/cwd "/Users/sean/src/seon/tmp/issue-trials-wt"})
```

which returned

```
The foreign process was terminated when its evaluation reached its time limit.
```

`:seon.config.shell/time-limit-ms` is 30,000 and the measured adoption of one
changed file on that cluster took 60–90 s. So even the taught workaround
cannot complete. The session ended one step from green with the fix on disk
and the test still red.

Two things are wrong, and the second is the real one:

1. the taught path cannot fit the shell bound;
2. there is no agent-facing way to adopt at all, so "fix the code and prove it
   with the tests" is not a loop an agent can close. Teaching a shell
   invocation of the operator from inside the cluster the operator launched is
   a workaround, not a design.

Acceptance: an agent has a declared call that makes its own committed edit
live in its cluster and returns what changed, bounded by its own declared
bound; `my.test/check` after it observes the new definitions. A regression
proves edit → adopt → red test turns green inside one agent session.
