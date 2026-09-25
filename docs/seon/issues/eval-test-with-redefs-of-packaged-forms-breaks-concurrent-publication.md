---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, tests, with-redefs, publication]
---

# eval-test's with-redefs of packaged-forms breaks concurrent publication

`test/seon/sci/eval_test.clj:172` (`database-provenance-regenerates-the-base-and-reverts-for-retained-forks`) wraps a call in
`(with-redefs [schema.edn/packaged-forms (fn [] (throw …"A database-derived base must not read schema files."))])`. `with-redefs`
replaces the root binding JVM-wide, so a `bin/seon init --dev` adoption running at the same moment (2026-09-25 ~23:05Z) refused
with that message. AGENTS.md forbids `with-redefs` over default's Vars. Fix: assert the property with an injected world (the base
builder takes its schema source as a value, or count reads through read evidence), then delete the redef.
