(ns seon.sci.batch
  "The SCI stage values of the one REPL path: the live read stage behind the
  reader stepper (`seon.sci.forgive`), the current namespace, and the batch's namespace
  binding. Evaluation is `seon.sci.eval/evaluate`.

  Arming and the write observer are `seon.sci.arm` and `seon.sci.observe`.
  This namespace requires no evaluator owner, so `seon.sci.eval` builds its
  acquisition entrant from it."
  (:require [sci.core :as sci]
            [sci.impl.parser :as parser]
            [seon.sci.forgive :as forgive]))

(def parse-options
  "The live reader's options: SCI's own reader policy (tags, records,
  `*data-readers*`, `#=`) with the `:clj` features and end locations, so every
  form carries its complete span, no eof."
  {:features #{:clj} :end-location true})

(defn read-stage
  "The live read stage for `ctx`: SCI's `parse-next` under its own reader policy."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.batch/read-stage]}
  [ctx]
  {:seon.batch/read (fn [rdr eof] (sci/parse-next ctx rdr (assoc parse-options :eof eof)))})

(defn read-next
  "One read step at the live cursor, through the reader stage's stepper."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/read-state] :seon.batch/read-step]}
  [{{ctx :seon.sci.eval/ctx} :seon.batch/world mode :seon.batch/mode} state]
  (forgive/next-form (read-stage ctx) mode state))

(defn current-ns
  "The namespace the batch is in now."
  {:malli/schema [:=> [:cat :seon.batch/request] :symbol]}
  [_request]
  (sci/ns-name @sci/ns))

(defn around
  "The batch runs in its start namespace, with the data readers bound for the pass as `sci/eval-string*` binds them."
  {:malli/schema [:=> [:cat :seon.batch/request [:=> [:cat] :seon.batch/batch]] :seon.batch/batch]}
  [{start-ns :seon.batch/start-ns} body]
  (sci/with-bindings {sci/ns (sci/create-ns start-ns)
                      parser/data-readers @parser/data-readers}
    (body)))
