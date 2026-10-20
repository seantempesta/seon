(ns my.root
  "Root's verbs on tasks (lanes R1 and M1's proto.agents.root): write desired state on main;
  the reconciler proc does the rest. Each takes root's system (seon.root/start!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.root :as root] [seon.task :as task]))

(defn create!
  "Write task declarations as held tasks. Answers the writer's answer, or the refusal."
  {:malli/schema [:=> [:cat :seon.root/sys [:vector :seon.task/declaration]] :map]}
  [sys decls]
  (let [tx (task/create-tx @(:conn sys) decls)]
    (if (:seon.error/type tx) tx @(root/transact-main! sys tx))))

(defn run!
  "Release tasks ids: the one release (nothing autoruns)."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :running)))

(defn cancel!
  "Cancel tasks ids: a live attempt is killed; tasks that need them stay blocked."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :cancelled)))
