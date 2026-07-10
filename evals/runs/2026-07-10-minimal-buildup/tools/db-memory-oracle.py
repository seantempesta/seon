#!/usr/bin/env python3
# db-memory-oracle.py TRANSCRIPT — GREEN iff the db-memory contract's
# store-then-recall actually happened:
#   (a) a schema/register! + db/transact! eval ran ok in some turn T
#   (b) a db/query eval ran ok?=true in a LATER turn than the transact
#   (c) the final answer (59.5 = 42.5 + 17.0, caches > 10 kg) appears in a
#       model-authored REPLY inside a message/user or complete form
# Exit 0 = GREEN, 1 = RED. Prints the evidence either way.
import sys, re

txt = open(sys.argv[1], encoding="utf-8", errors="replace").read()
turns = re.split(r'^════════ TURN ', txt, flags=re.M)[1:]

transact_turn = None
query_turn = None
answer_turn = None
for t in turns:
    num = int(t.split(None, 1)[0])
    evals = re.search(r'^──── EVALS.*?────\n(.*)', t, flags=re.M | re.S)
    evals = evals.group(1) if evals else ""
    # each eval block: "── eval eid=… ok?=… \n source: … result-edn: …"
    for ev in re.split(r'^\s*── eval ', evals, flags=re.M)[1:]:
        ok = 'ok?=true' in ev.split('\n', 1)[0]
        if not ok:
            continue
        if re.search(r'db/transact!|seon\.db/transact!', ev) and transact_turn is None:
            transact_turn = num
        if re.search(r'db/query|seon\.db/query', ev) and transact_turn is not None \
           and num > transact_turn and query_turn is None:
            query_turn = num
    reply = re.search(r'^──── REPLY.*?────\n(.*?)(?=^\n?──── EVALS)', t, flags=re.M | re.S)
    reply = reply.group(1) if reply else ""
    # same-reply co-occurrence of a report verb + the answer (spot-verify
    # GREEN cases by eye — regex can't safely span nested paren strings)
    if re.search(r'\(\s*(message/user|complete)\b', reply) and re.search(r'59\.5', reply):
        answer_turn = num

print(f"transact ok turn: {transact_turn}")
print(f"db/query ok in LATER turn: {query_turn}")
print(f"answer 59.5 in message/complete: turn {answer_turn}")
if transact_turn and query_turn and answer_turn:
    print("GREEN: store-then-recall verified (query ran after transact; answer reported)")
    sys.exit(0)
print("RED: store-then-recall NOT verified")
sys.exit(1)
