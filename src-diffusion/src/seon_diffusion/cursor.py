"""Typeahead driver FSM — the P2 half of typeahead-design.md.

The model is stateless; this driver is the machine. The caller supplies
the context RENDER (a string — seon owns deriving it db-side in P3) and
the driver re-encodes it every step: encoder prefill measured ~5ms for
4k tokens, free next to a ~114ms decode forward.

States (the design table):

  RENDER    encode the context render (+ committed text) into a fresh cache
  DENOISE   masked denoise rounds to stability (control._denoise_round,
            with additive logit masks — the slot-masking seam)
  INTERPRET a TOTAL partition of the round output into
            {glyph-select, text-progress, clean-unfinished, broken,
             eos-complete, stuck}; anything unmatched falls to the
            text-progress DEFAULT arm, which behaves exactly like the
            existing guided loop (lock the clean prefix, keep the rest) —
            the always-works guarantee.
  EXPAND    a selection glyph — emitted, or the calibrated-posterior
            auto-offer — clamps the offer's template segments and fills
            the holes (free holes, pad-clamped tail: the dg_typeahead
            pattern)
  GROW      clean-but-unfinished (or ⤵) → keep the text, grant space;
            the mechanical GROW is the guarantee, ⤵ just saves a round
  REPAIR/SCRAMBLE  broken spans → keep the clean head + tail, drop the
            broken region, return `; fix:` hints for the next render
  LOCK/HARVEST     the maximal parse-clean prefix (eval-proven when an
            eval session is supplied) moves to `locked` — the caller
            appends it to `committed` for the next step
  PROVE/DONE       EOS + everything clean + nothing pending

Glyphs live in TOKEN space only: single-token ids derived from the
tokenizer at init (never hardcoded), scanned in the free region, and
STRIPPED before any text reaches the oracle or committed output.

Statelessness: `step` is one full FSM turn per call — no session or KV
survives between calls in P2 (re-encode is ~free, measured). Hints are
returned in the result (`hints` + events), not clamped into `new_draft`;
the caller decides their placement in the next render.
"""

import re
import time
from dataclasses import dataclass, replace

import mlx.core as mx

from . import control
from .control import _Workspace, _tok_ids
from .generate import GenConfig, _entropy
from .repair import (hint_for as _hint_for, orient_for,
                     strip_hints as _strip_hints)

SELECT_GLYPHS = tuple("①②③④⑤⑥⑦⑧⑨⑩")   # model → driver: select offer N
GROW_GLYPH = "⤵"                            # model → driver: more space now
NEG = -1e9                                   # additive logit ban


@dataclass
class Policy:
    """Driver policy — the design defaults. Thresholds are the measured
    ones (typeahead-hole-filling research, rounds 4–6):
    on-menu glyph margins ≈ 10 nats, off-menu collapse −22…−28 (so a
    calibrated 6-nat margin separates them); determined slots show
    worst-token entropy 0.03–0.57 nats vs 1.3–3.0 underdetermined."""

    auto_offer_margin: float = 6.0   # calibrated top-vs-rest glyph margin (nats)
    worst_entropy_gate: float = 1.0  # auto-accept gates on WORST token, not mean
    settle_rounds: int = 2           # extra per-hole rounds: settled holes CLAMP,
                                     # only unsettled holes re-noise (per-field lock)
    expand_settle_rounds: int = 1    # step-mode expansions only (P6, live-measured:
                                     # re-noising an unsettled offer-args hole rescued
                                     # 0/2 at ~2x the forwards; the step loop's own
                                     # retry/suppress is the cheaper recovery)
    probe_lengths: int = 3           # CAL hole-length probe: K candidates (1 = off)
    probe_delta: int = 4             # candidate lengths n, n±Δ, n±2Δ, …
    ban_special: bool = True         # ban special/channel tokens at ALL free positions
    ban_eos_in_holes: bool = True    # ban EOS/pad inside template holes (open tail keeps EOS)
    candidate_masks: bool = True     # DINGO-lite per-hole allowed-token masks
    min_overlap: int = 2             # suffix-echo overlap-trim threshold (chars)
    settle_steps: int = 3            # rank-mode settle forwards
    grow_free: int = 32              # minimum free tokens granted per step
    max_rounds: int = 8              # drive() round budget
    max_attempts: int = 3            # drive() attempt budget
    glyph_page_size: int = 10        # offers per menu page (①–⑩)


# ---------------------------------------------------------------------------
# pure helpers (unit-testable without a model)
# ---------------------------------------------------------------------------

def norm_segments(segments):
    """JSON/wire segments → [("clamp", str) | ("free", int)]."""
    out = []
    for s in segments:
        kind, payload = s[0], s[1]
        if kind == "clamp":
            out.append(("clamp", str(payload)))
        elif kind == "free":
            out.append(("free", int(payload)))
        else:
            raise ValueError(f"unknown segment kind {kind!r}")
    return out


def glyph_ids(tok, glyphs=SELECT_GLYPHS + (GROW_GLYPH,)):
    """glyph → token id, from the tokenizer at init — never hardcoded.
    Glyphs that do not encode to exactly ONE token are dropped (the
    protocol only works for single-token glyphs)."""
    out = {}
    for g in glyphs:
        ids = _tok_ids(tok, g)
        if len(ids) == 1:
            out[g] = ids[0]
    return out


