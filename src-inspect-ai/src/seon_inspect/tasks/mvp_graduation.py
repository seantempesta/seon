"""Offline-first task definitions for the context MVP graduation eval.

The model sees only the user goals in :data:`TASKS`.  Everything else in this
module is harness plumbing evaluated through the fresh cluster's advertised
io-prepl.  In particular, schema fixture rows are published through the same
guarded evaluation plus terminal-receipt path used by an agent; the seed never
installs a Datahike schema declaration directly.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Sequence

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.solver import Generate, TaskState, solver


TASKS: dict[str, tuple[str, ...]] = {
    "A": (
        "The expense records in front of you are unreadable. I want each one "
        "to show up as a single line with its dollar amount and its vendor, "
        "whenever you look at your world.",
    ),
    "B": (
        "Which schema keys does the messaging toolkit's public surface mention?",
    ),
    "B-control": (
        "Which schema keys does the empty messaging toolkit's public surface mention?",
    ),
    "C": (
        "Let the bookkeeping agent know what the expenses add up to.",
    ),
    "D": (
        "Work out what we spent per vendor and keep it somewhere you will "
        "still have it tomorrow.",
        "What did we spend at the vendor with the largest total?",
    ),
    "E1": (
        "I need the exact number of primes below one trillion. Give me the number.",
    ),
    "E2": (
        "Addition is the slow part of your expense totals. Make addition "
        "faster everywhere, then give me the total.",
    ),
    "F": (
        "Take over the expense book: make it readable when you look at it, "
        "tell the bookkeeping agent the total, and tell me which schema keys "
        "the messaging toolkit mentions.",
    ),
}

BUDGETS: dict[str, tuple[int, ...]] = {
    "A": (6,),
    "B": (4,),
    "B-control": (4,),
    "C": (5,),
    "D": (5, 4),
    "E1": (5,),
    "E2": (5,),
    "F": (10,),
}

# The public six-family selection expands the two mandatory paired arms.
SCENARIO_SAMPLES: dict[str, tuple[str, ...]] = {
    "A": ("A",),
    "B": ("B", "B-control"),
    "C": ("C",),
    "D": ("D",),
    "E": ("E1", "E2"),
    "F": ("F",),
}

PAID_ARMS = frozenset({"deepseek", "paid"})
DEFAULT_AGENT_ID = "graduation"
PEER_AGENT_ID = "bookkeeping"
LOCAL_ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
LOCAL_MODEL = "qwen3.5:35b-a3b-coding-nvfp4"
LOCAL_CONFIG_MANIFEST = """{:seon.config.ai/endpoint "http://127.0.0.1:11434/v1/chat/completions"
 :seon.config.ai/model "qwen3.5:35b-a3b-coding-nvfp4"
 :seon.config.ai/max-tokens 8192
 :seon.config.ai/api-key-variable :seon.config/absent
 :seon.config.ai/no-auth true
 :seon.config.ai/timeout-ms 300000}"""


@dataclass(frozen=True)
class SeedPlan:
    """One reproducible fixture and its independently checkable expectations."""

    scenario: str
    seed: int
    sample_id: str
    nonce: str
    cluster_name: str
    agent_id: str
    peer_agent_id: str
    expense_attributes: Mapping[str, str]
    expenses: tuple[Mapping[str, Any], ...]
    toolkit_namespace: str
    empty_toolkit_namespace: str
    mentioned_schema_key: str
    expectations: Mapping[str, Any]
    form: str


class VoidedSample(RuntimeError):
    """Harness evidence disagreed before scoring, so no score may be emitted."""


def sample_nonce(seed: int, sample_id: str) -> str:
    """A fresh, replayable nonce derived only from ``(seed, sample_id)``."""

    material = f"mvp-graduation\0{int(seed)}\0{sample_id}".encode()
    return hashlib.sha256(material).hexdigest()[:16]


def _digest_int(seed: int, sample_id: str, index: int) -> int:
    material = f"expense\0{seed}\0{sample_id}\0{index}".encode()
    return int.from_bytes(hashlib.sha256(material).digest()[:8], "big")


def _expense_rows(seed: int, sample_id: str, nonce: str) -> tuple[dict, ...]:
    # The nonce is part of the fact the answer depends on.  A memorized total
    # or vendor name therefore cannot satisfy any fixture-bound check.
    vendors = tuple(f"{name}-{nonce}" for name in
                    ("Acorn Office", "Bluebird Market", "Cedar Transit"))
    rows = []
    for index in range(6):
        value = _digest_int(seed, sample_id, index)
        rows.append({
            "id": f"expense-{nonce}-{index}",
            "vendor": vendors[value % len(vendors)],
            "amount_cents": 175 + ((value // len(vendors)) % 18_000),
        })
    return tuple(rows)


def _expectations(expenses: Sequence[Mapping[str, Any]], schema_key: str,
                  toolkit_namespace: str, empty_namespace: str,
                  peer_agent_id: str, nonce: str) -> dict[str, Any]:
    totals: dict[str, int] = {}
    for row in expenses:
        vendor = str(row["vendor"])
        totals[vendor] = totals.get(vendor, 0) + int(row["amount_cents"])
    max_vendor, max_total = sorted(totals.items(), key=lambda item: (-item[1], item[0]))[0]
    return {
        "nonce": nonce,
        "expense_count": len(expenses),
        "expense_sum_cents": sum(int(row["amount_cents"]) for row in expenses),
        "vendor_totals_cents": totals,
        "max_vendor": max_vendor,
        "max_vendor_total_cents": max_total,
        "mentioned_schema_keys": [schema_key],
        "control_schema_keys": [],
        "toolkit_namespace": toolkit_namespace,
        "empty_toolkit_namespace": empty_namespace,
        "peer_agent_id": peer_agent_id,
    }


def _clj_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def _clj_keyword(value: str) -> str:
    if not value.startswith(":"):
        raise ValueError(f"not a keyword: {value!r}")
    return value


def _clj_data(value: Any) -> str:
    if value is None:
        return "nil"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return _clj_string(value)
    if isinstance(value, Mapping):
        items = []
        for key, child in value.items():
            rendered_key = (_clj_keyword(key) if isinstance(key, str) and key.startswith(":")
                            else _clj_string(str(key)))
            items.append(f"{rendered_key} {_clj_data(child)}")
        return "{" + " ".join(items) + "}"
    if isinstance(value, (tuple, list)):
        return "[" + " ".join(_clj_data(child) for child in value) + "]"
    raise TypeError(f"cannot encode Clojure data: {value!r}")


def _schema_source(schema_key: str, definition: str) -> str:
    return f"(seon.schema/register! {_clj_keyword(schema_key)} {definition})"


def _function_source(function_name: str, schema_key: str) -> str:
    return (
        f"(defn ^{{:malli/schema [:=> [:cat {_clj_keyword(schema_key)}] "
        f"{_clj_keyword(schema_key)}]}} {function_name} [days] days)"
    )


def _seed_form(*, cluster_name: str, agent_id: str, peer_agent_id: str,
               nonce: str, attributes: Mapping[str, str],
               expenses: Sequence[Mapping[str, Any]], toolkit_namespace: str,
               empty_namespace: str, schema_key: str) -> str:
    """Build one io-prepl form using runtime declaration admission.

    ``register-one!`` intentionally executes the declaration through
    ``seon.sci.eval/evaluate`` and commits its reader-produced program row via
    ``seon.cluster.loop/terminal-tx``.  That terminal transition derives and
    installs Datahike attributes; this form never constructs ``:db/ident``
    declarations itself.
    """

    fixture_owner = f"fixture-{nonce}"
    toolkit_owner = f"toolkit-{nonce}"
    empty_owner = f"empty-toolkit-{nonce}"
    registrations = [
        (fixture_owner, f"my.fixtures.{nonce}",
         _schema_source(attributes["id"], "[:string {:seon.db/identity true}]") ),
        (fixture_owner, f"my.fixtures.{nonce}",
         _schema_source(attributes["amount_cents"], ":int")),
        (fixture_owner, f"my.fixtures.{nonce}",
         _schema_source(attributes["vendor"], "[:string {:min 1}]")),
        (fixture_owner, f"my.fixtures.{nonce}",
         _schema_source(attributes["owner"],
                        "[:and {:seon.db/index true} :seon.db/ref]")),
        (fixture_owner, f"my.fixtures.{nonce}",
         _schema_source(
             attributes["entity"],
             "[:map {:seon.db/entity true} "
             f"[{attributes['id']} {attributes['id']}] "
             f"[{attributes['amount_cents']} {attributes['amount_cents']}] "
             f"[{attributes['vendor']} {attributes['vendor']}] "
             f"[{attributes['owner']} {attributes['owner']}]]")),
        (toolkit_owner, toolkit_namespace,
         _schema_source(schema_key, ":int")),
        (toolkit_owner, toolkit_namespace,
         _function_source(f"retention-days-{nonce}", schema_key)),
    ]
    registration_data = _clj_data([
        {":owner": owner, ":namespace": namespace, ":source": source}
        for owner, namespace, source in registrations
    ])
    expense_data = _clj_data([
        {attributes["id"]: row["id"],
         attributes["amount_cents"]: row["amount_cents"],
         attributes["vendor"]: row["vendor"],
         attributes["owner"]: [":seon.cluster.agent/id", agent_id]}
        for row in expenses
    ]).replace(_clj_string(":seon.cluster.agent/id"),
               ":seon.cluster.agent/id")
    return f"""
