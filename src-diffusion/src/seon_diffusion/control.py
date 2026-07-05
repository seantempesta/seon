"""Guided generation — the verified-canvas loop on the local MLX model.

The unit of progress is the top-level FORM, not the canvas:

  round:   denoise the workspace canvas to its natural early-stop
  check:   bb oracle partitions the text — good forms / broken spans
           (parse + def-vs-defn lint + phase grammar, one ~0.1ms call)
  repair:  a provably-fixable near-miss is REWRITTEN in place, $0 model
           forwards: undeclared var → fuzzy candidates (cljs.core) →
           substitute → re-eval; the eval sandbox IS the proof (it
           surfaces undeclared-var and fn-arity as errors). Scramble is
           the fallback, never the first move.
  lock:    the maximal PREFIX of good forms is EXECUTED against the
           stateful eval session (defs accumulate); each form that runs
           is harvested OFF the canvas into the encoder cache — it
           becomes conditioning context and never pays decode cost again
  fix:     every remaining flagged span is scrambled (fresh noise) with a
           `; fix:` hint comment CLAMPED above it — feedback rides the
           content channel the model actually reads
  prove:   when EOS is reached and everything harvested, the caller's
           [{call,expect}] checks run in the SAME session (T3). A failing
           check RESTARTS the attempt (fresh encoder cache) with the
           failure spelled out as a hint — locked forms are in the KV
           cache and cannot be re-noised, so behavioral repair is a new
           attempt, not a scramble.

Perf is reported in TOKENS/SECOND (useful committed tokens over wall
clock) — the owner's standing metric. Free generation (generate.py) is
untouched; it is the baseline arm.
"""

import time

import mlx.core as mx

from .generate import GenConfig, _accept, _entropy
from .repair import (HINT_PREFIX, hint_for as _hint_for,
                     strip_hints as _strip_hints,
                     suggest_candidates as _suggest_candidates,
                     try_repair, undeclared_var as _undeclared_var)

SLACK_TOKENS = 8          # extra free tokens granted to a scrambled span
MIN_TAIL_FREE = 24        # always leave at least this much workspace tail


def _tok_ids(tok, text):
    return tok(text, add_special_tokens=False)["input_ids"] if text else []


class _Workspace:
    """Next-round canvas plan: clamped segments + free (noise) segments."""

    def __init__(self):
        self.segs = []                      # ("clamp", [ids]) | ("free", n)

    def clamp(self, ids):
        if ids:
            self.segs.append(("clamp", ids))

    def free(self, n):
        if n > 0:
            self.segs.append(("free", n))

    def used(self):
        return sum(len(p) if k == "clamp" else p for k, p in self.segs)

    def build(self, CL, vocab_size):
        """Return (canvas[1,CL], clamp_mask[1,CL], clamp_ids[1,CL], overflow?)."""
        ids, mask = [], []
        overflow = False
        for kind, payload in self.segs:
            if kind == "clamp":
                ids += payload
                mask += [True] * len(payload)
            else:
                ids += [None] * payload
                mask += [False] * payload
        if len(ids) > CL:                   # too much kept material: shed tail
            overflow = True
            ids, mask = ids[:CL], mask[:CL]
        pad = CL - len(ids)
        ids += [None] * pad
        mask += [False] * pad
        noise = mx.random.randint(0, vocab_size, (1, CL))
        canvas = mx.array([[i if i is not None else 0 for i in ids]])
        m = mx.array([mask])
        canvas = mx.where(m, canvas, noise)
        return canvas, m, canvas, overflow


def _denoise_round(model, canvas, clamp_mask, clamp_ids, cache, cur_len, gen,
                   probe=None, probe_every=2):
    """One denoise run, holding clamped positions. Ends on the model's own
    stability+confidence — OR EARLIER when `probe(belief)` proves the
    canvas (validation-as-early-stop: a ~0.4ms parse probe against a
    ~114ms forward; proof beats model confidence).
    Returns (belief_ids[1,CL], forwards)."""
    current = canvas
    sc_logits = None
    history = None
    argmax_canvas = current
    forwards = 0
    for cur_step in range(gen.max_denoising_steps, 0, -1):
        forwards += 1
        logits = model.decode(current, cache, canvas_start=cur_len,
                              self_conditioning_logits=sc_logits)
        temp = gen.t_min + (gen.t_max - gen.t_min) * (cur_step / gen.max_denoising_steps)
        logits = logits / temp
        denoiser = mx.random.categorical(logits)
        argmax_canvas = mx.argmax(logits, axis=-1)
        accepted, m = _accept(current, denoiser, logits, gen.entropy_bound)
        renoise = mx.random.randint(0, model.cfg.vocab_size, current.shape)
        current = mx.where(m, accepted, renoise)
        current = mx.where(clamp_mask, clamp_ids, current)

        stable = gen.stability_threshold == 0 or (
            history is not None and bool(mx.all(history == argmax_canvas)))
        history = argmax_canvas
        confident = float(mx.mean(_entropy(logits))) < gen.confidence_threshold
        mx.eval(current, argmax_canvas)
        sc_logits = logits
        if stable and confident:
            break
        if probe is not None and forwards % probe_every == 0:
            belief = mx.where(clamp_mask, clamp_ids, argmax_canvas)
            if probe(belief):
                break
    belief = mx.where(clamp_mask, clamp_ids, argmax_canvas)
    return belief, forwards


