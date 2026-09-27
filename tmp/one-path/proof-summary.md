| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | PASS | 213 | {:taken [], :contract-failures [], :contracts 99, :declared 59, :form-failures []} |
| `entrant-merge-write-back` | entrant | PASS | 550 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 301 | {:facts-errors []} |
| `time-h36` | timing | PASS | 6067 | {:ms 5800, :timings {:read 2.1657509999999993, :evaluate 223.44083599999996, :derive 82.90700100000001, :apply 4025.303084, :settle 1465.033792}} |
| `b7-lazy-definition` | effects | PASS | 612 | {:shown ["(42)"], :diagnostics []} |
| `corpus-join` | observer | PASS | 662 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `world-members-agree` | world | PASS | 1021 | {:worlds {:agent-turn {:db-is-connections true, :ctx-custody-is-connection true, :writer-takes-connection true, :landed true, :own-branch? true, :elsewhere f... |
| `v2-4-schema-reflush` | schema | PASS | 291 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.32504, :evaluate 20.999749, :derive 6.877207, :apply 112.490791, :settle... |
| `speculative-batch-keeps-committed-projection` | schema | PASS | 3758 | {:ruling-b-ms {:before 33, :after 33}, :head-path-ms {:before 4, :after 5}} |
| `oq1-storage-failure` | owner | PASS | 472 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `world-survives-compute-hop` | world | PASS | 1106 | {:here #:seon.agent{:id "root"}, :bare {:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema... |
| `v2-6-tag-once` | input | PASS | 216 | {:reader-calls 1} |
| `b6-jvm-throwing-print-and-error` | entrant | PASS | 66 | {:shown ["#object[one-path-proof.host/eval665392/reify--665393]" "#:seon.error{:exception-class clojure.lang.ExceptionInfo,\n :message \"jvm boom\"}"], :diag... |
| `time-one-defn` | timing | PASS | 2754 | {:ms [293 261 278 279 304], :timings {:read 0.131583, :evaluate 3.229834, :derive 9.999542, :apply 110.864459, :settle 170.593334}, :batch-retained-mb 43.96} |
| `r6-e1-opening-reads-the-inbox` | parity | PASS | 1168 | {:inbox-reads ["(my.message/inbox {:seon.agent/id \"root\"})"], :walk-duplicates 1, :opening-forms 10} |
| `agent-starts-in-another-namespace` | effects | PASS | 887 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 216 | {:count 52, :designed [6], :ms 27, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `contract-warning` | owner | PASS | 1293 | {:note "user/nc1 has no :malli/schema; specify it before proposing a merge.", :warning [user/nc2 user/nc3 user/nc1]} |
| `b5-contracts-gate` | landing | PASS | 1184 | {:ms 387, :findings 0, :each []} |
| `r8-render-read-only` | entrant | PASS | 216 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 5796 | {:ms [260 235 247], :timings {:read 0.095083, :evaluate 1.866292, :derive 8.817625, :apply 96.296833, :settle 136.343417}, :cause "seon.db/nearest-base: the ... |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 274 | {:diagnostics ["show: stage failed" "show: stage failed" "conclude: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: ... |
| `attribute-change-equivalence` | schema | PASS | 918 | {:results [true true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `b3-malformed-tag-read-once` | reader | PASS | 0 | {:reader-calls 1, :reason "bad tag"} |
| `v2-6-repair-before-require` | input | PASS | 87 | {:values [nil 3 :clojure.string/k]} |
| `b1-value-equal-to-sentinel` | totality | PASS | 0 | {:diagnostics [["observe: review injected failure"] ["observe: review injected failure"] ["observe: review injected failure"]]} |
| `gate-runs-tests-in-their-namespace` | gate | PASS | 1695 | {:gate #:seon.gate{:sym gjf.core/f, :blocks? false, :tests 1}, :refusal {:seon.gate/sym gjf.core/f, :seon.gate/blocks? true, :seon.gate/tests 1, :seon.gate/n... |
| `b1-malformed-observation` | totality | PASS | 0 | {:diagnostics ["observe: 2 observed writes were not maps and were dropped"]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 448 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `gate-judges-the-change` | gate | PASS | 1873 | {:still-red-gate #:seon.gate{:sym user/gj, :blocks? false, :tests 2, :still-red [#:seon.test{:pass-count 0, :fail-count 1, :error-count 0, :sym user/gj-red}]... |
| `parity-faults-in-both-replies` | parity | PASS | 1083 | {:mcp-faults 1, :agent-faults 1, :diagnostics ["show: [{:seon.error/at #inst \"2026-09-27T23:26:24.502Z\",\n  :seon.error/data\n    #:seon.render.unknown{:ca... |
| `test-agent` | entrant | PASS | 1052 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 447 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `turn-overhead` | timing | PASS | 8580 | {:kept 22, :kept-plan-chars [4536371 15640 1533 991 652 618 608 608 608 608 608 278], :remaining {:largest-plan "seon.error/for-agent's read stores one whole... |
| `b2-generated-definition-hook` | join | PASS | 6 | {:made [{:name made, :row 2, :col 1, :end-row 2, :end-col 9}]} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `b3-repair-reads-tag-once` | reader | PASS | 1 | {:reader-calls 1, :result {:forms ["[#review/tag 1]"], :text "[#review/tag 1]", :repairs [#:seon.batch{:repair-kind :delimiters, :at 0}]}} |
| `v2-4-register-then-use` | schema | PASS | 570 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `captured-locals` | observer | PASS | 417 | {:private #:user{capture-f :captures-locals}} |
| `jvm-gets-feedback` | entrant | PASS | 264 | {:feedback #:seon.feedback{:changed []}} |
| `corpus` | corpus | PASS | 50825 | {:same 84, :designed 10, :facts-errors [["h32-multi-arity" ["The install gate refused user/h32-f: its own contract check failed"]]], :entities 73, :differ []... |
| `oq1-bad-form-logged` | owner | PASS | 577 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `extension-replay` | decision | PASS | 328 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `b4-moved-head-feedback` | effects | PASS | 1838 | {:errors ["Program retraction refused."], :feedback #:seon.feedback{:changed [user/review-new], :missing-contracts [user/review-new]}} |
| `v2-7-no-reverse-requires` | static | PASS | 1880 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 617 | {:read-only [false false false true]} |
| `sink-per-world` | world | PASS | 547 | {:a #:user{counted 1000}, :b #:user{counted 1500}, :jvm-wide 2500} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 1980, :old-gen-after-gc-mb 1075, :processors 18}, :after {:heap-used-mb 1632, :old-gen-after-gc-mb 1297, :processors 18}} |
| `time-acquisition` | timing | PASS | 12469 | {:entities 100, :ms 14, :us-per-entity 140} |
| `s3-schema-rehearsal` | landing | PASS | 18014 | {:errors [":seon.batch/sink references predicate seon.batch/sink?, but its existing program-graph call edges do not yet prove a pure, capability-free transit... |
| `q5-def-edges` | owner | PASS | 812 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 103 | {:shown ["()" "42"], :diagnostics ["show: [#:seon.error{:at #inst \"2026-09-27T23:26:47.270Z\",\n  :layer :seon.render/invocation,\n  :operation seon.sci.eva... |
| `v2-5-remove-rebind` | effects | PASS | 400 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 397 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `b1-nil-print-failure` | totality | PASS | 1 | {:entries [#:seon.batch{:ordinal 0, :ns-before user, :value nil, :source "nil", :line 1, :shown "#<unprintable nil>", :column 1, :ns-after user}], :diagnosti... |
| `s3-population-admits` | landing | PASS | 1208 | {:head {:refused "Schema population refused :seon.cache/cache (dishonest-generator)."}, :with-batch {:refused "Schema population refused :seon.cache/cache (d... |
| `mcp-equals-agent-turn` | entrant | PASS | 10609 | {:behaviors 9, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 340 | {:facts-errors []} |
| `entrant-gate` | entrant | PASS | 500 | {:facts-errors ["The install gate refused user/gt1: its own contract check failed"]} |
| `v2-5-remove-ns` | effects | PASS | 553 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `b6-jvm-floor` | entrant | PASS | 833 | {:jvm-shown-chars 3192, :sci-shown-chars 3192, :jvm-tail "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx... |
| `v2-8-jvm-recorded` | entrant | PASS | 60 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 1008 | {} |
| `sci-suite` | sci-fork | PASS | 0 | {:patched {:tests 397, :assertions 1517, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v2-3-ns-name-shadow` | totality | PASS | 537 | {:values ["42" "#'user/ns-name" "43"]} |
| `r6-shown-by-the-one-print-owner` | print | PASS | 82 | {:shown ["{:seon.error/exception-class clojure.lang.ExceptionInfo,\n :seon.sci.eval/stacktrace [[user 1 1]],\n :seon.error/message \"owner\"}" "(+ 1 2)" "{:a... |
| `q4-lone-key` | owner | PASS | 834 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `b4-writer-refusal-reply` | totality | PASS | 419 | {:reply #:seon.batch{:commit #:seon.batch{:writer-refusal #:seon.error{:message "review writer refusal"}, :first-refusal "review writer refusal"}, :diagnosti... |
| `v2-3-observer-fails` | totality | PASS | 95 | {:diagnostics ["observe: stop failed"]} |
| `feedback-hub-fresh` | timing | PASS | 1198 | {:bounded-text {:fresh-ms 29, :tests 10, :counts {:never-run 10}}, :shown-value {:fresh-ms 17, :tests 22, :counts {:never-run 22}}, :config-effective {:fresh... |
| `v2-8-nested-render-one-tx` | entrant | PASS | 199 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 334 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 580 | {:branches [":agent-e8a0c89f174a" ":agent-aac4fb239d94"]} |
| `b2-exact-span-no-fallback` | join | PASS | 0 | {:join #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}} |
| `parity-render-refusal-never-parks` | parity | PASS | 1003 | {:mcp {:applied? true, :stored? true, :values 2, :shown "{:seon.render/ai no.such/renderer, :x 1}"}, :agent {:applied? true, :stored? true, :values 2, :shown... |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `gate-callee-contract-is-feedback` | gate | PASS | 1200 | {:findings [#:seon.gate{:contract user/cal2, :arguments [-1]}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `oq1-head-moved` | owner | PASS | 1973 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `r6-unresolved-names-kept-as-values` | effects | PASS | 1008 | {:calls #{clojure.core/defn clojure.set/union}, :requires #{seon.bootstrap clojure.test clojure.set seon.db my.turn my.message}} |
| `worlds-never-cross` | world | PASS | 959 | {:sources ["(def op3-cross :a)" "(def op3-cross :b)"]} |
| `v2-3-span-invalid` | totality | PASS | 467 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `feedback-from-facts` | owner | PASS | 1582 | {:feedback #:seon.feedback{:changed [user/fb-f user/fb-g], :missing-contracts [user/fb-f], :tests #:user{fb-t :never-run}, :counts {:never-run 1}}, :contract... |
| `q3-threaded-facts` | owner | PASS | 306 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `b7-concurrent-observation` | effects | PASS | 3 | {:observed 8000, :late "The evaluation that owned this observation has completed."} |
| `time-chained-40` | timing | PASS | 11711 | {:timings {:read 10.140291000000003, :evaluate 351.93854299999987, :derive 1166.8941270000005, :apply 4565.457460000001, :settle 321.213833}, :retained-forms... |

94/94 proofs pass
