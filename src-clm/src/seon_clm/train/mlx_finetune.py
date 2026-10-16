"""Fine-tune the two CLM projection heads in MLX on cached, frozen-encoder embeddings.

Mirrors ``reference-code/CLM/train/finetune.py`` (task ``clm``):

* objective  symmetric in-batch InfoNCE over ``scale * cos`` with a learnable log-temperature
             (``scale = min(exp(logit_scale), 100)``); off-diagonal pairs that share a group code
             ``(task_id, step_idx)`` are masked in both directions (same-step rollouts are false negatives);
* optimiser  AdamW (weight decay 0), gradient-norm clip 1.0, one-cycle schedule (10 % warm-up, cosine);
* lr         ``2e-3 * sqrt(1024 / width) * sqrt(batch / 1024)`` unless given;
* selection  best epoch by validation ``top1`` (default) or ``val_loss``, task-disjoint validation split.

Additions: explicit hard negatives. A record may carry ``negatives`` (other actions that were available
in the same state); they join the batch's action pool as extra columns for every state, so the
state -> action direction ranks the gold action against the whole pool, while the action -> state
direction stays over the batch's gold actions.

Record (JSONL): ``{"state": str, "action": str, "negatives": [str], "task_id": str, "step_idx": int}``;
only ``state`` and ``action`` are required.

The embedding cache stores float16 vectors keyed by the digest of (encoder id, side, text), so a text
is encoded once however many datasets or runs use it. Checkpoints are written atomically in the layout
``convert.py`` produces, so ``HeadPair`` (and a running server) hot-reloads them.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import time
from dataclasses import dataclass, field

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim
import numpy as np
from mlx.utils import tree_flatten

from ..heads import Head, config_of

NEG = -1e9


# --------------------------------------------------------------------------- embedding cache
def digest(*parts: str) -> str:
    return hashlib.sha1("\x00".join(parts).encode()).hexdigest()


class EmbedStore:
    """Directory of ``{side}.npy`` (float16 [n, hidden]) + ``{side}.keys.json`` (digest per row)."""

    def __init__(self, path: str, encoder_id: str):
        self.path, self.encoder_id = path, encoder_id
        os.makedirs(path, exist_ok=True)
        self.rows, self.keys = {}, {}
        for side in ("state", "action"):
            kf, vf = self._files(side)
            if os.path.exists(kf) and os.path.exists(vf):
                self.keys[side] = json.load(open(kf))
                self.rows[side] = np.load(vf)
            else:
                self.keys[side], self.rows[side] = [], np.zeros((0, 0), np.float16)

    def _files(self, side: str) -> tuple[str, str]:
        return os.path.join(self.path, f"{side}.keys.json"), os.path.join(self.path, f"{side}.npy")

    def _save(self, side: str) -> None:
        kf, vf = self._files(side)
        json.dump(self.keys[side], open(kf, "w"))
        np.save(vf, self.rows[side])

    def get(self, side: str, texts: list[str], encode, chunk: int = 256, log=print) -> np.ndarray:
        """-> [n, hidden] float16; ``encode(list[str]) -> [k, hidden]`` runs only for texts not yet stored."""
        ks = [digest(self.encoder_id, side, t) for t in texts]
        index = {k: i for i, k in enumerate(self.keys[side])}
        todo = list(dict.fromkeys(i for i, k in enumerate(ks) if k not in index))
        todo_texts = list(dict.fromkeys(texts[i] for i in todo))
        for a in range(0, len(todo_texts), chunk):
            part = todo_texts[a:a + chunk]
            v = np.asarray(encode(part), np.float16)
            if self.rows[side].shape[1] == 0:
                self.rows[side] = np.zeros((0, v.shape[1]), np.float16)
            self.rows[side] = np.concatenate([self.rows[side], v])
            self.keys[side] += [digest(self.encoder_id, side, t) for t in part]
            self._save(side)
            log(f"[embed] {side} {min(a + chunk, len(todo_texts))}/{len(todo_texts)} new")
        index = {k: i for i, k in enumerate(self.keys[side])}
        return self.rows[side][[index[k] for k in ks]]


# --------------------------------------------------------------------------- data
@dataclass
class Data:
    S: np.ndarray                 # [n, hidden] float16 state embeddings
    A: np.ndarray                 # [m, hidden] float16 unique action embeddings
    gold: np.ndarray              # [n] index into A
    negs: list                    # per state: int array of indices into A (never the gold)
    group: np.ndarray             # [n] group code of (task_id, step_idx)
    task: list = field(default_factory=list)

    def __len__(self) -> int:
        return len(self.gold)

    def subset(self, rows) -> "Data":
        rows = np.asarray(rows)
        return Data(self.S[rows], self.A, self.gold[rows], [self.negs[i] for i in rows], self.group[rows],
                    [self.task[i] for i in rows])


def read_jsonl(path: str) -> list[dict]:
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]


def build(records: list[dict], store: EmbedStore, encode_state, encode_action) -> Data:
    """Embed (through ``store``) and index a list of records."""
    acts: dict[str, int] = {}
    for r in records:
        for t in [r["action"], *r.get("negatives", [])]:
            acts.setdefault(t, len(acts))
    S = store.get("state", [r["state"] for r in records], encode_state)
    A = store.get("action", list(acts), encode_action)
    groups: dict = {}
    group = np.array([groups.setdefault((r.get("task_id", i), r.get("step_idx", i)), len(groups))
                      for i, r in enumerate(records)])
    gold = np.array([acts[r["action"]] for r in records])
    negs = [np.array([acts[t] for t in dict.fromkeys(r.get("negatives", [])) if t != r["action"]], dtype=int)
            for r in records]
    return Data(S, A, gold, negs, group, [r.get("task_id", i) for i, r in enumerate(records)])


def split_tasks(data: Data, val_frac: float, seed: int) -> tuple[Data, Data]:
    """Task-disjoint split like the reference: whole tasks go to validation."""
    tasks = sorted(set(map(str, data.task)))
    rng = np.random.default_rng(seed)
    rng.shuffle(tasks)
    nv = min(len(tasks) - 1, max(1, round(val_frac * len(tasks))))
    val = set(tasks[:nv])
    isval = np.array([str(t) in val for t in data.task])
    return data.subset(np.where(~isval)[0]), data.subset(np.where(isval)[0])


# --------------------------------------------------------------------------- model + loss
class Heads(nn.Module):
    def __init__(self, cfg: dict, logit_scale: float):
        super().__init__()
        self.state_head, self.action_head = Head(**cfg), Head(**cfg)
        self.logit_scale = mx.array(logit_scale, dtype=mx.float32)


def normalize(z):
    return z / (mx.linalg.norm(z, axis=-1, keepdims=True) + 1e-12)


def exclusion(group: np.ndarray, gold_pos: np.ndarray, width: int) -> np.ndarray:
    """[n, width] True where pool item p must not count as a negative for state i: p is the gold action
    of another state in i's group (same task and step) and not i's own gold."""
    gp = np.zeros((group.max() + 1, width), bool)
    gp[group, gold_pos] = True
    ex = gp[group]
    ex[np.arange(len(group)), gold_pos] = False
    return ex


def batch_arrays(d: Data, idx: np.ndarray):
    """-> (states [B,h], pool [P,h], gold position per state, forward mask [B,P], backward mask [B,B])."""
    pool_ids = np.unique(np.concatenate([d.gold[idx], *(d.negs[i] for i in idx)]))
    where = {int(a): p for p, a in enumerate(pool_ids)}
    pos = np.array([where[int(a)] for a in d.gold[idx]])
    g = d.group[idx]
    ex_f = exclusion(np.unique(g, return_inverse=True)[1], pos, len(pool_ids))
    # a state's own negatives are never masked; a pool item that is another state's gold is masked only by group
    same = (g[:, None] == g[None, :]) | (d.gold[idx][:, None] == d.gold[idx][None, :])
    ex_b = same & ~np.eye(len(idx), dtype=bool)
    # a duplicate gold shares its pool position: the forward target stays valid, only duplicates in B x B are masked
    return d.S[idx], d.A[pool_ids], pos, ex_f, ex_b


def loss_fn(heads: Heads, s, a, pos, ex_f, ex_b):
    zs, za = normalize(heads.state_head(s)), normalize(heads.action_head(a))
    logits = mx.minimum(mx.exp(heads.logit_scale), 100.0) * (zs @ za.T)                  # [B, P]
    fwd = nn.losses.cross_entropy(mx.where(ex_f, NEG, logits), pos, reduction="mean")
    gold_cols = mx.take(logits, pos, axis=1)                                             # [B, B]: state i vs gold k
    bwd = nn.losses.cross_entropy(mx.where(ex_b, NEG, gold_cols.T), mx.arange(len(pos)), reduction="mean")
    return (fwd + bwd) / 2


def _project(head: Head, x: np.ndarray, chunk: int = 4096) -> np.ndarray:
    out = [normalize(head(mx.array(x[a:a + chunk]).astype(mx.float32))) for a in range(0, len(x), chunk)]
    z = mx.concatenate(out) if out else mx.zeros((0, 1))
    mx.eval(z)
    return np.array(z)


def evaluate(heads: Heads, d: Data, batch: int = 512) -> dict:
    """val_loss (in-batch), top1 (gold first among the split's distinct gold actions), hard_top1 (gold first
    among its own negatives, rows that have some), mrr over the gold pool."""
    heads.eval()
    n = len(d)
    losses = []
    for a in range(0, n, batch):
        idx = np.arange(a, min(a + batch, n))
        if len(idx) >= 2:
            losses.append(_eval_loss(heads, d, idx))
    zs = _project(heads.state_head, d.S)
    pool_ids = np.unique(d.gold)
    za = _project(heads.action_head, d.A[pool_ids])
    pos = np.searchsorted(pool_ids, d.gold)
    sc = zs @ za.T
    sc = np.where(exclusion(d.group, pos, len(pool_ids)), -np.inf, sc)
    own = sc[np.arange(n), pos]
    rank = (sc > own[:, None]).sum(1)
    hard = []
    zall = _project(heads.action_head, d.A)
    for i in range(n):
        if len(d.negs[i]):
            c = zall[d.negs[i]] @ zs[i]
            hard.append(float(np.all(zall[d.gold[i]] @ zs[i] > c)))
    heads.train()
    return {"val_loss": float(np.mean(losses)) if losses else float("nan"), "top1": float((rank == 0).mean()),
            "mrr": float((1 / (rank + 1)).mean()), "hard_top1": float(np.mean(hard)) if hard else None,
            "n_val": n, "pool": len(pool_ids)}


def _eval_loss(heads, d, idx) -> float:
    s, a, pos, ex_f, ex_b = batch_arrays(d, idx)
    return float(loss_fn(heads, mx.array(s).astype(mx.float32), mx.array(a).astype(mx.float32),
                         mx.array(pos), mx.array(ex_f), mx.array(ex_b)).item())


# --------------------------------------------------------------------------- checkpoints
def load_init(path: str) -> tuple[Heads, dict]:
    """A converted ``.safetensors`` checkpoint -> (Heads with its weights and logit scale, cfg dict)."""
    from safetensors import safe_open
    with safe_open(path, framework="numpy") as f:
        meta = json.loads(f.metadata()["clm"])
        w = {k: mx.array(f.get_tensor(k)) for k in f.keys()}
    cfg = config_of(meta)
    heads = Heads(cfg, meta["logit_scale"])
    for side in ("state", "action"):
        getattr(heads, side + "_head").load_weights([(k[len(side) + 1:], v) for k, v in w.items() if k.startswith(side + ".")])
    return heads, {"cfg": meta["cfg"], "cfg_head": cfg, "projection_dim": cfg["proj"]}


def save(path: str, heads: Heads, info: dict, metrics: dict | None = None) -> str:
    """Write the ``convert.py`` layout atomically; ``HeadPair`` reloads on the new mtime."""
    from safetensors.numpy import save_file
    t = {f"{side}.{k}": np.array(v.astype(mx.float32))
         for side in ("state", "action") for k, v in tree_flatten(getattr(heads, side + "_head").parameters())}
    meta = {"cfg": info["cfg"], "projection_dim": info["projection_dim"], "logit_scale": float(heads.logit_scale.item()),
            "source": "mlx_finetune", "metrics": metrics or {}}
    tmp = path + ".tmp"
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    save_file(t, tmp, metadata={"clm": json.dumps(meta, default=str)})
    os.replace(tmp, path)
    return path


# --------------------------------------------------------------------------- training
def one_cycle(lr: float, total: int, pct: float = 0.1):
    warm = max(1, int(total * pct))
    start = lr / 25
    return optim.join_schedules([optim.linear_schedule(start, lr, warm),
                                 optim.cosine_decay(lr, max(1, total - warm), start / 1e4)], [warm])


def train(train_d: Data, val_d: Data, init: str, out: str | None = None, *, epochs: int = 20, batch: int = 512,
          lr: float | None = None, patience: int = 5, select: str = "top1", seed: int = 1234, log=print) -> dict:
    mx.random.seed(seed)
    heads, info = load_init(init)
    width = info["cfg_head"]["width"]
    batch = min(batch, len(train_d))
    lr = lr or 2e-3 * math.sqrt(1024 / width) * math.sqrt(batch / 1024)
    rng = np.random.default_rng(seed)
    per_epoch = len(train_d) // batch + (1 if len(train_d) % batch >= 2 else 0)
    opt = optim.AdamW(learning_rate=one_cycle(lr, per_epoch * epochs), weight_decay=0.0)
    step = nn.value_and_grad(heads, loss_fn)

    def score(m):
        return -m["val_loss"] if select == "val_loss" else m["top1"]

    m0 = evaluate(heads, val_d)
    best, best_epoch, stale, hist = score(m0), 0, 0, [{"epoch": 0, **m0}]
    log(f"[train] epoch 0 {json.dumps(m0)}")
    best_w = heads.parameters()
    best_w = {k: mx.array(v) for k, v in tree_flatten(best_w)}
    t0 = time.time()
    for epoch in range(1, epochs + 1):
        order = rng.permutation(len(train_d))
        tot, nb = 0.0, 0
        for a in range(0, len(order), batch):
            idx = order[a:a + batch]
            if len(idx) < 2:
                continue
            s, p, pos, ex_f, ex_b = batch_arrays(train_d, idx)
            loss, grads = step(heads, mx.array(s).astype(mx.float32), mx.array(p).astype(mx.float32),
                               mx.array(pos), mx.array(ex_f), mx.array(ex_b))
            grads, _ = optim.clip_grad_norm(grads, 1.0)
            opt.update(heads, grads)
            mx.eval(heads.parameters(), opt.state)
            tot += loss.item(); nb += 1
        m = evaluate(heads, val_d)
        m.update(epoch=epoch, train_loss=tot / max(nb, 1), seconds=round(time.time() - t0, 1),
                 logit_scale=float(heads.logit_scale.item()))
        hist.append(m)
        log(f"[train] epoch {epoch} {json.dumps(m)}")
        if score(m) > best + 1e-4:
            best, best_epoch, stale = score(m), epoch, 0
            best_w = {k: mx.array(v) for k, v in tree_flatten(heads.parameters())}
        else:
            stale += 1
            if stale >= patience:
                log(f"[train] early stop at epoch {epoch} (best {best_epoch})")
                break
    heads.load_weights(list(best_w.items()))
    final = evaluate(heads, val_d)
    summary = {"best_epoch": best_epoch, "select": select, "lr": lr, "batch": batch, "n_train": len(train_d),
               "best": final, "history": hist}
    if out:
        save(out, heads, info, final)
        summary["checkpoint"] = out
    return summary