(let [cluster-name {_clj_string(cluster_name)}
      instance (get @@#'seon.cluster/running-instances cluster-name)
      connection (:seon.boot/cluster-connection instance)
      process (seon.cluster/process-identity (:seon.boot/advertisement instance))
      now (java.util.Date.)
      ensure! (fn [agent-id namespace-name]
                (seon.cluster/ensure-entity!
                 connection process
                 {{:seon.cluster.agent/id agent-id
                   :seon.cluster/name cluster-name
                   :seon.ns/name (symbol namespace-name)}}))
      register-one!
      (fn [ordinal {{:keys [owner namespace source]}}]
        (let [run-id (str "mvp-seed-{nonce}-" ordinal)
              tx-meta {{:seon.db/user [:seon.cluster.agent/id owner]
                       :seon.db/process [:seon.db.process/id process]}}
              _ (ensure! owner namespace)
              _ (datahike.api/transact
                 connection
                 {{:tx-data (seon.cluster.run/open-tx
                            {{:seon.cluster.run/id run-id
                              :seon.cluster.run/agent
                              [:seon.cluster.agent/id owner]
                              :seon.cluster.run/opened-at now}})
                   :tx-meta tx-meta}})
              _ (datahike.api/transact
                 connection
                 {{:tx-data (seon.cluster.run/claim-tx
                            {{:seon.cluster.run/id run-id
                              :seon.cluster.run/process process
                              :seon.cluster.run/live-processes #{{process}}
                              :seon.cluster.run/now now}})
                   :tx-meta tx-meta}})
              _ (datahike.api/transact
                 connection
                 {{:tx-data (seon.cluster.run/receipt-start-tx
                            {{:seon.cluster.run/id run-id
                              :seon.cluster.eval/ordinal 0
                              :seon.cluster.eval/at now}})
                   :tx-meta tx-meta}})
              ctx (seon.sci.eval/fork)
              _ (seon.sci.eval/acquire!
                 {{:seon.sci.eval/ctx ctx :seon.db/db @connection}})
              effective (seon.config/effective @connection cluster-name)
              evaluation
              (seon.sci.eval/evaluate
               {{:seon.cluster.run.form/source source
                 :seon.sci.eval/ctx ctx
                 :seon.cluster.agent/id owner
                 :seon.cluster.run.form/ns [:seon.ns/name (symbol namespace)]
                 :seon.sci.admit/caps (seon.config/result-caps effective)
                 :seon.sci.eval/time-limit-ms
                 (:seon.config.eval/time-limit-ms effective)
                 :seon.config/on-core-error
                 (:seon.config/on-core-error effective)}})
              receipt (merge
                       {{:seon.cluster.run/id run-id
                         :seon.cluster.run/process process
                         :seon.cluster.run.form/ordinal 0}}
                       (select-keys
                        evaluation
                        [:seon.cluster.eval/result-edn
                         :seon.cluster.eval/error
                         :seon.cluster.eval/interrupted-at
                         :seon.cluster.eval/output
                         :seon.cluster.eval/ns
                         :seon.sci.eval/program-row]))
              terminal (datahike.api/transact
                        connection
                        {{:tx-data (seon.cluster.loop/terminal-tx receipt now)
                          :tx-meta tx-meta}})]
          (when (:seon.error/kind (:seon.sci.admit/value evaluation))
            (throw (ex-info "MVP seed declaration evaluation failed"
                            {{:evaluation evaluation}})))
          (when (:seon.sci.eval/program-row evaluation)
            (seon.sci.eval/install-program-row!
             {{:seon.sci.eval/ctx ctx
               :seon.db/db (:db-after terminal)
               :seon.sci.eval/program-row
               (:seon.sci.eval/program-row evaluation)}}))
          (datahike.api/transact
           connection
           {{:tx-data (seon.cluster.run/close-tx
                      {{:seon.cluster.run/id run-id
                        :seon.cluster.run/process process
                        :seon.cluster.run/closed-at (java.util.Date.)}})
             :tx-meta tx-meta}})))
      registrations {registration_data}]
  (ensure! {_clj_string(agent_id)} "my.agents.{agent_id}")
  (ensure! {_clj_string(peer_agent_id)} "my.agents.{peer_agent_id}")
  (ensure! {_clj_string(empty_owner)} {_clj_string(empty_namespace)})
  (doseq [[ordinal registration] (map-indexed vector registrations)]
    (register-one! ordinal registration))
  (datahike.api/transact
   connection
   [[:db/add [:seon.cluster/name cluster-name]
     :seon.cluster/toolkit
     [:seon.ns/name (symbol {_clj_string(toolkit_namespace)})]]
    [:db/add [:seon.cluster/name cluster-name]
     :seon.cluster/toolkit
     [:seon.ns/name (symbol {_clj_string(empty_namespace)})]]])
  (datahike.api/transact connection {expense_data})
  (let [db @connection
        amount-attr {_clj_keyword(attributes['amount_cents'])}
        vendor-attr {_clj_keyword(attributes['vendor'])}
        id-attr {_clj_keyword(attributes['id'])}
        caps (seon.config/result-caps
              (seon.config/effective db cluster-name))
        sum-cents (datahike.api/q
                   '[:find (sum ?amount) .
                     :in $ ?amount-attr
                     :where [?expense ?amount-attr ?amount]]
                   db amount-attr)
        vendor-totals
        (into {{}}
              (datahike.api/q
               '[:find ?vendor (sum ?amount)
                 :in $ ?vendor-attr ?amount-attr
                 :where
                 [?expense ?vendor-attr ?vendor]
                 [?expense ?amount-attr ?amount]]
               db vendor-attr amount-attr))
        maximum (first (sort-by (fn [[vendor total]] [(- total) vendor])
                                vendor-totals))
        render-one
        (fn [expense-id]
          (let [path [id-attr expense-id]
                node (seon.render.walk/neighborhood
                      {{:seon.db/db db
                        :seon.render.walk/lookup path
                        :seon.render/kind :seon.render/ai
                        :seon.render/floor 'seon.render.block/data-prose
                        :seon.sci.admit/caps caps
                        :seon.render/distance 0}})]
            {{:expense-id expense-id
              :path path
              :rendered (or (:seon.render/output node)
                            (seon.render.walk/prose db node))}}))
        expense-ids (datahike.api/q
                     '[:find [?id ...]
                       :in $ ?id-attr
                       :where [?expense ?id-attr ?id]]
                     db id-attr)
        agent-eid (datahike.api/q
                   '[:find ?agent .
                     :in $ ?agent-id
                     :where [?agent :seon.cluster.agent/id ?agent-id]]
                   db {_clj_string(agent_id)})
        base-var (sci.core/resolve (seon.sci.eval/fork) 'clojure.core/+)
        base-state {{:sym "clojure.core/+"
                    :var-class (str (class base-var))
                    :root-class (str (class @base-var))
                    :identity (System/identityHashCode base-var)}}]
    {{:nonce {_clj_string(nonce)}
      :baseline
      {{:pre-walk (mapv render-one expense-ids)
        :agent-eid agent-eid
        :branch (:branch db)
        :commit-id (:datahike/commit-id db)
        :base-row-before base-state}}
      :derived-expectations
      {{:nonce {_clj_string(nonce)}
       :expense-count (count {expense_data})
       :expense-sum-cents sum-cents
       :vendor-totals-cents vendor-totals
       :max-vendor (first maximum)
       :max-vendor-total-cents (second maximum)
       :mentioned-schema-keys [(str {_clj_keyword(schema_key)})]
       :control-schema-keys []
       :toolkit-namespace {_clj_string(toolkit_namespace)}
       :empty-toolkit-namespace {_clj_string(empty_namespace)}
       :peer-agent-id {_clj_string(peer_agent_id)}}}}}))
