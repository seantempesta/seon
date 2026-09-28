#!/bin/sh
# Focused Datahike history suites (persistent-set index). Usage: dh-tests.sh <out-file>
cd /Users/sean/src/seon/reference-code/datahike || exit 1
set --  "$1"
OUT="$1"
F=""
for n in time-variance-test purge-test index-page-temporal-test query-planner-temporal-test valid-time-test upsert-test upsert-impl-test api-test transact-test versioning-test storage-retention-test back-correction-test tuples-test index-page-test query-test valid-at-test temporal-history-only-test; do
  F="$F --focus datahike.test.$n"
done
# shellcheck disable=SC2086
clojure -M:test -m kaocha.runner ${SUITE:-clj-pss} $F --reporter kaocha.report/dots > "$OUT" 2>&1
