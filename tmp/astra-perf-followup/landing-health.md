# Health measurement reads — 2026-09-28

SIMPLER: src/seon/health.clj +4/-6, net -2; remove the intermediate entity-id
query, separate pull-many, and its duplicate refusal branch. Datahike's query pull
seam (reference-code/datahike/src/datahike/query.cljc:2397, pin 05a02804) parses the
pull once. Its dependency extractor at 2862 includes every pulled attribute.
The schema-presence guard and refusal value remain unchanged.

FASTER: live fb1437312, 201 stored measurements, identical immutable database.
Parent/candidate read results equal as sets in every pair. First calls 6.697 ->
7.669 ms (new query planning); warmed pairs 5.960 -> 1.636 and 5.615 -> 1.471 ms.
Captured reads 2 -> 1. seon.health/regressions itself was 4.982 ms on a separate
warm observation. These readers write zero store bytes per 1,000 calls or failures;
MCP settlement overhead is outside the reader and was not isolated from root work.

CORRECT: data equality for 201 live records, plus the new regression
seon.health-storage-test/stored-regressions-follow-measurement-updates. It uses
profile/cell and measurement/significant-tx, canonical writes, and checks that a
new digest and a later bucket update reach the cached regression report. UNRUN by
assignment. Root also runs seon.health-test/a-definition-slower-under-its-new-digest-is-a-regression
and seon.health-test/an-open-fault-is-a-ranked-finding-and-the-report-writes-nothing.

The health feature is NOT O(changed) on a measurement update: its dependency-aware
cache skips unrelated commits, but a relevant update rescans the measurement
population and groups/sorts it. It is O(measurements + versions log versions), not
super-linear in the full population. This cut removes duplicate read work without
adding a second incremental store or weakening what the report compares.

Archive of recorded HEAD plus only health diff: lint src script test --cache false,
0 errors, 1,600 pre-existing warnings, 0 new findings. Contracts compile returned []
on the same archive. No tests, boot, reload or branch writes were started here.
RESTART NEEDED for installed proof and root's central focused tests.