def special_token_ids(tok, vocab_size):
    """Special/channel token ids to ban at free positions: the tokenizer's
    declared specials plus anything shaped like a scaffold token
    (`<...>` — fixes the measured `<|channel>thought` leak)."""
    ids = set(getattr(tok, "all_special_ids", None) or [])
    try:
        vocab = tok.get_vocab() if hasattr(tok, "get_vocab") else {}
    except Exception:
        vocab = {}
    for t, i in vocab.items():
        if len(t) > 2 and t[0] == "<" and t[-1] == ">":
            ids.add(i)
    return sorted(i for i in ids if 0 <= i < vocab_size)


def ban_vector(vocab_size, ids):
    """Additive [vocab] mask: NEG at `ids`, 0 elsewhere (None if empty)."""
    ids = sorted(set(ids))
    if not ids:
        return None
    v = mx.zeros((vocab_size,))
    v[mx.array(ids)] = NEG
    return v


def allow_vector(vocab_size, tok, candidates):
    """DINGO-lite hole mask: 0 at every token id of any candidate string,
    NEG elsewhere — free generation in that hole cannot leave the set."""
    allowed = set()
    for c in candidates:
        allowed.update(_tok_ids(tok, c))
    v = mx.full((vocab_size,), NEG)
    if allowed:
        v[mx.array(sorted(allowed))] = 0.0
    return v


def make_bias(free_mask, ban_vec, hole_vecs):
    """Compose the additive logit bias applied each forward.
    free_mask: [1,CL] bool of NON-clamped positions; ban_vec: [V] or None;
    hole_vecs: [(pos_mask[1,CL] bool, vec[V])] per masked hole.
    Returns a callable(logits)->logits, or None when there is nothing to do."""
    if ban_vec is None and not hole_vecs:
        return None

    def bias(logits):
        if ban_vec is not None:
            logits = logits + free_mask[..., None] * ban_vec
        for pm, vec in hole_vecs:
            logits = logits + pm[..., None] * vec
        return logits
    return bias


_PARTIAL_SYM = re.compile(r"[A-Za-z0-9_.*+!?<>=/-]+$")


def split_partial_symbol(draft):
    """(clamp_text, partial): back the frontier clamp off a trailing
    partial symbol so the model can rewrite the symbol WHOLE. Only a
    tail that sits mid-symbol is split — a draft ending in whitespace,
    a delimiter, or a complete string stays fully clamped."""
    if not draft or draft[-1] in " \n\t)]}\"":
        return draft, ""
    m = _PARTIAL_SYM.search(draft)
    if not m:
        return draft, ""
    return draft[: m.start()], m.group(0)


def overlap_trim(hole_text, next_clamp, min_overlap=2):
    """Suffix-echo mitigation: trim the longest hole-TAIL that duplicates
    the following clamp's PREFIX (the model regenerating the transition —
    measured; no published inference-time fix exists, this is ours).
    Returns (trimmed_text, overlap_chars)."""
    lim = min(len(hole_text), len(next_clamp))
    for k in range(lim, max(min_overlap, 1) - 1, -1):
        if hole_text[-k:] == next_clamp[:k]:
            return hole_text[:-k], k
    return hole_text, 0


def calibrate(posteriors, baseline):
    """Null-intent baseline subtraction (the measured position-bias
    mitigation: first-slot inflation −0.0 vs −6.4 margins)."""
    return {g: p - baseline.get(g, 0.0) for g, p in posteriors.items()}


def posterior_margin(calibrated):
    """Top-vs-second margin (nats); a single entry's margin is its own
    calibrated lift over the null baseline."""
    if not calibrated:
        return None
    vals = sorted(calibrated.values(), reverse=True)
    return vals[0] - vals[1] if len(vals) > 1 else vals[0]


# ---------------------------------------------------------------------------
# the driver
# ---------------------------------------------------------------------------

