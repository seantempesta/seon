(ns seon.record-cost-test
  "A protocol call on a record costs what its method reads, not the record's width: Datahike's DB has 21 fields, and
  its add path makes ~30 protocol calls on it per datom."
  (:require [clojure.test :refer [deftest]] [seon.cost :as cost]))

(defprotocol Wide
  (wide-first [r] "the record's first field"))

(defn wide-record
  "A record of n fields, f0 .. f(n-1), defined at run time, whose Wide method reads only
  f0; as call-wide's argument vector."
  [n]
  (let [fields (mapv #(symbol (str "f" %)) (range n))
        nm (symbol (str "Wide" n))]
    (binding [*ns* (the-ns 'seon.record-cost-test)]
      (eval (list 'defrecord nm fields 'seon.record-cost-test/Wide (list 'wide-first ['_] (first fields)))))
    [(apply (resolve (symbol "seon.record-cost-test" (str "->" nm))) (range n))]))

(defn call-wide
  "One protocol call on r."
  [r]
  (wide-first r))

(deftest a-protocol-call-costs-what-its-method-reads
  ;; the work, whatever the width: call-wide's call and the method's, and the dispatch, one
  ;; lookup in the record's descriptor table (priced as a hash map get); the field read is a
  ;; slot. A call allocates 96 B at every width (its frame; a hash map get prices none), stated.
  ;; Red while a method entry reads every field (~6 ticks a field): 27 ticks at 2 fields
  ;; and 153 at 20, 7x over
  (cost/proof
    {:of 'seon.record-cost-test/call-wide :input 'seon.record-cost-test/wide-record
     :n "fields of the record"
     :work "one protocol dispatch and one field read, whatever the record's width"
     :growth :constant
     :ops {:fn/call 2 :map/get 1}
     :per-n {:bytes 96}
     :sizes [2 20 200]}))
