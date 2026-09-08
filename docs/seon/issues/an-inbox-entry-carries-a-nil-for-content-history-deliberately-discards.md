---
type: issue
status: open
severity: friction
tags: [issue, schema, history, agent, class/present-nil]
---

# An inbox entry carries a nil for the content history deliberately discards

Found 2026-09-08 by `instrumented-gate-backlog-2` under the armed gate
([landing note](../../prds/context-generation/research/instrumented-gate-backlog-2-landing-2026-09-08.md)).

`:seon.cluster.message/content` is declared `:seon.db/no-history? true`
(`resources/seon/schemas/seon.cluster.message.edn:15-19`), so a historical
view of a message HAS no content — deliberately, and that is the ruled
behaviour the schema-lifecycle regressions already pin.

`my.message/listing-entry` (`src/my/message.clj:64-71`) builds its map as a
literal:

```clojure
{:my.message/id (:seon.cluster.message/id message)
 :my.message/at (:seon.cluster.message/at message)
 :my.message/content (:seon.cluster.message/content message)}
```

so on any historical read the entry carries `:my.message/content nil` — a
present nil in a key whose declaration is `[:string {:min 1}]` and which
`:my.message/inbox` requires. Under the contracts every cluster arms,
`my.message/inbox` therefore violates its own OUTPUT contract, and the
violation escapes into whatever replayed the read:

```text
my.message/inbox violated its contract (invalid-output):
  should be a string at [[0 :my.message/content] [1 :my.message/content]]
```

## Where it is red

`seon.db-test/diff-replays-one-read-by-derived-identity`. `seon.db/diff`
replays the read at an earlier basis, the replay returns the contract
refusal, and the diff answers `:seon.db/diff-refused` — so the test sees
`nil` for every diff field it asserts. Note the test's own expected value
still spells `:content nil`, so its expectation was written against the
present nil and is stale in the same way.

## Why this is not a one-line fix

Dropping the key is not obviously right either: `:my.message/content` is
REQUIRED by `:my.message/inbox`'s entry schema, and §2.4 says an unavailable
observation is the TYPED UNKNOWN — never absence, success, or silence. So an
entry read at a basis where content is intentionally unavailable needs a
declared way to say that, which is a schema decision about the agent-facing
inbox shape rather than a repair inside `listing-entry`.

## Acceptance criteria

- A historical inbox entry says, in declared data, that its content is
  unavailable at that basis; it neither stores a nil nor silently omits a
  required key.
- `seon.db/diff` over `my.message/inbox` replays without a contract refusal,
  and `diff-replays-one-read-by-derived-identity` asserts the new shape
  rather than `:content nil`.