class CursorDriver:
    """One model + tokenizer + bb oracle, many stateless steps/fills/ranks."""

    def __init__(self, model, tok, oracle, policy=None, gen=None):
        self.model = model
        self.tok = tok
        self.oracle = oracle
        self.policy = policy or Policy()
        self.gen = gen or GenConfig(entropy_bound=0.5)
        self.glyphs = glyph_ids(tok)                       # glyph -> id
        self.id2glyph = {i: g for g, i in self.glyphs.items()}
        self.select_ids = {i for g, i in self.glyphs.items() if g != GROW_GLYPH}
        self.grow_id = self.glyphs.get(GROW_GLYPH)
        self._specials = special_token_ids(tok, model.cfg.vocab_size)
        self._baselines = {}                               # null-render cache

    # ---- encode ----------------------------------------------------------

    def _prompt_ids(self, prompt):
        tok = self.tok
        if hasattr(tok, "apply_chat_template"):
            enc = tok.apply_chat_template(
                [{"role": "user", "content": prompt}],
                tokenize=True, add_generation_prompt=True)
            ids = enc["input_ids"] if hasattr(enc, "keys") else enc
            if ids and isinstance(ids[0], list):
                ids = ids[0]
            return ids
        return _tok_ids(tok, prompt)

    def _encode(self, prompt, committed=""):
        """RENDER: fresh cache ← context render (+ committed text)."""
        ids = self._prompt_ids(prompt)
        cache = self.model.new_cache()
        self.model.encode(mx.array(ids)[None, :], cache, past_len=0)
        mx.eval(cache[0]["k"])
        cur_len = len(ids)
        if committed:
            ctext = committed if committed.endswith("\n") else committed + "\n"
            cids = _tok_ids(self.tok, ctext)
            self.model.encode(mx.array(cids)[None, :], cache, past_len=cur_len)
            mx.eval(cache[0]["k"])
            cur_len += len(cids)
        return cache, cur_len

    # ---- masks -----------------------------------------------------------

    def _hole_ban_ids(self):
        ids = list(self._specials) if self.policy.ban_special else []
        if self.policy.ban_eos_in_holes:
            ids += list(self.gen.eos_token_ids) + [self.gen.pad_token_id]
        return ids

    def _open_ban_ids(self):
        """Step-mode (open tail) ban: special/channel tokens WITHOUT the
        EOS/pad ids — the tokenizer declares EOS special, but the open
        tail needs it legal (the done-ness meter + the eos-complete arm;
        banning it live produced eos_logprob_tail = -1e9 and trailing
        prose junk)."""
        keep = set(self.gen.eos_token_ids) | {self.gen.pad_token_id}
        return [i for i in self._specials if i not in keep]

    def _fill_bias(self, ws, clamp_mask, candidates):
        """Template-fill bias: EOS/pad/special banned in holes, plus
        DINGO-lite allowed-token masks for holes with candidate sets."""
        vs = self.model.cfg.vocab_size
        free = ~clamp_mask
        ban = ban_vector(vs, self._hole_ban_ids())
        hole_vecs = []
        if candidates and self.policy.candidate_masks:
            spans = ws.hole_spans()
            for hi, cands in _cand_items(candidates):
                if hi < len(spans) and cands:
                    a, b = spans[hi]
                    pm = mx.array([[a <= j < b for j in range(clamp_mask.shape[1])]])
                    hole_vecs.append((pm, allow_vector(vs, self.tok, cands)))
        return make_bias(free, ban, hole_vecs)

    # ---- CAL hole-length probe --------------------------------------------

    def _first_step_confidence(self, cache, cur_len, segments, hole_idx):
        """Φ(L): average first-step denoise confidence (mean max-prob) over
        the hole — CAL's signal: it peaks near the true content length."""
        ws = self._workspace(segments)
        code_buffer, clamp_mask, clamp_ids, _ = ws.build(
            self.model.cfg.code_buffer_length, self.model.cfg.vocab_size,
            pad_clamp_id=self.gen.pad_token_id)
        a, b = ws.hole_spans()[hole_idx]
        logits = self.model.decode(code_buffer, cache, code_buffer_start=cur_len)
        lp = logits - mx.logsumexp(logits, axis=-1, keepdims=True)
        conf = mx.mean(mx.exp(mx.max(lp[0, a:b, :], axis=-1)))
        mx.eval(conf)
        return float(conf)

    def _probe_hole_lengths(self, cache, cur_len, segments, skip=()):
        """CAL: for each hole, probe K candidate lengths (n, n±Δ, …), one
        forward each, pick the confidence peak. Holes in `skip` (0-based
        hole index) are left alone. Returns (segments, probes, forwards).

        Honesty note (live-measured): the uncalibrated Φ(L) reproduced
        the CAL peak for an open text hole but was MONOTONE-increasing
        for an enum hole (slack junk followed); the paper's length-decay
        bias B(L) is not fitted here. Closed slots should carry candidate
        sets instead — they are sized by the candidates, not probed."""
        p = self.policy
        if p.probe_lengths <= 1:
            return segments, [], 0
        segments = list(segments)
        probes, forwards = [], 0
        hole_at = [i for i, s in enumerate(segments) if s[0] == "free"]
        for hn, si in enumerate(hole_at):
            if hn in skip:
                continue
            n = segments[si][1]
            # P6 ladder: geometric DOWN alternated with +Δ up. Live-measured
            # on the offer-args hole: Φ(20) > Φ(24) > Φ(28) — the true
            # content is far shorter than the template default, and the old
            # ±Δ ladder never reached short lengths; the slack is what
            # invites the echo/junk. Halving reaches short lengths in
            # O(log n) probes; the +Δ arm keeps the upward direction open.
            lengths, down, d = [n], n, p.probe_delta
            while len(lengths) < p.probe_lengths:
                down = max(2, down // 2)
                if down not in lengths:
                    lengths.append(down)
                if len(lengths) < p.probe_lengths:
                    hi = n + d
                    if hi not in lengths:
                        lengths.append(hi)
                    d += p.probe_delta
            scores = {}
            for L in sorted(lengths):
                trial = list(segments)
                trial[si] = ("free", L)
                scores[L] = self._first_step_confidence(cache, cur_len, trial, hn)
                forwards += 1
            best = max(scores, key=scores.get)
            segments[si] = ("free", best)
            probes.append({"hole": hn, "scores": {k: round(v, 4) for k, v in scores.items()},
                           "chosen": best})
        return segments, probes, forwards

    # ---- fill -------------------------------------------------------------

    def _workspace(self, segments):
        ws = _Workspace()
        for kind, payload in segments:
            if kind == "clamp":
                ws.clamp(_tok_ids(self.tok, payload))
            else:
                ws.free(payload)
        return ws

    def _fill_on(self, cache, cur_len, segments, candidates=None,
                 settle_rounds=None):
        """Fill template holes on an existing cache: CAL length probe →
        masked denoise → per-hole readouts → suffix-echo overlap-trim.
        `settle_rounds` overrides the policy default (the step-mode
        expansion regime)."""
        p = self.policy
        if settle_rounds is not None:
            p = replace(p, settle_rounds=settle_rounds)
        cfg = self.model.cfg
        segments = norm_segments(segments)
        # a hole with a candidate set is CLOSED: size it to its longest
        # candidate (kills the slack that invites the echo/repeat junk —
        # the dg_typeahead2 rank recipe) and skip the CAL probe for it
        sized = set()
        hole_at = [i for i, s in enumerate(segments) if s[0] == "free"]
        for hi, cands in _cand_items(candidates):
            if cands and hi < len(hole_at):
                n = max(len(_tok_ids(self.tok, c)) for c in cands)
                segments[hole_at[hi]] = ("free", n)
                sized.add(hi)
        segments, probes, probe_fwd = self._probe_hole_lengths(
            cache, cur_len, segments, skip=sized)
        cand_map = dict(_cand_items(candidates))
        hole_seg = [i for i, s in enumerate(segments) if s[0] == "free"]
        n_holes = len(hole_seg)
        # per-ORIGINAL-hole state; settled holes CLAMP on later rounds and
        # only unsettled ones re-noise (per-FIELD locking — the fields of a
        # template converge independently, measured entropy gap ~10×)
        state = [{"text": None, "mean": None, "worst": None, "trim": 0,
                  "accepted": False, "snapped": False, "round": None}
                 for _ in range(n_holes)]
        total_fwd = probe_fwd
        overflow = False
        rounds = 1 + max(int(p.settle_rounds), 0)
        denoised_rounds = 0
        for rnd in range(rounds):
            open_holes = [h for h in range(n_holes) if not state[h]["accepted"]]
            if not open_holes:
                break
            denoised_rounds += 1
            # current segments: settled hole → clamp(text); open hole → free
            cur_segments, cur_cand, order = [], {}, []
            for i, (kind, payload) in enumerate(segments):
                if kind == "clamp":
                    cur_segments.append((kind, payload))
                    continue
                h = hole_seg.index(i)
                st = state[h]
                if st["accepted"]:
                    cur_segments.append(("clamp", st["text"]))
                else:
                    if cand_map.get(h):
                        cur_cand[len(order)] = cand_map[h]
                    order.append((h, i))
                    cur_segments.append(("free", payload))
            ws = self._workspace(cur_segments)
            code_buffer, clamp_mask, clamp_ids, ovf = ws.build(
                cfg.code_buffer_length, cfg.vocab_size,
                pad_clamp_id=self.gen.pad_token_id)
            overflow = overflow or ovf
            bias = self._fill_bias(ws, clamp_mask, cur_cand)
            # hole-stability early stop (P6, live-measured: one fill round
            # burned the full 48-step budget while the hole belief had long
            # settled — the round's whole-code_buffer stop criterion is dominated
            # by clamped-position uncertainty the fill can't reduce). Only
            # the hole positions are being generated: when their belief is
            # unchanged across two consecutive probes, further forwards are
            # pure heat.
            hole_positions = [j for a, b in ws.hole_spans()
                              for j in range(a, min(b, cfg.code_buffer_length))]
            snap = {"prev": None, "hits": 0}

            def holes_stable(belief_ids, _snap=snap, _pos=hole_positions):
                cur = tuple(int(belief_ids[0, j]) for j in _pos)
                _snap["hits"] = _snap["hits"] + 1 if cur == _snap["prev"] else 0
                _snap["prev"] = cur
                return _snap["hits"] >= 1

            belief, fwd, logits = control._denoise_round(
                self.model, code_buffer, clamp_mask, clamp_ids, cache, cur_len,
                self.gen, bias=bias, probe=holes_stable)
            total_fwd += fwd
            ent = _entropy(logits)
            toks = [int(t) for t in belief[0]]
            spans = ws.hole_spans()
            lp = None
            if cur_cand:
                lp = logits - mx.logsumexp(logits, axis=-1, keepdims=True)
                mx.eval(lp)
            last = rnd == rounds - 1
            for fi, (h, i) in enumerate(order):
                a, b = spans[fi]
                a, b = min(a, cfg.code_buffer_length), min(b, cfg.code_buffer_length)
                if b <= a:
                    # overflow truncated this hole off the code_buffer entirely —
                    # honest empty, never accepted (the caller sees
                    # overflow=True + accepted=False, not a crash)
                    state[h].update({"text": "", "mean": None, "worst": None,
                                     "trim": 0, "snapped": False})
                    if last:
                        state[h]["round"] = rnd
                    continue
                snapped = None
                if cand_map.get(h):
                    # closed slot: SNAP to the highest-probability candidate
                    # string under the final logits (the token mask alone
                    # only keeps the fill in-class — measured: "openopen").
                    best, best_s = None, None
                    for c in cand_map[h]:
                        ids = _tok_ids(self.tok, c)[: b - a]
                        s = sum(float(lp[0, a + j, t])
                                for j, t in enumerate(ids)) / max(len(ids), 1)
                        if best_s is None or s > best_s:
                            best, best_s = c, s
                    snapped = best
                text = snapped if snapped is not None else self.tok.decode(
                    [t for t in toks[a:b] if t != self.gen.pad_token_id]).strip()
                next_clamp = next(
                    (s[1] for s in segments[i + 1:] if s[0] == "clamp"), "")
                text, k = (text, 0) if snapped is not None else \
                    overlap_trim(text, next_clamp, p.min_overlap)
                h_ent = [float(ent[0, j]) for j in range(a, b)]
                worst = max(h_ent) if h_ent else 0.0
                mean = sum(h_ent) / len(h_ent) if h_ent else 0.0
                settled = snapped is not None or worst < p.worst_entropy_gate
                state[h].update(
                    {"text": text, "mean": round(mean, 3),
                     "worst": round(worst, 3), "trim": k,
                     "snapped": snapped is not None})
                if settled and not state[h]["accepted"]:
                    state[h]["accepted"] = True
                    state[h]["round"] = rnd
                elif last:
                    state[h]["round"] = rnd    # best effort, honestly unaccepted

        holes = [st["text"] for st in state]
        trims = [st["trim"] for st in state]
        stats = [{"mean": st["mean"], "worst": st["worst"],
                  "accepted": st["accepted"], "round": st["round"],
                  **({"snapped": True} if st["snapped"] else {})}
                 for st in state]
        pieces, hi = [], 0
        for kind, payload in segments:
            if kind == "clamp":
                pieces.append(payload)
            else:
                pieces.append(state[hi]["text"] or "")
                hi += 1
        return {"holes": holes, "hole_confidence": stats, "trims": trims,
                "probes": probes, "text": _strip_hints("".join(pieces)),
                "overflow": overflow, "forwards": total_fwd,
                "settle_rounds_used": denoised_rounds}

    def fill(self, prompt, segments, candidates=None, seed=None):
        """mode=fill: template segments in → hole texts + per-hole
        worst-token confidence + trims applied. Perf in tokens/second."""
        if seed is not None:
            mx.random.seed(seed)
        t0 = time.time()
        cache, cur_len = self._encode(prompt)
        r = self._fill_on(cache, cur_len, segments, candidates=candidates)
        dt = time.time() - t0
        n_tok = sum(len(_tok_ids(self.tok, h)) for h in r["holes"])
        r.update({"gen_s": round(dt, 3),
                  "tok_per_s": round(n_tok / dt, 1) if dt > 0 else 0.0})
        return r

    # ---- rank -------------------------------------------------------------

    def _score_slot(self, prompt, prefix, candidates, suffix):
        """Mean logprob of each candidate's tokens at the slot positions
        after `settle_steps` self-conditioning forwards (the measured
        ranked-menu recipe: ~3 forwards, ~0.5s)."""
        cache, cur_len = self._encode(prompt)
        cand_ids = [_tok_ids(self.tok, c) for c in candidates]
        hole_n = max(len(c) for c in cand_ids)
        segments = [("clamp", prefix), ("free", hole_n), ("clamp", suffix)]
        ws = self._workspace(segments)
        cfg = self.model.cfg
        code_buffer, clamp_mask, clamp_ids, _ = ws.build(
            cfg.code_buffer_length, cfg.vocab_size, pad_clamp_id=self.gen.pad_token_id)
        a, _b = ws.hole_spans()[0]
        settle = replace(self.gen, max_denoising_steps=self.policy.settle_steps,
                         stability_threshold=2, confidence_threshold=-1.0)
        _, fwd, logits = control._denoise_round(
            self.model, code_buffer, clamp_mask, clamp_ids, cache, cur_len, settle)
        lp = logits - mx.logsumexp(logits, axis=-1, keepdims=True)
        mx.eval(lp)
        scores = {}
        for c, ids in zip(candidates, cand_ids):
            scores[c] = sum(float(lp[0, a + i, t]) for i, t in enumerate(ids)) / len(ids)
        return scores, fwd

    def rank(self, prompt, prefix, candidates, suffix, null_prompt=None, seed=None):
        """mode=rank: calibrated ranked candidate list. Calibration =
        null-intent baseline subtraction when `null_prompt` is supplied
        (score each candidate under a content-free prompt, subtract) —
        the same position-bias mitigation as the glyph posteriors."""
        if seed is not None:
            mx.random.seed(seed)
        t0 = time.time()
        raw, fwd = self._score_slot(prompt, prefix, candidates, suffix)
        baseline = {}
        if null_prompt:
            baseline, bfwd = self._score_slot(null_prompt, prefix, candidates, suffix)
            fwd += bfwd
        cal = calibrate(raw, baseline)
        ranked = sorted(cal, key=cal.get, reverse=True)
        dt = time.time() - t0
        n_tok = sum(len(_tok_ids(self.tok, c)) for c in candidates)
        return {"ranked": [{"candidate": c,
                            "score": round(cal[c], 3),
                            "raw": round(raw[c], 3)} for c in ranked],
                "calibrated": bool(null_prompt),
                "forwards": fwd, "gen_s": round(dt, 3),
                "tok_per_s": round(n_tok / dt, 1) if dt > 0 else 0.0}

    # ---- glyph posterior + calibration -------------------------------------

    def _glyph_baseline(self, null_render, offer_glyphs):
        """Baseline glyph logprobs at the cursor under a caller-supplied
        NULL-intent render — measured once per (render, menu), cached."""
        key = (null_render, tuple(sorted(offer_glyphs)))
        if key not in self._baselines:
            cache, cur_len = self._encode(null_render)
            cfg = self.model.cfg
            ws = _Workspace()
            ws.free(cfg.code_buffer_length)
            code_buffer, clamp_mask, clamp_ids, _ = ws.build(cfg.code_buffer_length, cfg.vocab_size)
            logits = self.model.decode(code_buffer, cache, code_buffer_start=cur_len)
            lp = logits - mx.logsumexp(logits, axis=-1, keepdims=True)
            mx.eval(lp)
            self._baselines[key] = {
                g: float(lp[0, 0, self.glyphs[g]])
                for g in offer_glyphs if g in self.glyphs}
        return self._baselines[key]

    # ---- LOCK/HARVEST (shared: main path + the EXPAND arm) -----------------

    def _lock_prefix(self, text, refine, errors, eval_session, events):
        """The maximal clean-prefix lock over `text`: parse-gated by the
        oracle `refine` result, eval-gated when a session is supplied.
        Appends lock/eval-failed events; an eval failure appends its span
        to `errors` (the caller's list, mutated — same contract as the
        inline original). Returns (locked, harvest_end, eval_broke)."""
        good = sorted(refine["clamps"], key=lambda c: c["span"][0])
        first_bad = min((e["span"][0] for e in errors), default=len(text) + 1)
        locked, harvest_end, eval_broke = [], 0, False
        for form in good:
            s, e = form["span"]
            if e > first_bad:
                break
            src = form["source"]
            if eval_session is not None:
                ev = eval_session.eval(src)
                if not ev.get("ok"):
                    msg = (ev.get("error") or {}).get("message", "eval failed")
                    errors.append({"span": [s, e], "error-kind": "eval",
                                   "source": msg})
                    events.append({"event": "eval-failed", "form": src[:80],
                                   "error": msg[:120]})
                    eval_broke = True
                    break
            locked.append(src)
            harvest_end = e
            events.append({"event": "lock", "form": src[:80]})
        return locked, harvest_end, eval_broke

    # ---- INTERPRET ----------------------------------------------------------

    @staticmethod
    def _partition(refine, eos_seen, text, draft, locked_any):
        """The TOTAL partition (design: INTERPRET). Every round output maps
        to exactly one arm; the fallthrough is text-progress — the plain
        guided default. Glyph arms are decided by the caller BEFORE this
        (glyphs are token-space, not text-space)."""
        errors = refine["renoise_spans"]
        clamps = refine["clamps"]
        trailing_eof_only = (
            errors
            and all(e.get("error-kind") == "eof" for e in errors)
            and max(e["span"][1] for e in errors) >= len(text))
        hard_errors = [e for e in errors if not (
            trailing_eof_only and e.get("error-kind") == "eof")]
        if (eos_seen and not errors and clamps
                and text.strip()):
            return "eos-complete"
        if not locked_any and text.strip() == draft.strip():
            return "stuck"
        if hard_errors:
            return "broken"
        if not eos_seen or trailing_eof_only:
            return "clean-unfinished"
        return "text-progress"

    # ---- step ---------------------------------------------------------------

    def step(self, context_render, committed="", draft="", offers=None,
             eval_session=None, null_render=None, seed=None):
        """One full FSM turn (mode=step): RENDER → DENOISE → INTERPRET →
        arm action. STATELESS per call — the caller passes back
        `committed` (+ this step's `locked`) and `new_draft` next time;
        re-encoding the render each step is ~free (measured prefill).

        offers: [{"glyph": "①", "label": str,
                  "template": [["clamp", str] | ["free", n]]}].
        Locking is parse-gated by the bb oracle; pass `eval_session` for
        the eval-proven lock (the full guided-loop guarantee).
        Returns {transition, arm, new_draft, locked, glyph, posteriors,
        readouts, hints, events, forwards, gen_s, tok_per_s}."""
        p = self.policy
        gen = self.gen
        offers = offers or []
        events = []
        t0 = time.time()
        if seed is not None:
            mx.random.seed(seed)
        cfg = self.model.cfg
        CL = cfg.code_buffer_length

        # calibration baseline BEFORE the main denoise (deterministic order)
        offer_glyphs = [o["glyph"] for o in offers if o.get("glyph") in self.glyphs]
        baseline = (self._glyph_baseline(null_render, offer_glyphs)
                    if offers and null_render else {})

        # RENDER
        cache, cur_len = self._encode(context_render, committed)
        # frontier backoff: a draft ending MID-SYMBOL is not clamped through
        # the partial — a hard clamp pins a typo forever (measured: the model
        # completed "(todo/ad" to the undeclared "todo/ad!" because it could
        # not insert the missing char). Editor typeahead REPLACES the partial
        # word; so does the code_buffer: clamp up to the symbol start, let the
        # model rewrite the symbol whole (candidates ride the render).
        clamp_text, partial = split_partial_symbol(draft)
        if partial:
            events.append({"event": "frontier-backoff", "partial": partial})
        draft_ids = _tok_ids(self.tok, clamp_text) if clamp_text else []
        ws = _Workspace()
        ws.clamp(draft_ids)
        ws.free(max(p.grow_free, CL - ws.used()))
        code_buffer, clamp_mask, clamp_ids, overflow = ws.build(CL, cfg.vocab_size)
        if overflow:
            events.append({"event": "overflow"})

        # DENOISE — special/channel tokens banned at free positions;
        # EOS stays legal on the open tail (done-ness needs it)
        free = ~clamp_mask
        bias = make_bias(free, ban_vector(cfg.vocab_size, self._open_ban_ids())
                         if p.ban_special else None, [])
        belief, fwd, logits = control._denoise_round(
            self.model, code_buffer, clamp_mask, clamp_ids, cache, cur_len, gen,
            bias=bias)
        total_fwd = fwd
        lp = logits - mx.logsumexp(logits, axis=-1, keepdims=True)
        ent = _entropy(logits)
        mx.eval(lp, ent)

        toks = [int(t) for t in belief[0]]
        draft_n = len(draft_ids)
        eos_at = next((i for i, t in enumerate(toks) if t in gen.eos_token_ids), None)
        eos_seen = eos_at is not None
        visible = toks[:eos_at] if eos_seen else toks

        # glyph scan — TOKEN space, free region only
        emitted = [(i, self.id2glyph[t]) for i, t in enumerate(visible)
                   if i >= draft_n and t in self.id2glyph]
        select_glyph = next((g for _, g in emitted if g != GROW_GLYPH), None)
        grow_glyph = any(g == GROW_GLYPH for _, g in emitted)
        if emitted:
            events.append({"event": "glyph", "glyphs": [g for _, g in emitted]})

        # STRIP glyphs (and pads) before any text reaches the oracle
        glyph_id_set = set(self.id2glyph)
        text = self.tok.decode([t for i, t in enumerate(visible)
                                if t != gen.pad_token_id
                                and not (i >= draft_n and t in glyph_id_set)])
        free_text = self.tok.decode([t for i, t in enumerate(visible)
                                     if i >= draft_n and t != gen.pad_token_id
                                     and t not in glyph_id_set])

        # readouts (all from the same final forward)
        cursor = min(draft_n, CL - 1)
        tail_pos = eos_at if eos_seen else next(
            (i for i in range(draft_n, CL) if toks[i] == gen.pad_token_id), CL - 1)
        eos_lp = max(float(lp[0, tail_pos, e]) for e in gen.eos_token_ids)
        content = [i for i in range(draft_n, min(tail_pos, CL))
                   if toks[i] != gen.pad_token_id]
        f_ent = [float(ent[0, i]) for i in content]
        raw_post = {g: float(lp[0, cursor, self.glyphs[g]]) for g in offer_glyphs}
        cal_post = calibrate(raw_post, baseline) if baseline else None
        margin = posterior_margin(cal_post) if cal_post else None
        readouts = {
            "eos_logprob_tail": round(eos_lp, 2),
            "free_entropy_mean": round(sum(f_ent) / len(f_ent), 3) if f_ent else None,
            "free_entropy_worst": round(max(f_ent), 3) if f_ent else None,
            "glyph_posteriors": {g: round(v, 2) for g, v in raw_post.items()},
            "glyph_posteriors_calibrated": (
                {g: round(v, 2) for g, v in cal_post.items()} if cal_post else None),
            "glyph_margin": round(margin, 2) if margin is not None else None,
        }

        result = {"posteriors": readouts["glyph_posteriors"],
                  "readouts": readouts, "glyph": None, "locked": [],
                  "hints": [], "events": events}

        # INTERPRET — glyph arms first (token space), then the text partition
        refine = self.oracle.refine(text) if text.strip() else \
            {"renoise_spans": [], "clamps": []}

        # calibrated-posterior AUTO-OFFER: only when the margin clears the
        # policy AND the free region is still noise (no parse-clean form
        # typed) — never override typing
        auto = None
        if (select_glyph is None and offers and cal_post
                and margin is not None and margin > p.auto_offer_margin):
            free_refine = (self.oracle.refine(free_text)
                           if free_text.strip() else {"clamps": []})
            if not free_refine["clamps"]:
                auto = max(cal_post, key=cal_post.get)
                events.append({"event": "auto-offer", "glyph": auto,
                               "margin": round(margin, 2)})

        chosen = select_glyph or auto
        if chosen:
            offer = next((o for o in offers if o.get("glyph") == chosen), None)
            if offer is not None:
                # EXPAND — clamp the template segments, fill the holes.
                # An orientation line rides the content channel above the
                # template (measured 0/3→3/3 slot correctness, round 7) —
                # transient, stripped from the assembled text like hints.
                cand = {int(k): v for k, v in
                        (offer.get("candidates") or {}).items()}
                orient = orient_for(offer.get("label"), cand)
                tmpl = norm_segments(offer["template"])
                segments = ([("clamp", draft)] if draft else []) \
                    + ([("clamp", orient)] if orient else []) \
                    + tmpl
                fr = self._fill_on(cache, cur_len, segments, candidates=cand,
                                   settle_rounds=p.expand_settle_rounds)
                total_fwd += fr["forwards"]
                events.append({"event": "expand", "glyph": chosen,
                               "auto": bool(auto), "label": offer.get("label"),
                               "trims": fr["trims"], "probes": fr["probes"]})
                # P6: harvest the expansion IMMEDIATELY (parse-gated, eval-
                # gated with a session) instead of handing an unchecked fill
                # forward as draft. Live-measured failure mode: a junk-args
                # fill rode out as new_draft, the NEXT step's repair arm
                # dropped the whole broken region (the clamped verb call
                # included), the state returned to its pre-step value, and
                # the identical auto-offer re-fired forever. A clean
                # expansion now LOCKS in the same step; a broken one keeps
                # the caller's own draft (never the junk) and reports
                # expand-failed + hints, so the caller's offer memory can
                # suppress the glyph.
                etext = fr["text"]
                eref = (self.oracle.refine(etext) if etext.strip()
                        else {"renoise_spans": [], "clamps": []})
                eerrors = list(eref["renoise_spans"])
                elocked, eharvest_end, _ = self._lock_prefix(
                    etext, eref, eerrors, eval_session, events)
                eremainder = etext[eharvest_end:].strip()
                # A trailing eof error is normally "unfinished, growable" —
                # but when the offer template ENDS with a closing clamp the
                # assembled text is complete by construction, so any eof
                # left standing was injected by the hole (live-measured:
                # `ready")` broke the string balance and the junk rode
                # forward as an eof-only draft).
                template_closed = bool(tmpl) and tmpl[-1][0] == "clamp"
                hard = [e for e in eerrors
                        if e["span"][1] > eharvest_end
                        and not (not template_closed
                                 and e.get("error-kind") == "eof"
                                 and e["span"][1] >= len(etext))]
                if not elocked and hard:
                    hints, seen = [], set()
                    for e in sorted(hard, key=lambda e: e["span"][0]):
                        h = _hint_for(e)
                        if h not in seen and len(seen) < 2:
                            seen.add(h)
                            hints.append(h)
                    events.append({"event": "expand-failed", "glyph": chosen,
                                   "kinds": sorted({e.get("error-kind")
                                                    for e in hard})})
                    result.update({"transition": "expand",
                                   "arm": "glyph-select", "glyph": chosen,
                                   "new_draft": draft, "hints": hints,
                                   "expansion": fr})
                    return self._finish(result, total_fwd, t0)
                result.update({"transition": "expand", "arm": "glyph-select",
                               "glyph": chosen, "locked": elocked,
                               "new_draft": eremainder, "expansion": fr})
                return self._finish(result, total_fwd, t0)
            events.append({"event": "glyph-no-offer", "glyph": chosen})

        # stuck compares against what was CLAMPED (the backoff strips a
        # partial symbol from the clamp; re-emitting just that clamp text
        # is no progress)
        arm = self._partition(refine, eos_seen, text, clamp_text, locked_any=False)
        if grow_glyph and arm in ("clean-unfinished", "text-progress"):
            arm = "clean-unfinished"
            events.append({"event": "grow-glyph"})

        # LOCK/HARVEST — maximal clean prefix (parse-gated; eval-gated
        # when a session is supplied), shared by the progress/broken/done arms
        errors = list(refine["renoise_spans"])
        locked, harvest_end = [], 0
        if arm in ("eos-complete", "text-progress", "broken"):
            locked, harvest_end, eval_broke = self._lock_prefix(
                text, refine, errors, eval_session, events)
            if eval_broke:
                arm = "broken"
        result["locked"] = locked
        remainder = text[harvest_end:]

        if arm == "eos-complete" and not errors and not remainder.strip() and locked:
            events.append({"event": "done"})
            result.update({"transition": "done", "arm": "eos-complete",
                           "new_draft": ""})
            return self._finish(result, total_fwd, t0)
        if arm == "eos-complete":                # eval knocked a form out, or
            arm = "broken" if errors else "text-progress"   # nothing lockable

        if arm == "stuck":
            events.append({"event": "stuck"})
            result.update({"transition": "stuck", "arm": "stuck",
                           "new_draft": draft})
            return self._finish(result, total_fwd, t0)

        if arm == "clean-unfinished":
            events.append({"event": "grow"})
            result.update({"transition": "grow", "arm": "clean-unfinished",
                           "new_draft": text})
            return self._finish(result, total_fwd, t0)

        if arm == "broken":
            # REPAIR/SCRAMBLE plan: keep the clean head + tail, drop the
            # broken region; hints ride back for the caller's next render
            rel = sorted(({**e, "span": [e["span"][0] - harvest_end,
                                         e["span"][1] - harvest_end]}
                          for e in errors if e["span"][1] > harvest_end),
                         key=lambda e: e["span"][0])
            r_start = max(rel[0]["span"][0], 0) if rel else 0
            r_end = min(max((e["span"][1] for e in rel), default=0), len(remainder))
            hints, seen = [], set()
            for e in rel:
                h = _hint_for(e)
                if h not in seen and len(seen) < 2:
                    seen.add(h)
                    hints.append(h)
            keep = remainder[:r_start].rstrip()
            tail = remainder[r_end:].strip()
            new_draft = (keep + ("\n" + tail if tail else "")).strip()
            events.append({"event": "scramble",
                           "kinds": sorted({e.get("error-kind") for e in rel}),
                           "region": [r_start, r_end]})
            result.update({"transition": "repair", "arm": "broken",
                           "new_draft": new_draft, "hints": hints})
            return self._finish(result, total_fwd, t0)

        # DEFAULT arm — text-progress, exactly the guided loop's move:
        # locked prefix committed, remainder kept as the next draft
        result.update({"transition": "progress", "arm": "text-progress",
                       "new_draft": remainder.strip()})
        return self._finish(result, total_fwd, t0)

    def _finish(self, result, forwards, t0):
        dt = time.time() - t0
        produced = "\n".join(result.get("locked", []) + [result.get("new_draft") or ""])
        n_tok = len(_tok_ids(self.tok, produced))
        result.update({"forwards": forwards, "gen_s": round(dt, 3),
                       "tok_per_s": round(n_tok / dt, 1) if dt > 0 else 0.0})
        return result


def _cand_items(candidates):
    """Normalize fill candidates: {idx: [str]} (str or int keys) or a
    list aligned with hole order → [(hole_idx, [str])]."""
    if candidates is None:
        return []
    if isinstance(candidates, dict):
        return [(int(k), v) for k, v in candidates.items()]
    return [(i, v) for i, v in enumerate(candidates) if v]
