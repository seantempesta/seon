---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# Reference: the proof test `seon.db-overhead-test/query-preparation-has-bounded-armed-work`

Kind: deftest whose `:seon/tasks` names the task. Declared AI render: `:seon.test/test` → `seon.render.test/render-ai`.

```clojure
(def proof 'seon.db-overhead-test/query-preparation-has-bounded-armed-work)
(seon.render.test/render-ai {:seon.render/value (seon.db/pull dbh '[*] [:seon.test/sym proof]) :seon.db/db dbh :seon.render/distance 1})
```

Reply as the agent sees it (72 ms, 5,220 armed calls):

```clojure
#:seon.repl{:value {:seon.test/sym
   seon.db-overhead-test/query-preparation-has-bounded-armed-work,
 :seon.render.test/state "pass",
 :seon.test/run-basis-t 536872188,
 :seon.render.test/host {:seon.test/host :seon.test.host/in-process}}, :result result/e3097634e6ebf, :ms 72, :calls 5220}
```

The same render from the agent's own branch (`(seon.db/db)` there) would read `seon.test/recorded-result` as
"No admitted execution is recorded for this test." (probed on fresh agent `context-mockups-probe`). The task render in
01 shows that as "unrun or incomplete".

For comparison, `my.test/check` on the agent's branch answered `:seon.test/passed? true` (3 pass) in 7,799 ms
(395,298 armed calls; the test body ran in 115 ms), plus about 700 tokens of digests and timings.

## What is wrong

- **Says nothing about what the test asserts.** The agent cannot tell that the proof checks "≤ 40 armed calls", which
  is exactly what makes this task's proof not refute its intent: it is green while the cost the intent names remains
  (117 us vs 4 us). Task `seon.render.test/a-test-render-omits-what-the-test-asserts`.
- **`:seon.test/run-basis-t 536872188`** is bookkeeping the agent cannot use.
- **The state depends on custody** (green here, unknown on the agent branch). Task
  `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence`.
- **`:seon.render.test/host`** names the in-process host, which the agent cannot use either.

## What the render should do

- `{:test sym :state :green|:red|:never-run :asserts [the is-forms] :failing [first failing assertion as data]}`
  plus the operation `(my.test/check {:seon.test/changed [sym]})`.
- Read the state at the task home, or reuse it by reach digest.
