user=> (ns scratch.add2)
#:seon.repl{:value nil, :result result/e069dc7633048, :ns scratch.add2, :ms 0}

scratch.add2=> (defn add-one "Returns x plus one." {:malli/schema [:=> [:cat :int] :int]} [x] x)
#:seon.repl{:value {:seon.render.var/symbol scratch.add2/add-one}, :result result/e005594853c52, :ms 2, :calls 225}

scratch.add2=> (deftest ^{:seon/tasks #{'scratch.add2/add-one-returns-its-argument-plus-one}} add-one-adds-one
  (is (= 2 (add-one 1)))
  (is (= 0 (add-one -1))))
#:seon.repl{:value {:seon.render.var/symbol scratch.add2/add-one-adds-one}, :result result/e51734e7e2d86, :ms 1, :calls 8}

scratch.add2=> (seon.agent/settings! {:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 1})
#:seon.repl{:value {:my.agent/settings {},
 :seon.render/operations
   [(seon.agent/effective-settings (seon.db/db) "lp-add-2")]}, :result result/e840d568180db, :ms 78, :calls 721}

scratch.add2=> (seon.agent/settings! {:seon.config.run/max-episode-runs 4})
#:seon.repl{:value {:my.agent/settings
   {:seon.config.ai/no-provider false,
    :seon.config.run/max-episode-runs 1},
 :seon.render/operations
   [(seon.agent/effective-settings (seon.db/db) "lp-add-2")]}, :result result/ecbc1e69da779, :ms 40, :calls 553}

;; changed since your last turn
user=> (seon.db/pull (quote [:seon.agent/id #:seon.agent{:namespace [:seon.ns/name #:seon.ns{:steward [:seon.agent/id]}]} :seon.agent/cluster]) [:seon.agent/id "lp-add-2"])
#:seon.repl{:value {:seon.agent/id "lp-add-2",
 :seon.agent/cluster "default",
 :seon.agent/namespace
   #:seon.ns{:name user, :steward {:seon.agent/id "lp-add-2"}},
 :seon.render/operations [(help)]}, :result result/e33a220596626}

;; changed since your last turn
user=> (my.task/steps {})
#:seon.repl{:value {:seon.task/id "24d8acc49173",
 :seon.task/steps [],
 :seon.task/name "add-one-returns-its-argument-plus-one",
 :seon.task/intent
   "Make `scratch.add2/add-one` return its argument plus one. The proof test `scratch.add2/add-one-adds-one` is red until it does.",
 :seon.render/operations [(my.task/step! {:seon.step/text "Next step"})]}, :result result/eec989edadf44}

;; changed since your last turn
user=> (get (seon.db/pull (quote [:seon.message/_to]) [:seon.agent/id "lp-add-2"]) :seon.message/_to)
#:seon.repl{:value [{:my.message/id "8140c376edeb",
  :my.message/from "root",
  :seon.message/lines
    ["Work on your task: make scratch.add2/add-one return its argument plus one; the proof test is scratch.add2/add-one-adds-one."],
  :seon.render/operations [(my.message/received! "8140c376edeb")]}], :result result/e8a60b4e1cb66}

;; changed since your last turn
user=> (seon.agent/settings (seon.db/db) "lp-add-2")
#:seon.repl{:value {:my.agent/settings
   {:seon.config.ai/no-provider false,
    :seon.config.run/max-episode-runs 4},
 :seon.render/operations
   [(seon.agent/effective-settings (seon.db/db) "lp-add-2")]}, :result result/e65f2e1957621}

;; changed since your last turn
user=> (dir (quote user))
#:seon.repl{:value {:seon.ns/name user,
 :seon.ns/requires [],
 :seon.repl/rows [],
 :seon.repl/columns [:sym :arglists :doc :in :out]}, :result result/e1b75919ef0f5}

;; changed since your last turn
user=> (seon.db/pull (quote [*]) [:seon.runtime/agent {:seon.agent/id "lp-add-2", :db/id 125860}])
#:seon.repl{:value {:seon.render/operations
   [(seon.db/pull
     '[{:seon.turn/_agent [:seon.turn/id]}]
     [:seon.agent/id "lp-add-2"])]}, :result result/e166307f631e0}

;; changed since your last turn
user=> (seon.error/for-agent (seon.db/db) "lp-add-2")
#:seon.repl{:value [], :result result/e9b6e25ea4a98, :calls 6188}

turns left: 2 of 4