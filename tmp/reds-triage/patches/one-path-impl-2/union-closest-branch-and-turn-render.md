# Two producer defects in one-path-impl-2's files (reds-triage-opus, 2026-09-28, default 1be0f7448)

## 1. seon.instrument/closest-alternatives picks the wrong union branch (52043f542)
(seon.render/request-profile {:seon.render/profile {:seon.render.profile/token-budget 10}})
=> problem paths [[:seon.render/profile :seon.error/at] [.. :seon.error/layer] [.. :seon.error/operation]]:
the error-base branch (3 missing keys) beats the profile branch (4 missing keys) by count, although the
value carries a :seon.render.profile/* key. Red: seon.render-simplification-test/a-carried-partial-profile-is-refused-at-request-profile.
Suggestion: rank map branches first by how many of the value's keys the branch declares
(m/explicit-keys intersection, descending), then mismatch, problem count, index.

## 2. seon.turn/render-ai passes a read refusal into interrupted-warning (turn.clj:1910-1911)
In the branch agent mcp/reds-triage's SCI context:
(seon.render.transcript/render-run-ai (merge (seon.render.value/transacted run db) {:seon.db/db db ...caps}))
=> seon.turn/interrupted-warning refused argument 0 `evaluations`: a collection member that is a vector —
seon.turn/run-receipts' seon.db/q returned the read refusal "Expected number or lookup ref for entity id, got nil"
(seon.db/query-index-patterns db.clj:747) and render-ai used it as the receipts. Return the refusal (declared
alternative) instead of passing it on; find why the entity id is nil there (the same call from JVM mode renders).

## 3. Cost: render-run-ai over a 2-evaluation run = 1,839 ms cold / 1,007 ms warm, 757,620 armed calls
(projection-cache-value x171,837, schema.datahike/ref-attr-in? x73,484, vector-attribute-in? x48,685,
seon.cache/lookup x48,641): whole-projection work per rendered value. Likely puts
seon.render.transcript-run-test over its 5 s bound. Owners: render/transcript + schema/datahike (astra fixer).