def generate_guided(model, tok, prompt_ids, oracle, eval_session=None,
                    gen=None, phase=None, hints=True, repair=True,
                    checks=None, max_rounds=8, max_attempts=3, verbose=False):
    """Oracle-guided generation. Returns the free-gen result dict plus
    committed text, rounds, attempts, locked_forms, repairs,
    checks_passed, tok_per_s (useful committed tokens / wall), events.

    NOTE: `eval_session` state persists across attempts — a redefinition in
    a later attempt overwrites, but a def from a FAILED attempt stays
    visible (advisory-gate caveat; pod replay is the authoritative commit).
    """
    gen = gen or GenConfig()
    if gen.seed is not None:
        mx.random.seed(gen.seed)
    cfg = model.cfg
    CL = cfg.canvas_length

    ids = mx.array(prompt_ids)[None, :]
    t0 = time.time()
    # scratch session for the mid-round proof probe — throwaway state, so
    # probing never pollutes the REAL session the lock path executes in
    probe_session = None
    if eval_session is not None:
        try:
            probe_session = type(eval_session)()
        except Exception:
            probe_session = None
    total_forwards = 0
    locked_forms = 0
    repairs = 0
    events = []
    committed = ""
    done = False
    checks_passed = None
    attempt_hints = ""                      # behavioral-failure notes, cross-attempt
    rounds_used = 0

    for attempt in range(max_attempts):
        cache = model.new_cache()
        model.encode(ids, cache, past_len=0)
        mx.eval(cache[0]["k"])
        cur_len = ids.shape[1]
        committed = ""
        done = False
        checks_passed = None

        ws = _Workspace()
        if attempt_hints:
            ws.clamp(_tok_ids(tok, attempt_hints))
        ws.free(max(MIN_TAIL_FREE, CL - ws.used()))
        prev_signature = None

        for rnd in range(max_rounds):
            rounds_used += 1
            canvas, clamp_mask, clamp_ids, overflow = ws.build(CL, cfg.vocab_size)
            if overflow:
                events.append({"attempt": attempt, "round": rnd, "event": "overflow"})

            def _proven(belief_ids):
                """Mid-round probe: stop denoising only on EXECUTION PROOF —
                parse-clean AND every form evals AND the caller's checks
                pass (in the scratch session). Parse-clean alone freezes a
                half-refined draft body (measured: behav 1.00→0.72)."""
                if probe_session is None:
                    return False
                bt = [int(t) for t in belief_ids[0]]
                be = next((i for i, t in enumerate(bt) if t in gen.eos_token_ids), None)
                if be is None:
                    return False
                btxt = tok.decode([t for t in bt[:be] if t != gen.pad_token_id])
                if not btxt.strip():
                    return False
                br = oracle.refine(btxt, phase=phase)
                if br["renoise_spans"] or not br["clamps"]:
                    return False
                for f in sorted(br["clamps"], key=lambda c: c["span"][0]):
                    if not probe_session.eval(f["source"]).get("ok"):
                        return False
                for c in (checks or []):
                    ev = probe_session.eval(c["call"])
                    if not (ev.get("ok") and ev.get("value") == c["expect"]):
                        return False
                return True

            belief, fwd = _denoise_round(model, canvas, clamp_mask, clamp_ids,
                                         cache, cur_len, gen, probe=_proven)
            total_forwards += fwd

            toks = [int(t) for t in belief[0]]
            eos_at = next((i for i, t in enumerate(toks) if t in gen.eos_token_ids), None)
            eos_seen = eos_at is not None
            visible = toks[:eos_at] if eos_seen else toks
            text = tok.decode([t for t in visible if t != gen.pad_token_id])

            r = oracle.refine(text, phase=phase)
            errors = list(r["renoise_spans"])
            good = sorted(r["clamps"], key=lambda c: c["span"][0])
            first_bad = min((e["span"][0] for e in errors), default=len(text) + 1)

            # ---- repair → lock & execute: maximal prefix, eval-gated ----
            harvest_end = 0
            locked_srcs = []
            for form in good:
                s, e = form["span"]
                if e > first_bad:
                    break
                src = form["source"]
                if eval_session is not None:
                    ev = eval_session.eval(src)
                    if not ev.get("ok"):
                        msg = (ev.get("error") or {}).get("message", "eval failed")
                        fixed = try_repair(src, msg, eval_session) if repair else None
                        if fixed is None:
                            var = _undeclared_var(msg)
                            cands = (_suggest_candidates(eval_session, var, n=1)
                                     if var else [])
                            errors.append({"span": [s, e], "error-kind": "eval",
                                           "source": msg,
                                           "suggest": cands[0] if cands else None})
                            break
                        src, fix_list = fixed
                        repairs += len(fix_list)
                        events.append({"attempt": attempt, "round": rnd,
                                       "event": "repair",
                                       "fixes": [{"from": a, "to": b}
                                                 for a, b in fix_list]})
                harvest_end = e
                locked_forms += 1
                locked_srcs.append(src)
                events.append({"attempt": attempt, "round": rnd,
                               "event": "lock", "form": src[:80]})
            if locked_srcs:
                htext = "\n".join(locked_srcs) + "\n"
                hids = _tok_ids(tok, htext)
                model.encode(mx.array(hids)[None, :], cache, past_len=cur_len)
                mx.eval(cache[0]["k"])
                cur_len += len(hids)
                committed += htext

            remainder = text[harvest_end:]
            rel_errors = sorted(
                ({**e, "span": [e["span"][0] - harvest_end, e["span"][1] - harvest_end]}
                 for e in errors if e["span"][1] > harvest_end),
                key=lambda e: e["span"][0])

            # done needs PROOF of work: something committed, nothing pending
            if not rel_errors and eos_seen and not remainder.strip() and committed:
                done = True
                events.append({"attempt": attempt, "round": rnd, "event": "done"})
                break

            # ---- plan the next canvas: keep good text, scramble+hint bad ----
            ws = _Workspace()
            if attempt_hints:
                ws.clamp(_tok_ids(tok, attempt_hints))
            pos = 0
            for e in rel_errors:
                s, t_end = max(e["span"][0], 0), min(e["span"][1], len(remainder))
                keep = _strip_hints(remainder[pos:s])
                ws.clamp(_tok_ids(tok, keep))
                if hints:
                    ws.clamp(_tok_ids(tok, _hint_for(e)))
                ws.free(len(_tok_ids(tok, remainder[s:t_end])) + SLACK_TOKENS)
                events.append({"attempt": attempt, "round": rnd, "event": "scramble",
                               "kind": e.get("error-kind"),
                               "source": (e.get("source") or "")[:80],
                               "suggest": e.get("suggest")})
                pos = t_end
            tail = _strip_hints(remainder[pos:])
            if tail.strip():
                ws.clamp(_tok_ids(tok, tail))
            ws.free(max(MIN_TAIL_FREE, CL - ws.used()))

            signature = (committed, tuple((e.get("error-kind"), tuple(e["span"]))
                                          for e in rel_errors), remainder)
            if signature == prev_signature:
                events.append({"attempt": attempt, "round": rnd, "event": "stuck"})
                break
            prev_signature = signature

        # ---- T3: the caller's behavioral checks, in the same session ----
        if done and checks and eval_session is not None:
            failures = []
            for c in checks:
                ev = eval_session.eval(c["call"])
                got = (ev.get("value") if ev.get("ok")
                       else f"error: {(ev.get('error') or {}).get('message', '?')}")
                if not (ev.get("ok") and ev.get("value") == c["expect"]):
                    failures.append((c, got))
            checks_passed = not failures
            if failures:
                events.append({"attempt": attempt, "event": "checks-failed",
                               "failures": [{"call": c["call"], "expect": c["expect"],
                                             "got": g} for c, g in failures]})
                attempt_hints = "".join(
                    f"{HINT_PREFIX} {c['call']} must return {c['expect']} "
                    f"but returned {g} — fix the logic\n" for c, g in failures)
                done = False
                continue                    # fresh attempt, hints in view
        break                               # done (checks passed / no checks) or gave up

    if probe_session is not None:
        probe_session.close()
    t_total = time.time() - t0
    n_tokens = len(_tok_ids(tok, committed))
    return {
        "text": committed,
        "done": done,
        "attempts": attempt + 1,
        "rounds": rounds_used,
        "locked_forms": locked_forms,
        "repairs": repairs,
        "checks_passed": checks_passed,
        "decoder_forwards": total_forwards,
        "generate_s": t_total,
        "tok_per_s": n_tokens / t_total if t_total > 0 else 0.0,
        "events": events,
    }
