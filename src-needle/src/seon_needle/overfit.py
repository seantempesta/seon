"""Overfit smoke: prove gradients flow through the MLX port.

Finetunes the real checkpoint with AdamW on ~10 synthetic (context ->
forms) pairs — cross-entropy on decoder targets with PAD masked, the same
teacher-forcing shape as needle's finetune (dec_in = [EOS, <tool_call>,
answer...], dec_tgt = [<tool_call>, answer..., EOS]). Success = loss drops
AND greedy decode reproduces the memorized targets. The REAL finetune loop
(mined turns, gold exemplars, contrastive head) is Track B2 — this only
proves the plumbing.

Master weights are cast to float32 for the update step: AdamW deltas
(~1e-5) fall below bf16 resolution on 1e-2-magnitude weights, so bf16
master weights silently stop learning. Activations still round-trip
through bf16 inside the forward (zcrms_norm casts), which is fine here.
"""

import time

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim

from .generate import build_encoder_input, generate_batch
from .model import load_model, make_causal_mask, make_padding_mask
from .tokenizer import EOS_ID, PAD_ID, TOOL_CALL_ID, load_tokenizer

# (context -> forms) pairs, REPL-autosuggest shaped: a compact situation
# projection in, multi-form Clojure out.
SYNTHETIC_PAIRS = [
    ("current-ns: my.kb | plan: record source ratings | last: (db/query rating) => 3 rows",
     '(db/transact! :seon [{:my.kb/id "src-1" :my.kb/rating 4}])'),
    ("current-ns: my.kb | warning: :my.kb/rating unregistered",
     '(schema/register! :my.kb/rating [:int {:min 1 :max 5}])'),
    ("current-ns: my.plan | plan: parse CSV ▶ validate rows",
     '(my.plan/done! {:my.plan/id "step-2"})'),
    ("current-ns: user | goal: inspect recent errors",
     "(seon.agent.inspect/errors)"),
    ("current-ns: my.kb | goal: find unrated sources",
     "(db/query '[:find ?e :where [?e :my.kb/id] (not [?e :my.kb/rating])])"),
    ("current-ns: user | msg: run the foo tests",
     "(user/run-tests 'seon.foo-test)"),
    ("current-ns: my.plan | goal: plan the exporter work",
     '(my.plan/reconcile! {:my.plan/markdown "# Exporter\\n- [ ] projection fn\\n- [ ] emit JSONL"})'),
    ("current-ns: my.kb | last: (require kb) => nil",
     "(require '[my.kb :as kb])"),
    ("current-ns: user | warning: 2 failed evals in seon.agent.loop",
     "(seon.agent.inspect/error {:seon.agent.inspect/eid 42})"),
    ("current-ns: my.data | goal: count plan steps by status",
     "(db/query '[:find ?status (count ?e) :where [?e :my.plan/status ?status]])"),
]


def build_batch(tokenizer, pairs, max_dec=128):
    """Teacher-forcing batch exactly like prepare_tool_call_pairs."""
    enc_lists, dec_in_lists, dec_tgt_lists = [], [], []
    for context, forms in pairs:
        enc_lists.append(build_encoder_input(tokenizer, context, "[]"))
        a = tokenizer.encode(forms)[:max_dec - 3]
        dec_in_lists.append([EOS_ID, TOOL_CALL_ID] + a)
        dec_tgt_lists.append([TOOL_CALL_ID] + a + [EOS_ID])

    def pad(lists):
        n = max(len(x) for x in lists)
        out = mx.full((len(lists), n), PAD_ID, dtype=mx.int32)
        for i, x in enumerate(lists):
            out[i, :len(x)] = mx.array(x, dtype=mx.int32)
        return out

    return pad(enc_lists), pad(dec_in_lists), pad(dec_tgt_lists)


def loss_fn(model, enc, dec_in, dec_tgt):
    src_mask = make_padding_mask(enc, PAD_ID)
    tgt_mask = mx.logical_and(make_causal_mask(dec_in.shape[1]),
                              make_padding_mask(dec_in, PAD_ID))
    logits = model(enc, dec_in, src_mask=src_mask, tgt_mask=tgt_mask)
    ce = nn.losses.cross_entropy(logits, dec_tgt, reduction="none")
    mask = (dec_tgt != PAD_ID).astype(mx.float32)
    return (ce * mask).sum() / mask.sum()


def _cast_tree(tree, dtype):
    if isinstance(tree, dict):
        return {k: _cast_tree(v, dtype) for k, v in tree.items()}
    if isinstance(tree, list):
        return [_cast_tree(v, dtype) for v in tree]
    return tree.astype(dtype)


def run(steps=120, lr=3e-4, pairs=SYNTHETIC_PAIRS, verbose=True):
    """Returns {"losses": [...], "exact": n, "total": len(pairs)}."""
    tokenizer = load_tokenizer()
    model = load_model()
    model.w = _cast_tree(model.w, mx.float32)  # f32 master weights

    enc, dec_in, dec_tgt = build_batch(tokenizer, pairs)
    opt = optim.AdamW(learning_rate=lr)
    step_fn = nn.value_and_grad(model, loss_fn)

    losses = []
    t0 = time.perf_counter()
    for step in range(steps):
        loss, grads = step_fn(model, enc, dec_in, dec_tgt)
        opt.update(model, grads)
        mx.eval(model.parameters(), opt.state, loss)
        losses.append(float(loss))
        if verbose and (step % 20 == 0 or step == steps - 1):
            print(f"  step {step:>4}  loss {float(loss):.4f}")
    train_s = time.perf_counter() - t0
    train_tokens = steps * int((dec_tgt != PAD_ID).sum())
    if verbose:
        print(f"  {steps} steps in {train_s:.1f}s "
              f"({train_tokens / train_s:.0f} target tok/s)")

    result = generate_batch(model, tokenizer,
                            [c for c, _ in pairs], ["[]"] * len(pairs),
                            max_gen_len=128)
    # compare at the TOKEN level (what training teaches): generated =
    # [<tool_call>, answer tokens...]. Text-level compare would trip over
    # SentencePiece's dummy-prefix space at the <tool_call> boundary (the
    # reference's stripped output carries the same leading space).
    exact = 0
    for i, (_, want) in enumerate(pairs):
        want_tokens = [TOOL_CALL_ID] + tokenizer.encode(want)
        ok = result["tokens"][i] == want_tokens
        exact += ok
        if verbose:
            print(f"  {'=' if ok else '!'} {result['texts'][i]!r}")
    if verbose:
        print(f"memorized {exact}/{len(pairs)} exactly "
              f"(loss {losses[0]:.3f} -> {losses[-1]:.3f})")
    return {"losses": losses, "exact": exact, "total": len(pairs)}


if __name__ == "__main__":
    run()
