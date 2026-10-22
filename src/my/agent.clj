(ns my.agent
  "An agent's own functions (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                the live value of an earlier result (also result/h3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (send-message tos body)   body to the agents tos (ids), delivered by root
    (callers 'ns/f)           the program vars whose recorded definition names ns/f
    (callees 'ns/f)           the vars ns/f's recorded definition names
    (grep "text")             the program vars whose recorded definition's text holds it
    (prove)                   run what judges this branch: the task's proofs and the tests reaching
                              what it changed since main, each green one at its digest reused
    (help)                    the help page's sections (docs/agent.md); (help "done") one whole
    clojure.repl as at any Clojure REPL (source, doc, dir, apropos, find-doc, pst), with *1 *2 *3 *e:
    source reads this branch's own definitions first, then a library's file"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [seon.context :as context] [seon.ref :as ref] [seon.docs :as docs]
            [seon.config :as cfg] [seon.proofs :as proofs] [seon.task :as task]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.main] [datahike.api :as d]))

(defn $ "The live value of an earlier result, by handle (:h3)." {:malli/schema [:=> [:cat :keyword] :any]}
  [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." {:malli/schema [:=> [:cat] [:map [:id :seon.agent/id] [:branch :seon.db/branch] [:pid :seon.lib/pid]]]}
  [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The messages delivered to this agent and not yet taken ({:from :body}), taken." {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (agent/messages))
(defn send-message "Send body to the agents tos (ids); root delivers it by id. nil." {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (agent/send-message tos body))

(defn callers
  "The program vars whose recorded definition names var sym (its :form/refs on this branch),
  sorted; sym itself left out."
  {:malli/schema [:=> [:cat :qualified-symbol] [:vector :qualified-symbol]]}
  [sym]
  (vec (sort (disj (set (d/q '[:find [?v ...] :in $ ?s :where [?f :form/refs ?s] [?f :form/defines ?e] [?e :var/name ?v]]
                             (agent/db) sym))
                   sym))))

(defn callees
  "The vars var sym's recorded definition names on this branch (its :form/refs: program,
  library and clojure.core alike), sorted; sym itself left out."
  {:malli/schema [:=> [:cat :qualified-symbol] [:vector :qualified-symbol]]}
  [sym]
  (vec (sort (disj (set (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]]
                             (agent/db) sym))
                   sym))))

(defn prove
  "Run what judges this branch, as root's judge and the turn loop run it (seon.agent/run-tests-forked,
  in a fork of this agent): the task's proofs (or the given ones) and every test reaching what the
  branch changed since the main it forked from, each test green at its reach digest reused, not
  run. Answers {:selected n :ran n :reused n :ms n :results {test :pass|:fail|:error} :red [{:test
  :type :message :expected :actual}]}."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:set :qualified-symbol]] :map]]}
  ([] (prove (or (some->> (:task @agent/state) (task/task (agent/db)) :seon.task/accumulated :proofs) #{})))
  ([proofs]
   (let [run (agent/run-tests-forked (cfg/value :seon.config.agent/test-ticks) proofs)
         results (proofs/results run)]
     {:selected (count results) :ran (:ran run 0) :reused (:reused run 0) :ms (:ms run)
      :results results :red (proofs/red-reports run (set (keys results)))})))

(defn grep
  "The program vars whose recorded definition's text (:form/src on this branch) holds the
  string s, sorted: a text search over the program as the branch holds it."
  {:malli/schema [:=> [:cat :string] [:vector :qualified-symbol]]}
  [s]
  (vec (sort (d/q '[:find [?n ...] :in $ ?s :where [?f :form/src ?src] [(clojure.string/includes? ?src ?s)]
                    [?f :form/defines ?v] [?v :var/name ?n]]
                  (agent/db) s))))

(defn- file-source
  "The text of the form at var v's :file and :line on the classpath (Clojure's clojure.repl/source-fn
  reads it the same way: a library's vars carry both), or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [v]
  (let [{:keys [file line]} (meta v)]
    (when-let [r (and file line (io/resource file))]
      (let [text (slurp r)
            off (reduce (fn [i _] (inc (str/index-of text "\n" i))) 0 (range (dec line)))]
        (second (read+string {:read-cond :allow} (java.io.PushbackReader. (java.io.StringReader. (subs text off)))))))))

(defn source-fn
  "The source of the var sym names, or nil: clojure.repl/source-fn. The text its last definition
  in this process kept (seon.repl), else what this branch records for it, else the form at its
  :file and :line (a library's)."
  {:malli/schema [:=> [:cat :symbol] [:maybe :string]]}
  [sym]
  (when-let [v (resolve sym)]
    (or (:source (meta v))
        (context/source-of (agent/db) (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v)))))
        (file-source v))))

(defn pst
  "Print the exception e (*e by default) as clojure.repl/pst does: its message, then each cause's
  class and message."
  {:malli/schema [:function [:=> [:cat] :nil] [:=> [:cat :any] :nil]]}
  ([] (pst *e))
  ([e] (when (instance? Throwable e)
         (print (clojure.main/err->msg e))
         (doseq [{:keys [type message]} (rest (:via (Throwable->map e)))]
           (println "Caused by:" type message)))))

(defmacro source
  "Print the recorded source of the definition named by sym (as clojure.repl/source does)."
  [sym] `(println (or (source-fn '~sym) (str "Source not found for " '~sym))))

(defn help
  "The help page every agent reads first (seon.context/help-page), as this branch holds it
  (seon.docs/page): its sections, each its address, heading and lead's first sentence; with
  an anchor, that section's own text."
  {:malli/schema [:function [:=> [:cat] [:vector :map]] [:=> [:cat :string] [:maybe :string]]]}
  ([] (mapv (fn [{:keys [address heading lead]}] (cond-> {:address address :heading heading} lead (assoc :lead (ref/lead-sentence lead))))
            (docs/page (agent/db) context/help-page)))
  ([anchor] (:seon.ref/text (docs/view (agent/db) (str context/help-page "#" anchor) :code))))