""".strip()


def build_seed_plan(scenario: str, seed: int, sample_id: str, cluster_name: str,
                    *, agent_id: str = DEFAULT_AGENT_ID,
                    peer_agent_id: str = PEER_AGENT_ID) -> SeedPlan:
    if scenario not in TASKS:
        raise ValueError(f"unknown scenario {scenario!r}")
    nonce = sample_nonce(seed, sample_id)
    expenses = _expense_rows(seed, sample_id, nonce)
    attributes = {
        "id": f":my.expense/id-{nonce}",
        "amount_cents": f":my.expense/amount-cents-{nonce}",
        "vendor": f":my.expense/vendor-{nonce}",
        "owner": f":my.expense/owner-{nonce}",
        "entity": f":my.expense/expense-{nonce}",
    }
    toolkit_namespace = f"my.message.fixture-{nonce}"
    empty_namespace = f"my.message.empty-{nonce}"
    schema_key = f":my.archive/retention-days-{nonce}"
    expectations = _expectations(expenses, schema_key, toolkit_namespace,
                                 empty_namespace, peer_agent_id, nonce)
    form = _seed_form(
        cluster_name=cluster_name, agent_id=agent_id,
        peer_agent_id=peer_agent_id, nonce=nonce, attributes=attributes,
        expenses=expenses, toolkit_namespace=toolkit_namespace,
        empty_namespace=empty_namespace, schema_key=schema_key)
    return SeedPlan(
        scenario=scenario, seed=int(seed), sample_id=sample_id, nonce=nonce,
        cluster_name=cluster_name, agent_id=agent_id,
        peer_agent_id=peer_agent_id, expense_attributes=attributes,
        expenses=expenses, toolkit_namespace=toolkit_namespace,
        empty_toolkit_namespace=empty_namespace,
        mentioned_schema_key=schema_key, expectations=expectations, form=form)


def provider_readback_form(cluster_name: str) -> str:
    return f"""
