| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | **FAIL** | 214 | {:taken [:seon.batch/stage :seon.batch/member-of :seon.batch/ns :seon.batch/unread-from :seon.batch/structured? :seon.batch/sym :seon.batch/applied :seon.bat... |
| `entrant-merge-write-back` | entrant | PASS | 681 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 459 | {:facts-errors []} |
| `time-h36` | timing | PASS | 3238 | {:ms 2940, :timings {:read 2.7525789999999994, :evaluate 530.2615840000001, :derive 170.396666, :apply 1973.3906270000002, :settle 262.128417}} |
| `b7-lazy-definition` | effects | PASS | 489 | {:shown ["(42)"], :diagnostics []} |
| `c3-hook-spans` | join | PASS | 704 | {:observed [3 5 3 11], :relative [[2 5 2 11]]} |
| `corpus-join` | observer | PASS | 693 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `world-members-agree` | world | PASS | 924 | {:worlds {:agent-turn {:db-is-connections true, :ctx-custody-is-connection true, :writer-takes-connection true, :landed true, :own-branch? true, :elsewhere f... |
| `v2-4-schema-reflush` | schema | PASS | 473 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.395, :evaluate 74.02716699999999, :derive 17.247459, :apply 141.642292, ... |
| `c5-constructor-absence` | world | PASS | 273 | {:errors [[] [] []]} |
| `speculative-batch-keeps-committed-projection` | schema | PASS | 4048 | {:ruling-b-ms {:before 28, :after 28}, :head-path-ms {:before 10, :after 6}} |
| `oq1-storage-failure` | owner | PASS | 348 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `world-survives-compute-hop` | world | PASS | 870 | {:here #:seon.agent{:id "root"}, :bare {:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema... |
| `v2-6-tag-once` | input | PASS | 203 | {:reader-calls 1} |
| `b6-jvm-throwing-print-and-error` | entrant | PASS | 66 | {:shown ["#object[one-path-proof.host/eval985036/reify--985037]" "#:seon.error{:exception-class clojure.lang.ExceptionInfo,\n :message \"jvm boom\"}"], :diag... |
| `time-one-defn` | timing | PASS | 3603 | {:ms [1424 340 259 254 255], :timings {:read 0.127375, :evaluate 2.093, :derive 9.283917, :apply 102.779, :settle 137.517375}, :batch-retained-mb -0.72} |
| `r6-e1-opening-reads-the-inbox` | parity | PASS | 383 | {:inbox-reads ["(my.message/inbox {:seon.agent/id \"root\"})"], :walk-duplicates 1, :opening-forms 10} |
| `supply-from-the-world` | supply | PASS | 488 | {:lacking "Cannot call user/target: :seon.db/connection is unavailable. This evaluation's world holds no :seon.db/connection; pass it explicitly.", :walk (:s... |
| `agent-starts-in-another-namespace` | effects | PASS | 707 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 192 | {:count 52, :designed [6], :ms 32, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `contract-warning` | owner | PASS | 1438 | {:note "user/nc1 has no :malli/schema; specify it before proposing a merge.", :warning [user/nc2 user/nc3 user/nc1]} |
| `b5-contracts-gate` | landing | PASS | 1720 | {:ms 862, :findings 0, :each []} |
| `r8-render-read-only` | entrant | PASS | 373 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 3935 | {:ms [305 250 254], :timings {:read 0.112292, :evaluate 1.923917, :derive 8.544125, :apply 100.106625, :settle 139.432792}, :cause "seon.db/nearest-base: the... |
| `c3-generated-full-span` | join | PASS | 0 | {:short #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}, :exact :matched} |
| `c3-producer-spans` | join | PASS | 739 | {:origins #:user{spanned {:line 1, :column 1, :end-line 1, :end-column 16}, indented {:line 2, :column 3, :end-line 2, :end-column 24, :expanded-by defn}}} |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 253 | {:diagnostics ["show: stage failed" "show: stage failed" "conclude: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: ... |
| `attribute-change-equivalence` | schema | PASS | 1069 | {:results [true true true]} |
| `hop-plain-executor` | world | PASS | 570 | {:values ["#'user/hopped" "7"]} |
| `c5-error-print-world` | world | PASS | 120 | {:world-at-print [true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `c2-watch-rebinding-origin` | sci-fork | **FAIL** | 722 | {:source "(def watched 2)", :private {}} |
| `b3-malformed-tag-read-once` | reader | PASS | 0 | {:reader-calls 1, :reason "bad tag"} |
| `v2-6-repair-before-require` | input | PASS | 108 | {:values [nil 3 :clojure.string/k]} |
| `b1-value-equal-to-sentinel` | totality | PASS | 0 | {:diagnostics [["observe: review injected failure"] ["observe: review injected failure"] ["observe: review injected failure"]]} |
| `gate-runs-tests-in-their-namespace` | gate | PASS | 1533 | {:gate #:seon.gate{:sym gjf.core/f, :blocks? false, :tests 1}, :refusal {:seon.gate/sym gjf.core/f, :seon.gate/blocks? true, :seon.gate/tests 1, :seon.gate/n... |
| `b1-malformed-observation` | totality | PASS | 0 | {:diagnostics ["observe: 2 observed writes are not writes and were dropped"]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 624 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `gate-judges-the-change` | gate | PASS | 1509 | {:still-red-gate #:seon.gate{:sym user/gj, :blocks? false, :tests 2, :still-red [#:seon.test{:pass-count 0, :fail-count 1, :error-count 0, :sym user/gj-red}]... |
| `parity-faults-in-both-replies` | parity | PASS | 1185 | {:mcp-faults 1, :agent-faults 1, :diagnostics ["show: [{:seon.error/at #inst \"2026-09-28T00:01:36.902Z\",\n  :seon.error/data\n    #:seon.render.unknown{:ca... |
| `test-agent` | entrant | PASS | 1518 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 807 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `turn-overhead` | timing | **FAIL** | 2406 | {:kept 29, :kept-plan-chars [4536371 15640 1533 991 652 618 608 608 608 608 608 278], :remaining {:largest-plan "seon.error/for-agent's read stores one whole... |
| `b2-generated-definition-hook` | join | PASS | 8 | {:made [{:name made, :row 2, :col 1, :end-row 2, :end-col 9}]} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `b3-repair-reads-tag-once` | reader | PASS | 1 | {:reader-calls 1, :result {:forms ["[#review/tag 1]"], :text "[#review/tag 1]", :repairs [#:seon.batch{:repair-kind :delimiters, :at 0}]}} |
| `v2-4-register-then-use` | schema | PASS | 968 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `c1-malformed-map-observation` | totality | PASS | 0 | {:diagnostics ["observe: 1 observed writes are not writes and were dropped"]} |
| `captured-locals` | observer | PASS | 537 | {:private #:user{capture-f :captures-locals}} |
| `jvm-gets-feedback` | entrant | PASS | 244 | {:feedback #:seon.feedback{:changed []}} |
| `corpus` | corpus | PASS | 54878 | {:same 84, :designed 10, :facts-errors [["h32-multi-arity" ["The install gate refused user/h32-f: its own contract check failed"]]], :entities 86, :differ []... |
| `oq1-bad-form-logged` | owner | PASS | 628 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `c5-jvm-custody` | entrant | PASS | 80 | {:values [#:seon.agent{:id "root"}]} |
| `extension-replay` | decision | PASS | 283 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `b4-moved-head-feedback` | effects | PASS | 1574 | {:errors ["Program retraction refused."], :feedback #:seon.feedback{:changed [user/review-new], :missing-contracts [user/review-new]}} |
| `v2-7-no-reverse-requires` | static | PASS | 1948 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 864 | {:read-only [false false false true]} |
| `sink-per-world` | world | PASS | 598 | {:a #:user{counted 1000}, :b #:user{counted 1500}, :jvm-wide 2500} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 3781, :old-gen-after-gc-mb 1799, :processors 18}, :after {:heap-used-mb 4405, :old-gen-after-gc-mb 1747, :processors 18}} |
| `time-acquisition` | timing | PASS | 12790 | {:entities 100, :ms 11, :us-per-entity 110} |
| `blob-get-without-connection` | supply | PASS | 364 | {:values ["the whole value"], :digest "c1a9c9c4cd5488af28d93e7d185c77f4177c84955ba89bc0cb60613f266d0373"} |
| `s3-schema-rehearsal` | landing | **FAIL** | 2536 | {:errors [], :stored 61, :facts-errors [], :keys 60, :notes [{:seon.schema/key :seon.batch/scope, :seon.schema/installed? false, :seon.error/message "A store... |
| `q5-def-edges` | owner | PASS | 684 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 159 | {:shown ["()" "42"], :diagnostics ["show: [#:seon.error{:at #inst \"2026-09-28T00:01:54.131Z\",\n  :layer :seon.render/invocation,\n  :operation seon.sci.eva... |
| `v2-5-remove-rebind` | effects | PASS | 508 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 507 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `b1-nil-print-failure` | totality | PASS | 1 | {:entries [#:seon.batch{:ordinal 0, :ns-before user, :value nil, :source "nil", :line 1, :shown "#<unprintable nil>", :column 1, :ns-after user}], :diagnosti... |
| `s3-population-admits` | landing | PASS | 1529 | {:head {:refused "Schema population refused :seon.cache/cache (dishonest-generator)."}, :with-batch {:refused "Schema population refused :seon.cache/cache (d... |
| `mcp-equals-agent-turn` | entrant | PASS | 11109 | {:behaviors 9, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 270 | {:facts-errors []} |
| `entrant-gate` | entrant | PASS | 915 | {:facts-errors ["The install gate refused user/gt1: its own contract check failed"]} |
| `v2-5-remove-ns` | effects | PASS | 831 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `b6-jvm-floor` | entrant | PASS | 553 | {:jvm-shown-chars 3192, :sci-shown-chars 3192, :jvm-tail "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx... |
| `v2-8-jvm-recorded` | entrant | PASS | 90 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 1370 | {} |
| `sci-suite` | sci-fork | PASS | 0 | {:patched {:tests 398, :assertions 1521, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v2-3-ns-name-shadow` | totality | PASS | 650 | {:values ["42" "#'user/ns-name" "43"]} |
| `branch-entrance` | parity | PASS | 540 | {:created {:seon.store/branch :op-branch-224843, :seon.cluster/created? true, :seon.db/basis-t 536885644}, :refusals ["The branch already exists; retire it o... |
| `supply-reads-the-working-value` | supply | PASS | 594 | {:seen [[true false] [true true]]} |
| `r6-shown-by-the-one-print-owner` | print | PASS | 78 | {:shown ["{:seon.error/exception-class clojure.lang.ExceptionInfo,\n :seon.sci.eval/stacktrace [[user 1 1]],\n :seon.error/message \"owner\"}" "(+ 1 2)" "{:a... |
| `q4-lone-key` | owner | PASS | 771 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `c6-nested-world-sink` | world | PASS | 46 | {:a #:review{f 2}, :b #:review{f 1}} |
| `b4-writer-refusal-reply` | totality | PASS | 400 | {:reply #:seon.batch{:commit #:seon.batch{:writer-refusal #:seon.error{:message "review writer refusal"}, :first-refusal "review writer refusal"}, :diagnosti... |
| `v2-3-observer-fails` | totality | PASS | 142 | {:diagnostics ["observe: stop failed"]} |
| `feedback-hub-fresh` | timing | PASS | 1788 | {:bounded-text {:fresh-ms 34, :tests 10, :counts {:never-run 10}, :armed ["33 ms in seon.db/with-declarations x80" "23 ms in seon.db/q x5" "22 ms in seon.tes... |
| `v2-8-nested-render-one-tx` | entrant | PASS | 267 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 276 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 1099 | {:branches [":agent-cbe1a6a94441" ":agent-3574aaf6c933"]} |
| `supply-per-world` | supply | PASS | 732 | {} |
| `b2-exact-span-no-fallback` | join | PASS | 0 | {:join #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}} |
| `parity-render-refusal-never-parks` | parity | PASS | 1198 | {:mcp {:applied? true, :stored? true, :values 2, :shown "{:seon.render/ai no.such/renderer, :x 1}"}, :agent {:applied? true, :stored? true, :values 2, :shown... |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `gate-callee-contract-is-feedback` | gate | PASS | 1164 | {:findings [#:seon.gate{:contract user/cal2, :arguments [-1]}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `c4-deferred-gates` | gate | PASS | 2125 | {:refusal "The install gate refused user/deferred-a, user/deferred-b", :stored-error "facts not proposed: The install gate refused user/deferred-a, user/defe... |
| `oq1-head-moved` | owner | PASS | 1511 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `r6-unresolved-names-kept-as-values` | effects | PASS | 846 | {:calls #{clojure.core/defn clojure.set/union}, :requires #{seon.bootstrap clojure.test clojure.set seon.db my.turn my.message}} |
| `worlds-never-cross` | world | PASS | 1238 | {:sources ["(def op3-cross :a)" "(def op3-cross :b)"]} |
| `v2-3-span-invalid` | totality | PASS | 209 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `c3-generated-stored-and-reacquired` | join | **FAIL** | 886 | {:sources ["(defmacro pair [] (list 'do (list 'def 'pa 1) (list 'def 'pb 2)))" nil nil], :private #:user{pa :no-definition-at-origin, pb :no-definition-at-or... |
| `feedback-from-facts` | owner | PASS | 1240 | {:feedback #:seon.feedback{:changed [user/fb-f user/fb-g], :missing-contracts [user/fb-f], :tests #:user{fb-t :never-run}, :counts {:never-run 1}}, :contract... |
| `q3-threaded-facts` | owner | PASS | 351 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `b7-concurrent-observation` | effects | PASS | 7 | {:observed 8000, :late "The evaluation that owned this observation has completed."} |
| `time-chained-40` | timing | PASS | 12906 | {:timings {:read 10.416919000000004, :evaluate 1936.6081660000004, :derive 1317.8156249999995, :apply 5288.542541999999, :settle 476.802917}, :retained-forms... |

106/111 proofs pass
