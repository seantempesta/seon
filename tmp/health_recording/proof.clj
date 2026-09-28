;; Workbench of lane health-recording-opus (2026-09-28). The proofs below ran
;; in MCP JVM mode on default (pid 85484, started 2026-09-28T17:34:11Z) against
;; a scratch copy of src/seon/profile.clj + src/seon/profile/measurement.clj
;; loaded as namespace tmp.health-recording.profile (the working-tree text with
;; its `seon.profile/` self-references renamed), on disposable branches
;; :health-recording-proof, :health-recording-final (retired) and
;; :health-recording-tx (live at pause). The directory is tmp/health_recording
;; because the edit hook refuses a namespace whose path has a hyphen.
;; The MCP reader qualifies `.method` symbols and type-hint symbols inside
;; syntax-quote, unlike Clojure's: the committed macros therefore build hinted
;; gensyms outside the syntax-quote and spell interop `(. target method)`.
(ns tmp.health-recording.proof)

(def proofs
  "Named proofs, each with its setup and measured result."
  {:bracket-cost
   {:setup "fresh threads, (inc x) armed, 2,000,000 calls x 7 runs (1 thread) / 500,000 x 5 (8 threads); median ns/call"
    :head {:t1-host 45.4 :t1-world 104.4 :t8-host 542 :t8-world 837}
    :new {:t1-host 48.7 :t1-world 65.5 :t8-host 554 :t8-world 552}
    :note "in a world the new bracket reads the clock twice (HEAD three times: exited-in-world) and *world* once"}
   :bracket-variants
   {:setup "same harness, before self time"
    :ns-per-call {:t1 {:head [40.6 46.4] :array [41.4 41.9] :adders+gauge [38.7 40.1]}
                  :t8 {:head [535 542] :array [575 581] :adders [530 543] :adders+gauge [544 546]}}
    :choice "a LongAdder per bucket: an AtomicLongArray histogram costs +7.5% at 8 threads (one hot slot)"}
   :cell-bytes {:setup "heap delta over 10,000 cells" :bytes-per-cell 1765 :live-cells 2563 :total-mb 4.5}
   :self-time {:setup "outer spins 2 ms and calls inner (1 ms) three times, twice, in a world"
               :result {:outer {:calls 2 :ms 10 :self-ms 4} :inner {:calls 6 :ms 6 :self-ms 6}}
               :observation {:ms 11 :calls 8 :hot [[:inner 6 6] [:outer 2 4]]}
               :second-observation {}}
   :significance
   {:setup "branch :health-recording-final, jitter +-30% seed 42"
    :baseline 1 :noise-10-settles [0 0 0 0 0 0 0 0 0 0] :under-min-calls 0
    :same-digest-4x-slower 1 :after [0 0 0 0 0] :new-digest 1 :new-digest-noise [0 0 0 0 0]
    :stored {"1dbad0" [20 20] "f1cf7c" [22 22]}
    :unchanged-1000-settles {:jitter-200us-3-fns-100-settles 0 :edge-1us-10-fns 39}
    :note "before the rank clamp (p99 never on the single largest call) the edge case wrote 1,220"}
   :transaction-query
   {:setup "branch :health-recording-tx, 45 evaluations (40 ordinary, 3 N+1, 2 slow), one Datalog query with or-join"
    :query-ms 0.23 :matched 5 :observation-us {:median 36 :max 90}}
   :transaction-bytes
   {:setup "idx/-mark new index nodes per commit, 30 interleaved rounds, median bytes/commit"
    :bare 689103 :adjacent-history-unindexed 734585 :adjacent-nohistory-unindexed 744530
    :distant-nohistory-unindexed 916708 :indexed-history 1377834
    :choice "seon.db.transaction/* names, unindexed: +45 KB/commit"}})
