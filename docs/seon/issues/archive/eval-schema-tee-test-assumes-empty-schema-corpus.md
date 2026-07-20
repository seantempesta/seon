---
type: issue
status: resolved
severity: friction
tags: [issue, schema, flow]
---

# Make the schema tee test assert its owned row

## Resolution

The fixture that carried this false singleton assumption no longer exists.
Commit `2884c41b` deleted
`test/seon/eval/record_eval_tee_test.cljs` with the retired pod-wide program
replay path. Restoring that test would restore a superseded integration
mechanism rather than repair a maintained fixture.

Current receipt coverage asserts the owned tee row inside the atomic eval
transaction in `test/seon/eval/receipt_test.cljs`; it does not query the
database's complete schema corpus as a singleton. The stale fixture issue is
therefore closed by deletion and replacement coverage.

## Original problem

The former focused `seon.eval.record-eval-tee-test` selector passed the
intended `:probe.dom/dur-secs` schema row and namespace link, but
`data-ns-schema-tee-lands-both-rows-and-upserts-the-ns` asserted that its query
over every `:seon.schema/key` row equaled a singleton. A normal boot schema
corpus made that assertion fail for unrelated data.