(let [instance (get @@#'seon.cluster/running-instances {_clj_string(cluster_name)})
      connection (:seon.boot/cluster-connection instance)
      effective (seon.config/effective @connection {_clj_string(cluster_name)})]
  (select-keys effective
               [:seon.config.ai/endpoint :seon.config.ai/model
                :seon.config.ai/no-auth :seon.config.ai/api-key-variable]))
""".strip()


def _provider_matches(arm: str, provider: Mapping[str, Any]) -> bool:
    endpoint = str(provider.get("seon.config.ai/endpoint",
                                provider.get(":seon.config.ai/endpoint", "")))
    model = str(provider.get("seon.config.ai/model",
                             provider.get(":seon.config.ai/model", "")))
    no_auth = provider.get(
        "seon.config.ai/no-auth", provider.get(":seon.config.ai/no-auth"))
    if arm == "local":
        return (endpoint == LOCAL_ENDPOINT
                and model == LOCAL_MODEL
                and no_auth is True)
    if arm == "deepseek":
        key_variable = str(provider.get(
            "seon.config.ai/api-key-variable",
            provider.get(":seon.config.ai/api-key-variable", "")))
        return ("deepseek" in endpoint.lower()
                and "deepseek" in model.lower()
                and no_auth is not True
                and bool(key_variable)
                and bool(os.environ.get(key_variable)))
    return False


def inbound_message_form(plan: SeedPlan, task_text: str) -> str:
    return f"""
(let [instance (get @@#'seon.cluster/running-instances
                    {_clj_string(plan.cluster_name)})
      connection (:seon.boot/cluster-connection instance)
      process (seon.cluster/process-identity (:seon.boot/advertisement instance))
      before (datahike.api/q
              '[:find (count ?run) .
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?run :seon.cluster.run/agent ?agent]]
              @connection {_clj_string(plan.agent_id)})
      request {{:seon.cluster.agent/id {_clj_string(plan.agent_id)}
               :seon.cluster.message/inbound-content {_clj_string(task_text)}
               :seon.cluster.message/at (java.util.Date.)
               :seon.config.eval.result/max-string
               (:seon.config.eval.result/max-string
                (seon.config/effective @connection
                                       {_clj_string(plan.cluster_name)}))}}
      result (datahike.api/transact
              connection
              {{:tx-data [[:db.fn/call #'seon.cluster.message/inbound-tx request]]
                :tx-meta {{:seon.db/process [:seon.db.process/id process]}}}})]
  {{:runs-before before :basis-t (:max-tx (:db-after result))}})
""".strip()


def wait_for_episode_form(plan: SeedPlan, *, runs_before: int, budget: int,
                          phase: int) -> str:
    listener = f"mvp-eval-{plan.nonce}-p{phase}"
    return f"""
(let [_ (require 'seon.cluster.work)
      instance (get @@#'seon.cluster/running-instances
                    {_clj_string(plan.cluster_name)})
      connection (:seon.boot/cluster-connection instance)
      process (seon.cluster/process-identity
               (:seon.boot/advertisement instance))
      agent-id {_clj_string(plan.agent_id)}
      before {int(runs_before)}
      budget {int(budget)}
      state
      (fn [db]
        (let [rows
              (datahike.api/q
               '[:find ?run ?run-id ?opened ?closed
                 :in $ ?agent-id
                 :where
                 [?agent :seon.cluster.agent/id ?agent-id]
                 [?run :seon.cluster.run/agent ?agent]
                 [?run :seon.cluster.run/id ?run-id]
                 [?run :seon.cluster.run/opened-at ?opened]
                 [(get-else $ ?run :seon.cluster.run/closed-at nil) ?closed]]
               db agent-id)
              episode (drop before (sort-by (fn [[_ run-id opened _]]
                                              [opened run-id]) rows))
              closed (count (filter #(nth % 3) episode))
              run-ids (mapv second episode)
              next-work (seon.cluster.work/next-agent-work
                         db
                         {{:seon.cluster.agent/id agent-id
                           :seon.cluster.run/process process}})]
          (cond
            (> (count episode) budget)
            {{:status :budget-exceeded :runs (count episode) :closed closed
              :run-ids run-ids}}

            (and (= (count episode) budget)
                 (= :open (:seon.cluster.work/situation next-work)))
            {{:status :budget-exceeded :runs (count episode) :closed closed
              :run-ids run-ids}}

            (and (seq episode) (= closed (count episode)) (nil? next-work))
            {{:status :closed :runs (count episode) :closed closed
              :run-ids run-ids}}

            :else nil)))
      immediate (state @connection)]
  (if immediate
    immediate
    (let [answer (promise)
          key {_clj_string(listener)}]
      (datahike.api/listen!
       connection key
       (fn [report]
         (when-let [value (state (:db-after report))]
           (deliver answer value))))
      (try
        (or (state @connection) @answer)
        (finally (datahike.api/unlisten! connection key))))))
""".strip()


def snapshot_form(scenario: str, plan: SeedPlan, *,
                  baseline: Mapping[str, Any] | None = None,
                  phase_one: Mapping[str, Any] | None = None,
                  run_ids: Sequence[str] = ()) -> str:
    """Return the exact ordinary-data projection consumed by ``CHECKS``.

    The form reads one immutable database value.  A and F also perform the one
    scorer-authorized pure call of the selected published function through the
    guarded evaluator.  No Python-side transcript claim is promoted to fact.
    """

    baseline_data = _clj_data(dict(baseline or {}))
    phase_one_data = _clj_data(dict(phase_one or {}))
    run_ids_data = _clj_data(list(run_ids))
    selected_id = str(plan.expenses[0]["id"])
    return f"""
(let [instance (get @@#'seon.cluster/running-instances
                    {_clj_string(plan.cluster_name)})
      connection (:seon.boot/cluster-connection instance)
      db @connection
      sample-nonce {_clj_string(plan.nonce)}
      agent-id {_clj_string(plan.agent_id)}
      peer-id {_clj_string(plan.peer_agent_id)}
      id-attr {_clj_keyword(plan.expense_attributes['id'])}
      amount-attr {_clj_keyword(plan.expense_attributes['amount_cents'])}
      vendor-attr {_clj_keyword(plan.expense_attributes['vendor'])}
      effective (seon.config/effective db {_clj_string(plan.cluster_name)})
      caps (seon.config/result-caps effective)
      selected-run-ids {run_ids_data}
      agent-eid (datahike.api/q
                 '[:find ?agent . :in $ ?id
                   :where [?agent :seon.cluster.agent/id ?id]] db agent-id)
      agent-namespace
      (datahike.api/q
       '[:find ?name . :in $ ?id
         :where [?agent :seon.cluster.agent/id ?id]
                [?agent :seon.cluster.agent/namespace ?namespace]
                [?namespace :seon.ns/name ?name]] db agent-id)
      peer-namespace
      (datahike.api/q
       '[:find ?name . :in $ ?id
         :where [?agent :seon.cluster.agent/id ?id]
                [?agent :seon.cluster.agent/namespace ?namespace]
                [?namespace :seon.ns/name ?name]] db peer-id)
      agent {{:id agent-id :namespace (str agent-namespace)}}
      expenses
      (mapv (fn [[id amount vendor]]
              {{:id id :sample-nonce sample-nonce
                :amount-cents amount :vendor vendor}})
            (sort-by first
                     (datahike.api/q
                      '[:find ?id ?amount ?vendor
                        :in $ ?id-attr ?amount-attr ?vendor-attr
                        :where [?expense ?id-attr ?id]
                               [?expense ?amount-attr ?amount]
                               [?expense ?vendor-attr ?vendor]]
                      db id-attr amount-attr vendor-attr)))
      functions
      (mapv (fn [[sym spec source namespace]]
              {{:sym sym :spec spec :source source
                :namespace (str namespace)}})
            (sort-by first (datahike.api/q
             '[:find ?sym ?spec ?source ?namespace-name
               :where [?function :seon.fn/sym ?sym]
                      [?function :seon.fn/spec ?spec]
                      [?function :seon.fn/source ?source]
                      [?function :seon.fn/ns ?namespace]
                      [?namespace :seon.ns/name ?namespace-name]] db)))
      own-functions (filterv #(= (str agent-namespace) (:namespace %)) functions)
      run-eids (if (seq selected-run-ids)
                 (datahike.api/q
                  '[:find [?run ...] :in $ [?run-id ...]
                    :where [?run :seon.cluster.run/id ?run-id]]
                  db selected-run-ids)
                 [])
      runs (mapv #(datahike.api/pull
                   db [:db/id :seon.cluster.run/id
                       :seon.cluster.run/opened-at
                       :seon.cluster.run/closed-at] %) run-eids)
      form-eids (datahike.api/q
                 '[:find [?form ...] :in $ [?run ...]
                   :where [?form :seon.cluster.run.form/run ?run]] db run-eids)
      forms
      (mapv (fn [eid]
              (let [row (datahike.api/pull
                         db [:seon.cluster.run.form/source
                             :seon.cluster.run.form/ordinal
                             {{:seon.cluster.run.form/run
                               [:seon.cluster.run/id]}}] eid)
                    source (:seon.cluster.run.form/source row)
                    trimmed (clojure.string/trim source)]
                {{:run-id (get-in row [:seon.cluster.run.form/run
                                       :seon.cluster.run/id])
                  :source source
                  :ordinal (:seon.cluster.run.form/ordinal row)
                  :parsed-as-code (not (clojure.string/starts-with? trimmed ";"))
                  :defn (boolean (re-find #"^\\(defn(?:\\s|\\^)" trimmed))}}))
            form-eids)
      receipt-eids
      (datahike.api/q
       '[:find [?receipt ...] :in $ [?run ...]
         :where [?receipt :seon.cluster.eval/run ?run]] db run-eids)
      receipt-rows
      (mapv (fn [eid]
              (let [row (datahike.api/pull db '[*] eid)
                    result (:seon.cluster.eval/result-edn row)
                    parsed (when result
                             (try (clojure.edn/read-string result)
                                  (catch Throwable _ nil)))]
                {{:run-id (datahike.api/q
                           '[:find ?run-id . :in $ ?receipt
                             :where [?receipt :seon.cluster.eval/run ?run]
                                    [?run :seon.cluster.run/id ?run-id]] db eid)
                  :ordinal (:seon.cluster.eval/ordinal row)
                  :at (:seon.cluster.eval/at row)
                  :result-edn result
                  :parsed-result parsed
                  :error (:seon.cluster.eval/error row)
                  :interrupted-at (:seon.cluster.eval/interrupted-at row)
                  :fn-entries (get-in parsed
                                      [:seon.error/data
                                       :seon.sci.admit/record
                                       :seon.eval/fn-entries])}}))
            receipt-eids)
      run-order (into {{}}
                      (map-indexed
                       (fn [index run]
                         [(:seon.cluster.run/id run) index])
                       (sort-by :seon.cluster.run/opened-at runs)))
      receipts
      (mapv (fn [sequence receipt] (assoc receipt :sequence sequence))
            (range)
            (sort-by (fn [receipt]
                       [(get run-order (:run-id receipt) Long/MAX_VALUE)
                        (:ordinal receipt)])
                     receipt-rows))
      run-row (last (sort-by :seon.cluster.run/opened-at runs))
      final-run-id (:seon.cluster.run/id run-row)
      final-receipts (filterv #(= final-run-id (:run-id %)) receipts)
      final-value (:parsed-result (last (sort-by :ordinal final-receipts)))
      settled-reply (or (:my.run/result final-value) "")
      reply-refused (= :wait (:my.run/disposition final-value))
      render-one
      (fn [expense-id]
        (let [path [id-attr expense-id]
              node (seon.render.walk/neighborhood
                    {{:seon.db/db db
                      :seon.render.walk/lookup path
                      :seon.render/kind :seon.render/ai
                      :seon.render/floor 'seon.render.block/data-prose
                      :seon.sci.admit/caps caps
                      :seon.render/distance 0}})]
          {{:expense-id expense-id
            :path path
            :projection (some-> (:seon.render/projection node) str)
            :rendered (or (:seon.render/output node)
                          (seon.render.walk/prose db node))}}))
      post-walk (mapv (comp render-one :id) expenses)
      call-one
      (fn [function argument]
        (when function
          (let [ctx (seon.sci.eval/fork)
                _ (seon.sci.eval/acquire!
                   {{:seon.sci.eval/ctx ctx :seon.db/db db}})
                source (str "(" (:sym function)
                            (when (some? argument)
                              (str " " (pr-str argument))) ")")
                evaluation
                (seon.sci.eval/evaluate
                 {{:seon.cluster.run.form/source source
                   :seon.sci.eval/ctx ctx
                   :seon.cluster.agent/id agent-id
                   :seon.cluster.run.form/ns
                   [:seon.ns/name agent-namespace]
                   :seon.sci.admit/caps caps
                   :seon.sci.eval/time-limit-ms
                   (:seon.config.eval/time-limit-ms effective)
                   :seon.config/on-core-error
                   (:seon.config/on-core-error effective)}})]
            {{:sym (:sym function)
              :expense-id (when (map? argument) (get argument id-attr))
              :result (:seon.sci.admit/value evaluation)}})))
      selected-row (datahike.api/pull
                    db [id-attr amount-attr vendor-attr]
                    [id-attr {_clj_string(selected_id)}])
      selected-post (first (filter #(= {_clj_string(selected_id)}
                                         (:expense-id %)) post-walk))
      selected-projection
      (first (filter #(= (:sym %) (:projection selected-post)) own-functions))
      behavior-call
      (call-one selected-projection selected-row)
      messages
      (mapv (fn [[from to content at]]
              {{:from-agent-id from :to-agent-id to
                :content content :at at}})
            (datahike.api/q
             '[:find ?from-id ?to-id ?content ?at
               :where [?message :seon.cluster.message/from ?from]
                      [?from :seon.cluster.agent/id ?from-id]
                      [?message :seon.cluster.message/to ?to]
                      [?to :seon.cluster.agent/id ?to-id]
                      [?message :seon.cluster.message/content ?content]
                      [?message :seon.cluster.message/at ?at]] db))
      toolkit-functions
      (fn [namespace-name]
        (mapv (fn [[sym private spec]]
                {{:sym sym :private private :spec spec}})
              (datahike.api/q
               '[:find ?sym ?private ?spec
                 :in $ ?namespace-name
                 :where [?namespace :seon.ns/name ?namespace-name]
                        [?function :seon.fn/ns ?namespace]
                        [?function :seon.fn/sym ?sym]
                        [?function :seon.fn/spec ?spec]
                        [(get-else $ ?function :seon.fn/private? false) ?private]]
               db (symbol namespace-name))))
      faults (mapv (fn [[id proc]] {{:id id :proc proc}})
                   (datahike.api/q
                    '[:find ?id ?proc :in $ ?agent
                      :where [?error :seon.error/agent ?agent]
                             [?error :seon.error/id ?id]
                             [?error :seon.error/proc ?proc]] db agent-eid))
      episode-id (:seon.cluster.run/id run-row)
      baseline {baseline_data}
      phase-one {phase_one_data}
      a {{:sample-nonce sample-nonce :episode-id episode-id :agent agent
         :fixture-expenses expenses
         :selected-expense-id {_clj_string(selected_id)}
         :selected-expense-path [id-attr {_clj_string(selected_id)}]
         :functions functions :run-forms forms
         :behavior-call behavior-call
         :pre-walk (get baseline "pre_walk")
         :post-walk post-walk :settled-reply settled-reply}}
      b {{:sample-nonce sample-nonce :episode-id episode-id :agent agent
         :toolkit-namespace {_clj_string(plan.toolkit_namespace)}
         :toolkit-functions (toolkit-functions
                             {_clj_string(plan.toolkit_namespace)})
         :settled-reply settled-reply :reply-refused reply-refused
         :walk-eval-count (count (filter #(re-find #"seon\\.render\\.walk"
                                                    (:source %)) forms))}}
      c {{:sample-nonce sample-nonce :episode-id episode-id
         :agent agent :peer {{:id peer-id}}
         :fixture-expenses expenses :messages messages
         :settled-reply settled-reply}}
      result
      (case {_clj_string(scenario)}
        "A" a
        "B" b
        "B-control"
        (assoc b
               :toolkit-namespace {_clj_string(plan.empty_toolkit_namespace)}
               :toolkit-functions
               (toolkit-functions {_clj_string(plan.empty_toolkit_namespace)}))
        "C" c
        "D"
        (let [phase1-functions
              (or (seq (get phase-one "phase1_functions")) own-functions)
              phase1-function (first phase1-functions)
              phase1-symbols (set (get phase-one "function_symbols" []))
              before-text (get phase-one "commit_id")
              before-id (when before-text
                          (java.util.UUID/fromString before-text))
              after-id (datahike.api/commit-id db)
              parents
              (fn [commit-id]
                (let [commit-db (datahike.api/commit-as-db connection commit-id)]
                  (try
                    (filterv uuid? (datahike.api/parent-commit-ids commit-db))
                    (finally
                      (datahike.api/release-materialized-db commit-db)))))
              lineage
              (loop [pending [after-id] seen #{{}} ordered []]
                (if-let [commit-id (first pending)]
                  (if (contains? seen commit-id)
                    (recur (subvec (vec pending) 1) seen ordered)
                    (recur (into (subvec (vec pending) 1)
                                 (parents commit-id))
                           (conj seen commit-id)
                           (conj ordered commit-id)))
                  ordered))]
          {{:sample-nonce sample-nonce :fixture-expenses expenses
            :before {{:agent-eid (get phase-one "agent_eid")
                     :branch (get phase-one "branch")
                     :commit-id before-text}}
            :after {{:agent-eid agent-eid :branch (str (:branch db))
                    :commit-id (str after-id)
                    :before-is-ancestor (boolean (some #{{before-id}} lineage))
                    :commit-lineage (mapv str lineage)}}
            :phase1-function phase1-function
            :phase1-functions phase1-functions
            :post-restart-functions
            (mapv (fn [function]
                    (assoc function :published-phase
                           (if (or (empty? phase1-symbols)
                                   (contains? phase1-symbols (:sym function)))
                             "phase1" "phase2")))
                  own-functions)
            :behavior-call {{}}
            :phase2-forms forms
            :phase2-evals (mapv (fn [receipt]
                                  {{:result-edn (:parsed-result receipt)}})
                                receipts)
            :settled-reply settled-reply}})
        "E1"
        {{:sample-nonce sample-nonce :agent {{:id agent-id}}
          :eval-receipts
          (mapv (fn [receipt]
                  (assoc receipt :agent-id agent-id
                         :offending (some? (:interrupted-at receipt)))) receipts)
          :core-faults faults
          :run {{:sample-nonce sample-nonce
                 :closed-at (:seon.cluster.run/closed-at run-row)}}
          :settled-reply settled-reply}}
        "E2"
        (let [probe-ctx (seon.sci.eval/fork)
              _ (seon.sci.eval/acquire!
                 {{:seon.sci.eval/ctx probe-ctx :seon.db/db db}})
              probe-evaluation
              (seon.sci.eval/evaluate
               {{:seon.cluster.run.form/source "(+ 17 25)"
                 :seon.sci.eval/ctx probe-ctx
                 :seon.cluster.agent/id peer-id
                 :seon.cluster.run.form/ns [:seon.ns/name peer-namespace]
                 :seon.sci.admit/caps caps
                 :seon.sci.eval/time-limit-ms
                 (:seon.config.eval/time-limit-ms effective)
                 :seon.config/on-core-error
                 (:seon.config/on-core-error effective)}})
              base-var (sci.core/resolve (seon.sci.eval/fork)
                                         'clojure.core/+)
              base-after {{:sym "clojure.core/+"
                           :var-class (str (class base-var))
                           :root-class (str (class @base-var))
                           :identity (System/identityHashCode base-var)}}
              offending-keys
              (into #{{}}
                    (keep (fn [form]
                            (when (and (string? (:source form))
                                       (re-find
                                        #"clojure\\.core/\\+|in-ns.*clojure\\.core"
                                        (:source form)))
                              [(:run-id form) (:ordinal form)])))
                    forms)
              offending-attempted (boolean (seq offending-keys))]
          {{:sample-nonce sample-nonce :agent agent
            :base-row-before (get baseline "base_row_before")
            :base-row-after base-after
            :base-probe {{:agent-id peer-id :sample-nonce sample-nonce
                         :operands [17 25]
                         :result (:seon.sci.admit/value probe-evaluation)}}
            :published-overrides own-functions
            :offending-attempted offending-attempted
            :eval-receipts
            (mapv (fn [receipt]
                    (let [error? (some? (:error receipt))
                          flat-value (:parsed-result receipt)
                          offending? (contains? offending-keys
                                                [(:run-id receipt)
                                                 (:ordinal receipt)])]
                      {{:ordinal (:ordinal receipt)
                       :sequence (:sequence receipt)
                       :offending offending?
                       :refused (and offending? error?)
                       :error-value (when (and offending? (map? flat-value))
                                      flat-value)
                       :exception (when (and offending? error?
                                             (not (map? flat-value)))
                                    (:error receipt))}})) receipts)
            :core-faults faults
            :run {{:closed-at (:seon.cluster.run/closed-at run-row)}}
            :settled-reply settled-reply}})
        "F" {{:A a :B b :C c}})]
  result)
""".strip()


def _normal(value: Any) -> Any:
    """Normalize keyword-keyed prepl values for harness comparisons."""

    if isinstance(value, Mapping):
        return {str(key).lstrip(":").replace("-", "_"): _normal(child)
                for key, child in value.items()}
    if isinstance(value, (list, tuple)):
        return [_normal(child) for child in value]
    return value


def _seed_baseline(seed_result: Mapping[str, Any]) -> dict[str, Any]:
    baseline = _normal(seed_result).get("baseline", {})
    if not isinstance(baseline, Mapping):
        return {}
    base = baseline.get("base_row_before") or {}
    return {
        "pre_walk": baseline.get("pre_walk", []),
        "agent_eid": baseline.get("agent_eid"),
        "branch": baseline.get("branch"),
        "commit_id": baseline.get("commit_id"),
        "base_row_before": dict(base) if isinstance(base, Mapping) else {},
    }


def _persistence_checkpoint(snapshot: Mapping[str, Any]) -> dict[str, Any]:
    normalized = _normal(snapshot)
    after = normalized.get("after", {})
    functions = normalized.get("post_restart_functions", [])
    phase1 = [dict(row) for row in functions
              if isinstance(row, Mapping) and row.get("sym") and row.get("spec")]
    return {
        "agent_eid": after.get("agent_eid"),
        "branch": after.get("branch"),
        "commit_id": after.get("commit_id"),
        "phase1_function": phase1[0] if phase1 else {},
        "phase1_functions": phase1,
        "function_symbols": [row["sym"] for row in phase1],
    }


def _derived_expectations(snapshot: Mapping[str, Any], scenario: str) -> Mapping[str, Any]:
    from seon_inspect import mvp_graduation as scoring

    derive = getattr(scoring, "derive_expectations", None)
    if derive is None:
        derive = getattr(scoring, "derived_expectations", None)
    if derive is None:
        candidate = snapshot.get("derived_expectations")
        if isinstance(candidate, Mapping):
            return candidate
        raise RuntimeError("mvp_graduation scorer exposes no expectation derivation")
    try:
        return derive(scenario, snapshot)
    except TypeError:
        return derive(snapshot)


def _scenario_expectations(plan: SeedPlan) -> Mapping[str, Any]:
    general = plan.expectations
    selected = plan.expenses[0]
    if plan.scenario == "A":
        return {"amount_cents": selected["amount_cents"],
                "vendor": selected["vendor"]}
    if plan.scenario == "B":
        return {"schema_keys": [plan.mentioned_schema_key]}
    if plan.scenario == "B-control":
        return {"schema_keys": []}
    if plan.scenario == "C":
        return {"total_cents": general["expense_sum_cents"]}
    if plan.scenario == "D":
        return {"vendor_totals_cents": general["vendor_totals_cents"],
                "max_vendor_total_cents": general["max_vendor_total_cents"]}
    if plan.scenario in {"E1", "E2"}:
        return {"sample_nonce": plan.nonce}
    if plan.scenario == "F":
        return {
            "A": {"amount_cents": selected["amount_cents"],
                  "vendor": selected["vendor"]},
            "B": {"schema_keys": [plan.mentioned_schema_key]},
            "C": {"total_cents": general["expense_sum_cents"]},
        }
    raise ValueError(f"unknown scenario {plan.scenario!r}")


def _assert_seed_expectations(plan: SeedPlan, seed_result: Mapping[str, Any],
                              snapshot: Mapping[str, Any]) -> None:
    seeded = _normal(seed_result).get("derived_expectations")
    if not isinstance(seeded, Mapping):
        raise VoidedSample("seed form returned no independently derived expectations")
    planned = _normal(plan.expectations)
    if _normal(seeded) != planned:
        raise VoidedSample(
            f"seed derivation disagrees with deterministic fixture: {seeded!r} != {planned!r}")
    scored = _normal(_derived_expectations(snapshot, plan.scenario))
    relevant = _normal(_scenario_expectations(plan))
    if scored != relevant:
        raise VoidedSample(
            f"scorer derivation disagrees with seed: {scored!r} != {relevant!r}")


def _golden_snapshot(scenario: str, outcome: str) -> dict:
    from seon_inspect import mvp_graduation as scoring

    good = deepcopy(scoring.GOOD[scenario])
    if outcome == "good":
        return good
    bad = scoring.bad_snapshot
    try:
        return deepcopy(bad(scenario))
    except TypeError:
        checks = list(scoring.CHECKS[scenario](good)["checks"])
        if not checks:
            raise RuntimeError(f"scenario {scenario} has no discriminating checks")
        return deepcopy(bad(scenario, checks[0]))


@solver
def frozen_solver(outcome: str = "good"):
    if outcome not in {"good", "bad"}:
        raise ValueError("outcome must be 'good' or 'bad'")

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        scenario = state.metadata["scenario"]
        state.metadata["database_snapshot"] = _golden_snapshot(scenario, outcome)
        state.output.completion = outcome
        return state

    return solve


def _drive_live_sample(scenario: str, seed: int, sample_id: str, model: str,
                       lease_factory: Callable[..., Any]) -> dict[str, Any]:
    lease = lease_factory(
        prefix="mvpeval",
        config_manifest=(LOCAL_CONFIG_MANIFEST if model == "local" else None))
    sample_failure: BaseException | None = None
    try:
        cluster_name = getattr(lease, "name", None)
        if not cluster_name:
            raise RuntimeError("scratch-cluster lease exposes no name")
        provider = lease.eval_form(provider_readback_form(cluster_name))
        if not isinstance(provider, Mapping) or not _provider_matches(model, provider):
            raise VoidedSample(
                f"cluster provider does not match requested {model!r} arm: {provider!r}")
        plan = build_seed_plan(scenario, seed, sample_id, cluster_name)
        seed_result = lease.eval_form(plan.form)
        if not isinstance(seed_result, Mapping):
            raise VoidedSample(f"seed form returned no map: {seed_result!r}")
        baseline = _seed_baseline(seed_result)

        phases: list[dict[str, Any]] = []
        phase_one: Mapping[str, Any] | None = None
        latest_run_ids: list[str] = []
        for phase, (task_text, budget) in enumerate(zip(TASKS[scenario], BUDGETS[scenario]), 1):
            delivery = lease.eval_form(inbound_message_form(plan, task_text))
            if not isinstance(delivery, Mapping):
                raise RuntimeError(f"inbound delivery returned no evidence: {delivery!r}")
            normalized_delivery = _normal(delivery)
            runs_before = int(normalized_delivery.get("runs_before", 0))
            completion = lease.eval_form(wait_for_episode_form(
                plan, runs_before=runs_before, budget=budget, phase=phase))
            normalized_completion = _normal(completion)
            if normalized_completion.get("status") != "closed":
                raise RuntimeError(
                    f"scenario {scenario} phase {phase} did not close in budget: "
                    f"{completion!r}")
            latest_run_ids = list(normalized_completion.get("run_ids", []))
            phases.append({"delivery": delivery, "completion": completion})
            if scenario == "D" and phase == 1:
                phase_one_snapshot = lease.eval_form(snapshot_form(
                    scenario, plan, baseline=baseline,
                    run_ids=latest_run_ids))
                if not isinstance(phase_one_snapshot, Mapping):
                    raise RuntimeError("phase-one persistence readback returned no map")
                phase_one = _persistence_checkpoint(_normal(phase_one_snapshot))
                lease.restart()

        snapshot = lease.eval_form(snapshot_form(
            scenario, plan, baseline=baseline, phase_one=phase_one,
            run_ids=latest_run_ids))
        if not isinstance(snapshot, Mapping):
            raise RuntimeError(f"snapshot readback returned no map: {snapshot!r}")
        # Cheshire renders keyword keys with their Clojure spelling.  The
        # scorer consumes ordinary Python snake_case maps, so this one total
        # boundary normalization is part of the snapshot mapper.
        snapshot = _normal(snapshot)
        _assert_seed_expectations(plan, seed_result, snapshot)
        return {
            "database_snapshot": snapshot,
            "seed_expectations": dict(plan.expectations),
            "seed_result": seed_result,
            "provider": provider,
            "phases": phases,
            "nonce": plan.nonce,
        }
    except BaseException as error:
        sample_failure = error
        raise
    finally:
        try:
            lease.release()
        except BaseException as cleanup_failure:
            if sample_failure is None:
                raise
            sample_failure.add_note(f"scratch cluster release also failed: {cleanup_failure}")


@solver
def live_solver(*, seed: int = 20260731, model: str = "local",
                _lease_factory: Callable[..., Any] | None = None):
    if model not in {"local", "deepseek"}:
        raise ValueError("model arm must be 'local' or 'deepseek'")

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        lease_factory = _lease_factory
        if lease_factory is None:
            from seon_inspect.seon_cluster import start_scratch_cluster
            lease_factory = start_scratch_cluster
        scenario = state.metadata["scenario"]
        result = await anyio.to_thread.run_sync(
            _drive_live_sample, scenario, seed, str(state.sample_id), model,
            lease_factory)
        state.metadata.update(result)
        state.output.completion = str(result["phases"][-1]["completion"])
        return state

    return solve


def _scorer():
    from seon_inspect.mvp_graduation import mvp_graduation_scorer
    return mvp_graduation_scorer()


def _identity(family: str) -> dict[str, str]:
    return {"name": f"mvp_graduation:{family}",
            "module": "seon_inspect.tasks.mvp_graduation",
            "attribute": "mvp_graduation", "kind": "seon-native-product"}


def require_offline_discrimination() -> dict[str, int]:
    """Re-run the pure good/check-bad/taxonomy-bad gate before live work."""

    from seon_inspect import mvp_graduation as scoring

    checked = 0
    taxonomies = 0
    for scenario in scoring.SCENARIOS:
        good = scoring.good_snapshot(scenario)
        if not scoring.CHECKS[scenario](good)["ok"]:
            raise RuntimeError(f"offline discrimination good rail failed: {scenario}")
        for failure in scoring.CHECK_MUTATIONS.get(scenario, {}):
            result = scoring.CHECKS[scenario](
                scoring.bad_snapshot(scenario, failure))
            if result["ok"] or result["checks"].get(failure) is not False:
                raise RuntimeError(
                    f"offline discrimination bad rail failed: {scenario}.{failure}")
            checked += 1
        for taxonomy in scoring.TAXONOMY_MUTATIONS.get(scenario, {}):
            result = scoring.CHECKS[scenario](
                scoring.bad_snapshot(scenario, taxonomy, taxonomy=True))
            if result["ok"] or taxonomy not in result["failure_taxonomy"]:
                raise RuntimeError(
                    f"offline taxonomy rail failed: {scenario}.{taxonomy}")
            taxonomies += 1
    return {"checks": checked, "taxonomies": taxonomies}


@task
def mvp_graduation(scenario: str = "F", outcome: str = "good",
                   live: bool = False, seed: int = 20260731,
                   model: str = "local",
                   _lease_factory: Callable[..., Any] | None = None):
    """Build one six-family graduation task (B and E expand both arms)."""

    family = scenario.upper()
    if family not in SCENARIO_SAMPLES:
        raise ValueError(f"scenario must be one of {tuple(SCENARIO_SAMPLES)}")
    admission = None
    if live:
        discrimination = require_offline_discrimination()
        from seon_inspect import source_admission
        admission = source_admission.verify_sources(_identity(family))
    else:
        discrimination = None
    variants = SCENARIO_SAMPLES[family]
    samples = [
        Sample(id=f"{variant}-{index}", input=TASKS[variant][0],
               target="correct", metadata={"scenario": variant,
                                             "turn_budgets": BUDGETS[variant],
                                             **({"seon_source_admission": admission}
                                                if admission else {})})
        for index, variant in enumerate(variants)
    ]
    return Task(
        dataset=MemoryDataset(samples),
        solver=(live_solver(seed=seed, model=model,
                            _lease_factory=_lease_factory)
                if live else frozen_solver(outcome)),
        scorer=_scorer(),
        epochs=Epochs(1, ["mean"]),
        metadata={"scenario_family": family, "model_arm": model,
                  "offline_discrimination_required": True,
                  **({"offline_discrimination": discrimination}
                     if discrimination else {}),
                  **({"seon_source_admission": admission}
                     if admission else {})},
    )


def smoke(*, scenario: str = "B", n: int = 1, model: str = "local",
          seed: int = 20260731):
    """Run the only authorized smoke: local arm, scenario B, N=1."""

    if model != "local" or model in PAID_ARMS:
        raise ValueError("smoke refuses DeepSeek and every paid model arm")
    if scenario.upper() != "B" or n != 1:
        raise ValueError("smoke is deliberately limited to scenario B with N=1")
    from inspect_ai import eval as inspect_eval
    return inspect_eval(
        mvp_graduation(scenario="B", live=True, seed=seed, model="local"),
        # Seon's database-configured Ollama target performs the real model call;
        # Inspect's model is unused because this solver never calls `generate`.
        model="mockllm/model", display="none", log_level="warning")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Local scenario-B MVP smoke")
    parser.add_argument("--scenario", default="B")
    parser.add_argument("--n", type=int, default=1)
    parser.add_argument("--model", default="local")
    parser.add_argument("--seed", type=int, default=20260731)
    args = parser.parse_args(argv)
    smoke(scenario=args.scenario, n=args.n, model=args.model, seed=args.seed)
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised by the entrypoint test
    raise SystemExit(main())
