# Lane fix-refused-read-as-id — landing (2026-09-24)

Class: a refused or absent read handed on as if it were a value
(data-oriented-clojure SKILL.md:58-64). Source: triage
`docs/research/agent-platform/open-errors-triage-2026-09-24.md` groups B and D.

## Broken / unresolved first

1. **D (`4265ebcd`, `39f38724`, `seon.db/basis-t` got nil) is NOT fixed.** The
   hypothesis "`seon.db/db` answers nil on a released connection" is refuted
   (probe below). The nil's producer is the test stand-ins in
   `test/seon/cluster/agent_test.clj:837-845, 899-904, 1002-1005`: they
   `with-redefs` `seon.cluster.prompt/prompt` (a law violation: no Var
   redefinitions) to return `{:seon.error/message "The prelude stand-in
   released."}`. That value is neither `:seon.cluster.prompt/rendered-context`
   nor a `:seon.cluster.prompt/error` (no `error-agent-id`), so turn.clj:4434
   `refusal` is nil and turn.clj:4461 takes the success arm with a "rendered"
   map lacking `:seon.db/db`; `capture-tx` (context.clj:381) then reads
   `(or nil nil)`. The occurrence branches are exactly those tests' agent
   branches (and the fault copy on `:cluster-default`).
   - Exact fix (agent_test.clj, held by m4-n1): the stand-ins return a
     declared prompt error, e.g.
     `{:seon.error/at (Date.) :seon.error/layer :seon.cluster.prompt/stand-in
       :seon.error/operation 'seon.cluster.prompt/prompt
       :seon.error/message "The prelude stand-in released."
       :seon.cluster.prompt/error-agent-id agent-id}` — or better, the
     completion/prompt travels as an injected value instead of a redef.
   - **Open question (unverified):** today the armed `capture-tx` contract
     refuses this exact request by name (probe below), so at the occurrences
     `capture-tx`'s input contract was not in force while `basis-t`'s was.
     Candidates: a re-arming gap during an adoption of `seon.context`, or the
     test branch interpreting `seon.context` rows in SCI without the wrapper.
     Needs a root probe; no context.clj edit was warranted (the `or` is not
     the producer, and changing it would not remove the nil).
2. Sibling of B, not in my files: `seon.turn/latest-answering-turn-t` also
   returns `seon.db/q`'s refusal map through an `[:int]` output contract
   ("refused return value at []: expected an integer, got a map",
   turn.clj:3001 before this change). After this change `unanswered-wakes`
   refuses at `agent-eid` first, so that path is covered there, but the
   function itself still passes a refused read on. Same class; owner of
   turn.clj's next slice.

## Fixed: B (`c0ae227e`, agent-wake-datoms got a map)

`seon.turn/agent-eid` (turn.clj:2566) now has a contract
`[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :int]]` and
throws `seon.db/q`'s refusal whole (`ex-info` with the refusal as data)
instead of returning it. Both readers (`outside-wake-t`, `unanswered-wakes`)
used `if-let` and so treated the map as an id; they now receive either an id
or nil ("no such agent"), and a refused read escapes to the graph's fault
route naming `seon.db/q` — the same path eb14a2f22 made for `next-agent-work`.

## Evidence (MCP JVM probes on default, scratch in-memory stores only)

- Released connection: `(seon.db/db c)` after `d/release` →
  `{:seon.db/invalid-read true, :seon.error/message "Connection has been
  released.", :seon.error/exception-class clojure.lang.ExceptionInfo}` in
  0.91 ms. Not nil (datahike 620721d6 connector.cljc:81-86 throws; seon.db
  resolve-database-value catches into `dependency-error`).
- Armed `capture-tx` refuses `{:rendered-context {:seon.error/message ...}}`
  at `[:seon.cluster.prompt/rendered-context :seon.cluster.prompt/text]`, and
  a nil `:seon.db/db` in either arm, by name.
- B reproduced without redefs on a database value with no `:seon.agent/id`
  installed: `outside-wake-t` → "agent-wake-datoms refused agent-eid ...
  got a map" (5.2 ms); `unanswered-wakes` → "latest-answering-turn-t refused
  return value" (2.8 ms). New `agent-eid` body on the same world throws
  ex-data `{:seon.db/invalid-read true :seon.db/refused-read-operation
  seon.db/q}`; `(:seon.db/invalid-read 42)` is nil (ids pass through).
- `bin/test-check default --test seon.cluster.agent-test/disarm-has-a-declared-loud-turn-completion-backstop`
  (before the load rule): pass 16, 15 962 ms; produced no new basis-t
  occurrence (so D comes from the release-join / other stand-in test).

## Changes

- `src/seon/turn.clj` agent-eid: +14 / −6.
- `test/seon/turn_work_test.clj`: +22, new
  `a-refused-agent-lookup-escapes-as-its-own-refusal` (real `seon.db/q`
  refusal, no redefinition; both derivations must name `seon.db/q`).
- No timing change on the hot path: one keyword lookup on the query result.

## Verification limits — root runs (load rule: not run by this lane)

- `bin/test-check default --test seon.turn-work-test/a-refused-agent-lookup-escapes-as-its-own-refusal`
- `bin/test-check default --ns seon.turn-work-test`
- `bin/test-check default --ns seon.data-shapes-test` (outside-wake-t caller)
- Not loaded into default (hook publication paused); lint only
  (clj-kondo: 0 errors). Adoption of seon.turn at root's checkpoint.
