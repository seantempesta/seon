| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | PASS | 202 | {:taken [], :contract-failures [], :contracts 109, :declared 58, :form-failures []} |
| `entrant-merge-write-back` | entrant | PASS | 485 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 263 | {:facts-errors []} |
| `time-h36` | timing | PASS | 2342 | {:ms 2074, :timings {:read 2.1163800000000004, :evaluate 198.56291499999998, :derive 65.233626, :apply 1580.4390400000002, :settle 226.826958}} |
| `b7-lazy-definition` | effects | PASS | 828 | {:shown ["(42)"], :diagnostics []} |
| `c3-hook-spans` | join | PASS | 605 | {:observed [3 5 3 11], :relative [[2 5 2 11]]} |
| `corpus-join` | observer | PASS | 551 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `world-members-agree` | world | PASS | 695 | {:worlds {:agent-turn {:db-is-connections true, :ctx-custody-is-connection true, :writer-takes-connection true, :landed true, :own-branch? true, :elsewhere f... |
| `v2-4-schema-reflush` | schema | PASS | 323 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.31070800000000004, :evaluate 19.888625000000005, :derive 7.0969999999999... |
| `c5-constructor-absence` | world | PASS | 220 | {:errors [[] [] []]} |
| `speculative-batch-keeps-committed-projection` | schema | PASS | 3122 | {:ruling-b-ms {:before 28, :after 28}, :head-path-ms {:before 6, :after 6}} |
| `oq1-storage-failure` | owner | PASS | 293 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `world-survives-compute-hop` | world | PASS | 530 | {:here #:seon.agent{:id "root"}, :bare {:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema... |
| `v2-6-tag-once` | input | PASS | 152 | {:reader-calls 1} |
| `b6-jvm-throwing-print-and-error` | entrant | PASS | 46 | {:shown ["#object[one-path-proof.host/eval1781150/reify--1781151]" "#:seon.error{:exception-class clojure.lang.ExceptionInfo,\n :message \"jvm boom\"}"], :di... |
| `time-one-defn` | timing | PASS | 2399 | {:ms [339 234 193 198 192], :timings {:read 0.104458, :evaluate 1.997667, :derive 7.821541, :apply 72.57875, :settle 109.204041}, :batch-retained-mb 4.15} |
| `r6-e1-opening-reads-the-inbox` | parity | PASS | 278 | {:inbox-reads ["(my.message/inbox {:seon.agent/id \"root\"})"], :walk-duplicates 1, :opening-forms 10} |
| `supply-from-the-world` | supply | PASS | 345 | {:lacking "Cannot call user/target: :seon.db/connection is unavailable. This evaluation's world holds no :seon.db/connection; pass it explicitly.", :walk (:s... |
| `agent-starts-in-another-namespace` | effects | PASS | 802 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 183 | {:count 52, :designed [6], :ms 24, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `b5-contracts-gate` | landing | PASS | 1194 | {:ms 493, :findings 0, :each []} |
| `v1-red-delta` | render | PASS | 9974 | {:statuses [#:seon.render.var{:pending [user/v1-t]} {} #:seon.render.var{:pending [user/v1-t]} #:seon.render.var{:newly-red [user/v1-t]} #:seon.render.var{:n... |
| `v1-pending-refreshes` | render | PASS | 6101 | {:evidence 53, :current [true false]} |
| `r8-render-read-only` | entrant | PASS | 130 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 3120 | {:ms [273 195 199], :timings {:read 0.113667, :evaluate 1.785417, :derive 7.3125, :apply 74.441042, :settle 115.137209}, :cause "seon.db/nearest-base: the 24... |
| `c3-generated-full-span` | join | PASS | 0 | {:short #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}, :exact :matched} |
| `c3-producer-spans` | join | PASS | 523 | {:origins #:user{spanned {:line 1, :column 1, :end-line 1, :end-column 16}, indented {:line 2, :column 3, :end-line 2, :end-column 24, :expanded-by defn}}} |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 205 | {:diagnostics ["show: stage failed" "show: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: stage failed"]} |
| `attribute-change-equivalence` | schema | PASS | 747 | {:results [true true true]} |
| `hop-plain-executor` | world | PASS | 469 | {:values ["#'user/hopped" "7"]} |
| `c5-error-print-world` | world | PASS | 92 | {:world-at-print [true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `c2-watch-rebinding-origin` | sci-fork | PASS | 602 | {:source nil, :private #:user{watched :rebound}} |
| `b3-malformed-tag-read-once` | reader | PASS | 0 | {:reader-calls 1, :reason "bad tag"} |
| `v2-6-repair-before-require` | input | PASS | 70 | {:values [nil 3 :clojure.string/k]} |
| `b1-value-equal-to-sentinel` | totality | PASS | 0 | {:diagnostics [["observe: review injected failure"] ["observe: review injected failure"] ["observe: review injected failure"]]} |
| `b1-malformed-observation` | totality | PASS | 0 | {:diagnostics ["observe: 2 observed writes are not writes and were dropped"]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 370 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `parity-faults-in-both-replies` | parity | PASS | 1084 | {:mcp-faults 1, :agent-faults 1, :diagnostics ["show: [{:seon.error/at #inst \"2026-09-28T00:32:02.180Z\",\n  :seon.error/data\n    #:seon.render.unknown{:ca... |
| `test-agent` | entrant | PASS | 1639 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 321 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `turn-overhead` | timing | PASS | 2020 | {:kept 29, :kept-plan-chars [4536371 15640 1533 991 652 618 608 608 608 608 608 278], :remaining {:largest-plan "seon.error/for-agent's read stores one whole... |
| `b2-generated-definition-hook` | join | PASS | 7 | {:made [{:name made, :row 2, :col 1, :end-row 2, :end-col 9}]} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `b3-repair-reads-tag-once` | reader | PASS | 0 | {:reader-calls 1, :result {:forms ["[#review/tag 1]"], :text "[#review/tag 1]", :repairs [#:seon.batch{:repair-kind :delimiters, :at 0}]}} |
| `v2-4-register-then-use` | schema | PASS | 518 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `c1-malformed-map-observation` | totality | PASS | 0 | {:diagnostics ["observe: 1 observed writes are not writes and were dropped"]} |
| `captured-locals` | observer | PASS | 337 | {:private #:user{capture-f :captures-locals}} |
| `corpus` | corpus | PASS | 49174 | {:same 84, :designed 10, :facts-errors [], :entities 89, :differ [], :over-1s [["h36-register-22" 2113]], :p90 334, :max 2113, :bad [], :items 94, :p50 222} |
| `oq1-bad-form-logged` | owner | PASS | 512 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `c5-jvm-custody` | entrant | PASS | 45 | {:values [#:seon.agent{:id "root"}]} |
| `extension-replay` | decision | PASS | 169 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `b4-moved-head-feedback` | effects | PASS | 1407 | {:errors ["Program retraction refused."]} |
| `v2-7-no-reverse-requires` | static | PASS | 1647 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 505 | {:read-only [false false false true]} |
| `sink-per-world` | world | PASS | 507 | {:a #:user{counted 1000}, :b #:user{counted 1500}, :jvm-wide 2500} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 6704, :old-gen-after-gc-mb 2563, :processors 18}, :after {:heap-used-mb 5632, :old-gen-after-gc-mb 2843, :processors 18}} |
| `time-acquisition` | timing | PASS | 8227 | {:entities 100, :ms 12, :us-per-entity 120} |
| `blob-get-without-connection` | supply | PASS | 427 | {:values ["the whole value"], :digest "c1a9c9c4cd5488af28d93e7d185c77f4177c84955ba89bc0cb60613f266d0373"} |
| `s3-schema-rehearsal` | landing | PASS | 1465 | {:errors [], :stored 60, :facts-errors [], :keys 58, :notes [{:seon.schema/key :seon.batch/facts-error, :seon.schema/installed? false, :seon.error/message "A... |
| `q5-def-edges` | owner | PASS | 583 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 155 | {:shown ["()" "42"], :diagnostics ["show: [#:seon.error{:at #inst \"2026-09-28T00:32:30.805Z\",\n  :layer :seon.render/invocation,\n  :operation seon.sci.eva... |
| `v2-5-remove-rebind` | effects | PASS | 373 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 315 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `b1-nil-print-failure` | totality | PASS | 1 | {:entries [#:seon.batch{:ordinal 0, :ns-before user, :value nil, :source "nil", :line 1, :shown "#<unprintable nil>", :column 1, :ns-after user}], :diagnosti... |
| `s3-population-admits` | landing | PASS | 1066 | {:head {:refused "Schema population refused :seon.cache/cache (dishonest-generator)."}, :with-batch {:refused "Schema population refused :seon.cache/cache (d... |
| `mcp-equals-agent-turn` | entrant | PASS | 10261 | {:behaviors 9, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 982 | {:facts-errors []} |
| `v2-5-remove-ns` | effects | PASS | 673 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `b6-jvm-floor` | entrant | PASS | 465 | {:jvm-shown-chars 3192, :sci-shown-chars 3192, :jvm-tail "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx... |
| `v2-8-jvm-recorded` | entrant | PASS | 44 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 915 | {} |
| `sci-suite` | sci-fork | PASS | 1 | {:patched {:tests 398, :assertions 1521, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v1-uncontracted-admitted` | render | PASS | 1063 | {:mcp {:stored? true, :errors [], :status #:seon.render.var{:contract :missing, :example "(defn v1-open {:malli/schema [:=> [:cat :int] :int]} [x] x)"}, :ai ... |
| `v2-3-ns-name-shadow` | totality | PASS | 534 | {:values ["42" "#'user/ns-name" "43"]} |
| `branch-entrance` | parity | PASS | 287 | {:created {:seon.store/branch :op-branch-e13e26, :seon.cluster/created? true, :seon.db/basis-t 536886366}, :refusals ["The branch already exists; retire it o... |
| `supply-reads-the-working-value` | supply | PASS | 508 | {:seen [[true false] [true true]]} |
| `r6-shown-by-the-one-print-owner` | print | PASS | 53 | {:shown ["{:seon.error/exception-class clojure.lang.ExceptionInfo,\n :seon.sci.eval/stacktrace [[user 1 1]],\n :seon.error/message \"owner\"}" "(+ 1 2)" "{:a... |
| `q4-lone-key` | owner | PASS | 691 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `c6-nested-world-sink` | world | PASS | 40 | {:a #:review{f 2}, :b #:review{f 1}} |
| `b4-writer-refusal-reply` | totality | PASS | 385 | {:reply #:seon.batch{:commit #:seon.batch{:writer-refusal #:seon.error{:message "review writer refusal"}, :first-refusal "review writer refusal"}, :diagnosti... |
| `v2-3-observer-fails` | totality | PASS | 93 | {:diagnostics ["observe: stop failed"]} |
| `v2-8-nested-render-one-tx` | entrant | PASS | 166 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 263 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 737 | {:branches [":agent-2678ce8b30fc" ":agent-9985979aa941"]} |
| `supply-per-world` | supply | PASS | 528 | {} |
| `b2-exact-span-no-fallback` | join | PASS | 0 | {:join #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}} |
| `parity-render-refusal-never-parks` | parity | PASS | 1072 | {:mcp {:applied? true, :stored? true, :values 2, :shown "{:seon.render/ai no.such/renderer, :x 1}"}, :agent {:applied? true, :stored? true, :values 2, :shown... |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `oq1-head-moved` | owner | PASS | 1298 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `r6-unresolved-names-kept-as-values` | effects | PASS | 824 | {:calls #{clojure.core/defn clojure.set/union}, :requires #{seon.bootstrap clojure.test clojure.set seon.db my.turn my.message}} |
| `worlds-never-cross` | world | PASS | 1136 | {:sources ["(def op3-cross :a)" "(def op3-cross :b)"]} |
| `v2-3-span-invalid` | totality | PASS | 312 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `c3-generated-stored-and-reacquired` | join | PASS | 827 | {:sources ["(defmacro pair [] (list 'do (list 'def 'pa 1) (list 'def 'pb 2)))" "(pair)" "(pair)"], :private {}, :reacquired [[1 2]]} |
| `q3-threaded-facts` | owner | PASS | 232 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `b7-concurrent-observation` | effects | PASS | 3 | {:observed 8000, :late "The evaluation that owned this observation has completed."} |
| `time-chained-40` | timing | PASS | 8028 | {:timings {:read 7.270131999999995, :evaluate 528.8406640000001, :derive 849.6582530000002, :apply 2949.3593339999998, :settle 396.26075}, :retained-forms 79... |

105/105 proofs pass
