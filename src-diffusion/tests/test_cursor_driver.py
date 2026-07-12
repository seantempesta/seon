"""CursorDriver FSM against a SCRIPTED stub model + the REAL bb oracle.

The test_control_loop pattern exactly: the stub is a perfect denoiser
(near-one-hot logits, one forward per round via stability_threshold=0),
targets are scripted per round, bb parse/lint is real. Every INTERPRET
arm is forced by a scripted output; glyph handling, masks, overlap-trim,
calibration arithmetic, and the CAL length probe are pinned offline.
"""

import shutil
from types import SimpleNamespace

import pytest

mx = pytest.importorskip("mlx.core")

from seon_diffusion.cursor import (CursorDriver, Policy, allow_vector,   # noqa: E402
                                   ban_vector, calibrate, glyph_ids,
                                   overlap_trim, posterior_margin)
from seon_diffusion.generate import GenConfig                            # noqa: E402

pytestmark = pytest.mark.skipif(
    shutil.which("bb") is None or shutil.which("node") is None,
    reason="bb/node not on PATH")

EOS, PAD, SPACE, CL, VOCAB = 3, 0, 32, 96, 512

# single-token glyph ids in the stub vocab (derived via the tokenizer,
# same contract as the real one — never hardcoded in the driver)
GLYPH_ID = {g: 300 + i for i, g in enumerate("①②③④⑤⑥⑦⑧⑨⑩")}
GLYPH_ID["⤵"] = 320
ID_GLYPH = {v: k for k, v in GLYPH_ID.items()}


class GlyphTok:
    def __call__(self, text, add_special_tokens=False):
        return {"input_ids": [GLYPH_ID.get(c, ord(c)) for c in text]}

    def decode(self, ids, skip_special_tokens=False):
        out = []
        for i in ids:
            if i in ID_GLYPH:
                out.append(ID_GLYPH[i])
            elif i == 10 or 31 < i < 256:
                out.append(chr(i))
        return "".join(out)


class StubModel:
    """Perfect denoiser: holds clamps, writes the next scripted target
    into free positions (space-filled past the target; EOS appended when
    the target's flag says so)."""

    def __init__(self, tok, targets):
        self.cfg = SimpleNamespace(code_buffer_length=CL, vocab_size=VOCAB)
        self.tok = tok
        self.targets = [(t, True) if isinstance(t, str) else t for t in targets]
        self.encoded = []
        self._mask = [False] * CL

    def new_cache(self):
        return [{"k": mx.zeros(1)}]

    def encode(self, ids, cache, past_len):
        self.encoded.append(self.tok.decode([int(t) for t in ids[0]]))

    def _emit(self, code_buffer_ids, stream):
        out, ti = [], 0
        for p in range(CL):
            if self._mask[p]:
                out.append(int(code_buffer_ids[0][p]))
            elif ti < len(stream):
                out.append(stream[ti])
                ti += 1
            else:
                out.append(SPACE)
        safe = [i if i < VOCAB else 1 for i in out]
        logits = mx.full((1, CL, VOCAB), -20.0)
        onehot = mx.zeros((1, CL, VOCAB))
        onehot[0, mx.arange(CL), mx.array(safe)] = 50.0
        return logits + onehot

    def decode(self, code_buffer_ids, cache, code_buffer_start, self_conditioning_logits=None):
        target, want_eos = self.targets.pop(0) if self.targets else ("", False)
        stream = self.tok(target)["input_ids"] + ([EOS] if want_eos else [])
        return self._emit(code_buffer_ids, stream)


@pytest.fixture(autouse=True)
def spy_clamp_mask(monkeypatch):
    """Hand the stub the clamp mask a real model implicitly respects."""
    import seon_diffusion.control as C
    real = C._denoise_round

    def wrapper(model, code_buffer, clamp_mask, clamp_ids, cache, cur_len, gen, **kw):
        model._mask = [bool(b) for b in clamp_mask[0]]
        return real(model, code_buffer, clamp_mask, clamp_ids, cache, cur_len, gen, **kw)
    monkeypatch.setattr(C, "_denoise_round", wrapper)


def gen_cfg():
    return GenConfig(seed=1, entropy_bound=0.1, stability_threshold=0,
                     confidence_threshold=0.05, max_denoising_steps=4,
                     eos_token_ids=(EOS,), pad_token_id=PAD)


@pytest.fixture(scope="module")
def oracle():
    from seon_diffusion.oracle import Oracle
    o = Oracle()
    yield o
    o.close()


class SpyOracle:
    """Records every code string refine ever sees (glyph-leak assertions)."""

    def __init__(self, o):
        self.o = o
        self.seen = []

    def refine(self, code, phase=None):
        self.seen.append(code)
        return self.o.refine(code, phase=phase)

    def cursor(self, text, cursor):
        return self.o.cursor(text, cursor)


def driver(targets, oracle, policy=None):
    tok = GlyphTok()
    model = StubModel(tok, targets)
    spy = SpyOracle(oracle)
    d = CursorDriver(model, tok, spy,
                     policy=policy or Policy(probe_lengths=1), gen=gen_cfg())
    return d, model, spy


OFFERS = [{"glyph": "①", "label": "add todo",
           "template": [["clamp", '(todo/add! "'], ["free", 8], ["clamp", '")']]},
          {"glyph": "②", "label": "query",
           "template": [["clamp", "(db/query q)"]]}]


# ---------------------------------------------------------------------------
# pure helpers
# ---------------------------------------------------------------------------

def test_policy_defaults():
    p = Policy()
    assert p.auto_offer_margin == 6.0
    assert p.worst_entropy_gate == 1.0
    assert p.probe_lengths == 3 and p.probe_delta == 4
    assert p.ban_special and p.ban_eos_in_holes and p.candidate_masks
    assert p.min_overlap == 2 and p.settle_steps == 3
    assert p.max_rounds == 8 and p.max_attempts == 3
    assert p.glyph_page_size == 10


def test_overlap_trim():
    # the measured suffix echo: hole tail regenerates the following clamp
    t, k = overlap_trim('buy milk" :my', '" :my.plan/status :open})')
    assert t == "buy milk" and k == 5
    t, k = overlap_trim("no echo here", '" :status')
    assert t == "no echo here" and k == 0
    # below min_overlap: a single-char coincidence is not an echo
    t, k = overlap_trim("word)", ") tail", min_overlap=2)
    assert t == "word)" and k == 0
    t, k = overlap_trim("word))", ")) tail", min_overlap=2)
    assert t == "word" and k == 2


def test_calibration_subtraction():
    raw = {"①": -0.1, "②": -8.0}
    base = {"①": -3.0, "②": -9.0}
    cal = calibrate(raw, base)
    assert cal == {"①": 2.9, "②": 1.0}
    assert posterior_margin(cal) == pytest.approx(1.9)
    assert posterior_margin({"①": 4.2}) == pytest.approx(4.2)  # single = lift
    assert posterior_margin({}) is None


def test_glyph_ids_single_token_only():
    ids = glyph_ids(GlyphTok())
    assert len(ids) == 11 and ids["①"] == GLYPH_ID["①"] and ids["⤵"] == GLYPH_ID["⤵"]

    class SplitGrow(GlyphTok):
        def __call__(self, text, add_special_tokens=False):
            if text == "⤵":
                return {"input_ids": [1, 2]}       # multi-token: unusable
            return super().__call__(text)
    ids = glyph_ids(SplitGrow())
    assert "⤵" not in ids and len(ids) == 10


def test_open_tail_keeps_eos_legal(oracle):
    """Live-found bug: the real tokenizer declares EOS special, so the
    step-mode special ban was killing the done-ness meter. Holes ban
    EOS/pad/specials; the open tail bans specials MINUS EOS/pad."""
    class SpecialTok(GlyphTok):
        all_special_ids = [EOS, PAD, 400]      # 400 = a scaffold token

    tok = SpecialTok()
    d = CursorDriver(StubModel(tok, []), tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=1), gen=gen_cfg())
    assert d._open_ban_ids() == [400]
    assert set(d._hole_ban_ids()) == {EOS, PAD, 400}


def test_mask_construction():
    v = ban_vector(VOCAB, [EOS, PAD])
    assert float(v[EOS]) == -1e9 and float(v[PAD]) == -1e9
    assert float(v[ord("a")]) == 0.0
    assert ban_vector(VOCAB, []) is None
    a = allow_vector(VOCAB, GlyphTok(), [":open", ":done"])
    for ch in ":opendn":
        assert float(a[ord(ch)]) == 0.0
    assert float(a[ord("x")]) == -1e9 and float(a[EOS]) == -1e9


# ---------------------------------------------------------------------------
# INTERPRET arms — each forced by a scripted stub output
# ---------------------------------------------------------------------------

def test_arm_eos_complete_locks_and_done(oracle):
    d, _, _ = driver(["(defn f2a [x] x)"], oracle)
    r = d.step("ctx")
    assert r["arm"] == "eos-complete" and r["transition"] == "done"
    assert r["locked"] == ["(defn f2a [x] x)"] and r["new_draft"] == ""


def test_arm_text_progress_default(oracle):
    # clean form + trailing comment + EOS: lock the prefix, keep the rest —
    # exactly the guided loop's default move
    d, _, _ = driver(["(defn t1 [x] x)\n; still thinking"], oracle)
    r = d.step("ctx")
    assert r["arm"] == "text-progress" and r["transition"] == "progress"
    assert r["locked"] == ["(defn t1 [x] x)"]
    assert r["new_draft"] == "; still thinking"


def test_arm_broken_scramble_with_hints(oracle):
    d, _, _ = driver(["(def f2b [x] x)"], oracle)
    r = d.step("ctx")
    assert r["arm"] == "broken" and r["transition"] == "repair"
    assert not r["locked"]
    assert any("def takes no arg vector" in h for h in r["hints"])
    assert any(e["event"] == "scramble" and "def-vs-defn" in e["kinds"]
               for e in r["events"])


def test_arm_clean_unfinished_grows(oracle):
    d, _, _ = driver([("(defn part [x]", False)], oracle)
    r = d.step("ctx")
    assert r["arm"] == "clean-unfinished" and r["transition"] == "grow"
    assert r["new_draft"].startswith("(defn part [x]")


def test_arm_stuck_no_progress(oracle):
    d, _, _ = driver([("", False)], oracle)
    r = d.step("ctx", draft="(def f [x] x)")
    assert r["arm"] == "stuck" and r["transition"] == "stuck"
    assert r["new_draft"] == "(def f [x] x)"


def test_arm_glyph_select_expands_and_locks(oracle):
    # P6: a parse-clean expansion locks IN the expand step (harvested
    # immediately) instead of riding forward as an unchecked draft.
    d, model, spy = driver(["①", ("buy milk", False)], oracle)
    r = d.step("ctx", offers=OFFERS)
    assert r["arm"] == "glyph-select" and r["transition"] == "expand"
    assert r["glyph"] == "①"
    assert r["locked"] == ['(todo/add! "buy milk")']
    assert r["new_draft"] == ""
    assert any(e["event"] == "lock" for e in r["events"])
    # the glyph exists only between driver and model — never in text
    assert all("①" not in code for code in spy.seen)
    assert all("①" not in form for form in r["locked"])


def test_expand_failed_keeps_caller_draft_and_reports(oracle):
    # P6 root-cause fix (live-measured p1 loop): a junk-args fill must NOT
    # ride forward as new_draft (the next step's repair dropped the whole
    # region and the identical auto-offer re-fired forever). A broken
    # expansion keeps the caller's own draft, locks nothing, and reports
    # expand-failed so the caller's offer memory can suppress the glyph.
    d, model, spy = driver(["①", ('a" ]', False)], oracle)
    r = d.step("ctx", draft="", offers=OFFERS)
    assert r["transition"] == "expand" and r["glyph"] == "①"
    assert r["locked"] == []
    assert r["new_draft"] == ""          # the caller's draft, unchanged
    assert any(e["event"] == "expand-failed" for e in r["events"])
    assert r["hints"]


def test_grow_glyph_saves_a_round(oracle):
    d, _, _ = driver([("(+ 1 2) ⤵", False)], oracle)
    r = d.step("ctx")
    assert r["transition"] == "grow" and r["arm"] == "clean-unfinished"
    assert "⤵" not in r["new_draft"] and "(+ 1 2)" in r["new_draft"]
    assert any(e["event"] == "glyph" for e in r["events"])


# ---------------------------------------------------------------------------
# calibrated auto-offer
# ---------------------------------------------------------------------------

class AutoOfferStub(StubModel):
    """Call 1 (the null-render baseline): flat logits. Call 2 (the main
    denoise): free text is noise (spaces) but the ① glyph carries high
    mass at the cursor WITHOUT being argmax — the auto-offer shape.
    Calls 3+: scripted fill targets (the expansion)."""

    def __init__(self, tok, targets):
        super().__init__(tok, targets)
        self.calls = 0

    def decode(self, code_buffer_ids, cache, code_buffer_start, self_conditioning_logits=None):
        self.calls += 1
        if self.calls == 1:
            return mx.zeros((1, CL, VOCAB))
        if self.calls == 2:
            logits = self._emit(code_buffer_ids, [])          # all spaces
            bump = mx.zeros((1, CL, VOCAB))
            bump[0, 0, GLYPH_ID["①"]] = 40.0             # -20+40=20 < the space's 30
            return logits + bump
        return super().decode(code_buffer_ids, cache, code_buffer_start,
                              self_conditioning_logits)


def test_auto_offer_fires_on_calibrated_margin(oracle):
    tok = GlyphTok()
    model = AutoOfferStub(tok, [("milk", False)])
    d = CursorDriver(model, tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=1), gen=gen_cfg())
    r = d.step("ctx", offers=OFFERS, null_render="null intent")
    assert r["transition"] == "expand" and r["glyph"] == "①"
    assert any(e["event"] == "auto-offer" for e in r["events"])
    cal = r["readouts"]["glyph_posteriors_calibrated"]
    assert cal["①"] > cal["②"]
    assert r["readouts"]["glyph_margin"] > Policy().auto_offer_margin
    assert "milk" in (r["new_draft"] + " ".join(r["locked"]))


def test_no_auto_offer_when_model_typed_code(oracle):
    """Never override typing: a parse-clean free-typed form wins even if
    the glyph posterior margin clears the threshold."""
    class TypedStub(AutoOfferStub):
        def decode(self, code_buffer_ids, cache, code_buffer_start,
                   self_conditioning_logits=None):
            self.calls += 1
            if self.calls == 1:
                return mx.zeros((1, CL, VOCAB))
            logits = self._emit(code_buffer_ids,
                                self.tok("(db/q 1)")["input_ids"] + [EOS])
            bump = mx.zeros((1, CL, VOCAB))
            bump[0, 0, GLYPH_ID["①"]] = 40.0
            return logits + bump

    tok = GlyphTok()
    model = TypedStub(tok, [])
    d = CursorDriver(model, tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=1), gen=gen_cfg())
    r = d.step("ctx", offers=OFFERS, null_render="null intent")
    assert r["transition"] != "expand"
    assert not any(e["event"] == "auto-offer" for e in r["events"])
    assert "(db/q 1)" in (r["new_draft"] + " ".join(r["locked"]))


# ---------------------------------------------------------------------------
# fill: overlap-trim + readouts + CAL probe
# ---------------------------------------------------------------------------

def test_fill_overlap_trim_and_confidence(oracle):
    d, _, _ = driver([('buy milk" :my', False)], oracle)
    r = d.fill("ctx", [("clamp", '(todo/add! "'), ("free", 14),
                       ("clamp", '" :my.plan/status :open})')])
    assert r["holes"] == ["buy milk"] and r["trims"] == [5]
    assert r["text"] == '(todo/add! "buy milk" :my.plan/status :open})'
    st = r["hole_confidence"][0]
    assert st["worst"] < 1.0 and st["accepted"] is True
    assert r["tok_per_s"] > 0


def test_fill_candidate_hole_sized_and_masked(oracle):
    """A hole with a candidate set is CLOSED: sized to the longest
    candidate (no slack → no echo/repeat junk) and skipped by CAL."""
    d, _, _ = driver([(":op", False)], oracle,
                     policy=Policy(probe_lengths=3))
    r = d.fill("ctx", [("clamp", "(s "), ("free", 9), ("clamp", ")")],
               candidates={"0": [":op", ":no"]})
    assert r["holes"] == [":op"] and r["text"] == "(s :op)"
    assert r["probes"] == []                   # candidate hole: no CAL probe
    assert r["hole_confidence"][0]["snapped"] is True


def test_rank_orders_candidates(oracle):
    d, _, _ = driver([(":open", False)] * 3, oracle)
    r = d.rank("ctx", '(todo/add! {:my.plan/status ',
               [":open", ":done"], "})")
    assert [c["candidate"] for c in r["ranked"]] == [":open", ":done"]
    assert r["ranked"][0]["score"] > r["ranked"][1]["score"]
    assert r["calibrated"] is False and r["forwards"] == 3


class ProbeStub(StubModel):
    """Fixed belief: 'abc' at the hole start, FLAT logits past it — the
    CAL Φ(L) signal peaks when the hole length matches the content."""

    def __init__(self, tok, hole_start):
        super().__init__(tok, [])
        self.hole_start = hole_start

    def decode(self, code_buffer_ids, cache, code_buffer_start, self_conditioning_logits=None):
        logits = mx.zeros((1, CL, VOCAB))
        for i, t in enumerate(self.tok("abc")["input_ids"]):
            logits[0, self.hole_start + i, t] = 50.0
        return logits


def test_cal_hole_length_probe_picks_peak(oracle):
    tok = GlyphTok()
    model = ProbeStub(tok, hole_start=len(tok("(x ")["input_ids"]))
    d = CursorDriver(model, tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=3, probe_delta=4),
                     gen=gen_cfg())
    r = d.fill("ctx", [("clamp", "(x "), ("free", 7), ("clamp", ")")])
    assert r["probes"][0]["chosen"] == 3
    assert set(r["probes"][0]["scores"]) == {3, 7, 11}
    assert r["probes"][0]["scores"][3] == max(r["probes"][0]["scores"].values())
    assert r["holes"] == ["abc"] and r["text"] == "(x abc)"


# ---------------------------------------------------------------------------
# eval-gated lock (real node session)
# ---------------------------------------------------------------------------

@pytest.fixture()
def session():
    from seon_diffusion.oracle import EvalSession
    s = EvalSession()
    yield s
    s.close()


def test_eval_gated_lock(oracle, session):
    d, _, _ = driver(["(defn ev2 [x] (+ x 1))"], oracle)
    r = d.step("ctx", eval_session=session)
    assert r["transition"] == "done" and r["locked"] == ["(defn ev2 [x] (+ x 1))"]

    d2, _, _ = driver(["(defn ev3 [xs] (filter even xs))"], oracle)
    r2 = d2.step("ctx", eval_session=session)
    assert r2["transition"] == "repair" and not r2["locked"]
    assert any(e["event"] == "eval-failed" for e in r2["events"])


# ---------------------------------------------------------------------------
# orientation line (round 7: 0/3->3/3 slot correctness)
# ---------------------------------------------------------------------------

def test_orient_for_renders_label_and_candidates():
    from seon_diffusion.repair import orient_for, strip_hints
    line = orient_for("(todo/add! …)", {1: [":open", ":done"]})
    assert line.startswith("; slot:")
    assert "(todo/add! …)" in line and ":open :done" in line
    assert strip_hints("(+ 1 2)\n" + line) == "(+ 1 2)\n"
    assert orient_for(None, {}) == ""


def test_expand_clamps_orientation_and_strips_from_draft(oracle):
    d, model, spy = driver(["①", (":open", False)], oracle)
    offers = [{"glyph": "①", "label": "(todo/add! <status>)",
               "candidates": {"0": [":open", ":done"]},
               "template": [["clamp", "(todo/add! "], ["free", 3],
                            ["clamp", ")"]]}]
    r = d.step("ctx", offers=offers)
    assert r["transition"] == "expand"
    produced = r["new_draft"] + " ".join(r["locked"])
    # orientation line never leaks into the assembled output
    assert "; slot:" not in produced
    # offer candidates flowed through to the closed-hole SNAP
    exp = r["expansion"]
    assert exp["hole_confidence"][0].get("snapped") is True
    assert exp["holes"][0] in (":open", ":done")
    # P6: the clean expansion locks in-step
    assert r["locked"] == [f"(todo/add! {exp['holes'][0]})"]
    assert r["new_draft"] == ""


# ---------------------------------------------------------------------------
# progressive per-hole locking (settle rounds)
# ---------------------------------------------------------------------------

def test_settled_hole_clamps_while_sibling_resettles(oracle):
    # stub emits junk in hole 2 first round; the settled hole-1 text must
    # survive (clamped) and only hole 2 re-noises on round 2
    d, model, spy = driver(["①", (":open junk", False), (":done", False)],
                           oracle,
                           policy=Policy(probe_lengths=1, settle_rounds=2,
                                         worst_entropy_gate=1.0))
    offers = [{"glyph": "①", "label": "f",
               "candidates": {"0": [":open"], "1": [":done", ":open"]},
               "template": [["clamp", "(f "], ["free", 2],
                            ["clamp", " "], ["free", 2], ["clamp", ")"]]}]
    r = d.step("ctx", offers=offers)
    exp = r["expansion"]
    # both holes end snapped-legal; per-hole state carries the settle round
    assert exp["holes"][0] == ":open"
    assert exp["holes"][1] in (":done", ":open")
    assert all(s["accepted"] for s in exp["hole_confidence"])
    assert all(s["round"] is not None for s in exp["hole_confidence"])
    assert "settle_rounds_used" in exp


def test_settle_rounds_zero_is_single_round(oracle):
    d, model, spy = driver(["①", (":open", False)], oracle,
                           policy=Policy(probe_lengths=1, settle_rounds=0))
    offers = [{"glyph": "①", "label": "one-slot",
               "candidates": {"0": [":open", ":done"]},
               "template": [["clamp", "(f "], ["free", 2], ["clamp", ")"]]}]
    r = d.step("ctx", offers=offers)
    assert r["expansion"]["settle_rounds_used"] == 1


def test_overflow_truncated_hole_is_honest_not_crash(oracle):
    # a template longer than the code_buffer: the truncated hole reports
    # empty + unaccepted + overflow=True (regression: used to crash)
    d, model, spy = driver(["①", ("x", False)], oracle,
                           policy=Policy(probe_lengths=1, settle_rounds=0))
    offers = [{"glyph": "①", "label": "L" * 90,
               "candidates": {"1": [":done"]},
               "template": [["clamp", "(f "], ["free", 2],
                            ["clamp", " "], ["free", 2], ["clamp", ")"]]}]
    r = d.step("ctx", offers=offers)
    exp = r["expansion"]
    assert exp["overflow"] is True
    assert exp["hole_confidence"][1]["accepted"] is False


# ---------------------------------------------------------------------------
# frontier backoff (mode 1: replace the partial symbol, never clamp a typo)
# ---------------------------------------------------------------------------

def test_split_partial_symbol():
    from seon_diffusion.cursor import split_partial_symbol
    assert split_partial_symbol("(todo/ad") == ("(", "todo/ad")
    assert split_partial_symbol("(f x ") == ("(f x ", "")
    assert split_partial_symbol('(f "done"') == ('(f "done"', "")
    assert split_partial_symbol("(let [x 1] (+ x y") == ("(let [x 1] (+ x ", "y")
    assert split_partial_symbol("") == ("", "")


def test_frontier_backoff_lets_model_rewrite_symbol(oracle):
    # draft ends mid-symbol with a typo'd prefix; the model writes the
    # correct full symbol because the partial is NOT clamped
    d, model, spy = driver([("todo/add!)", True)], oracle,
                           policy=Policy(probe_lengths=1))
    r = d.step("ctx", draft="(todo/ad")
    assert any(e["event"] == "frontier-backoff" and e["partial"] == "todo/ad"
               for e in r["events"])
    assert r["locked"] == ["(todo/add!)"]
    assert r["transition"] == "done"


# ---------------------------------------------------------------------------
# buffer picture (buffer_text/buffer_spans) + offer_status — the additive
# observability fields the :typeahead-steps tile renders
# ---------------------------------------------------------------------------

def _assert_spans_wellformed(r):
    """Spans are ordered, non-overlapping, clipped to buffer_text, and use
    only the closed status vocabulary."""
    from seon_diffusion.cursor import BUFFER_STATUSES
    n = len(r["buffer_text"])
    prev = 0
    for s in r["buffer_spans"]:
        assert s["status"] in BUFFER_STATUSES
        assert 0 <= s["start"] <= s["end"] <= n
        if s["status"] != "frontier":
            assert s["start"] >= prev
            prev = s["end"]


def test_spans_overlay_clips_and_orders():
    from seon_diffusion.cursor import spans_overlay
    base = [{"start": 0, "end": 10, "status": "settled"}]
    out = spans_overlay(base, 3, 6, "repaired")
    assert out == [{"start": 0, "end": 3, "status": "settled"},
                   {"start": 3, "end": 6, "status": "repaired"},
                   {"start": 6, "end": 10, "status": "settled"}]
    # zero-width overlay is a no-op
    assert spans_overlay(base, 4, 4, "locked") == base


def test_progress_buffer_marks_locked_prefix(oracle):
    d, _, _ = driver(["(defn t9 [x] x)\n; still thinking"], oracle)
    r = d.step("ctx")
    _assert_spans_wellformed(r)
    by = {s["status"]: s for s in r["buffer_spans"]}
    assert "locked" in by and by["locked"]["start"] == 0
    locked_txt = r["buffer_text"][by["locked"]["start"]:by["locked"]["end"]]
    assert "(defn t9 [x] x)" in locked_txt
    assert "settled" in by            # the unharvested remainder
    assert "frontier" in by and by["frontier"]["start"] == by["frontier"]["end"]


def test_repair_buffer_marks_repaired_region(oracle):
    d, _, _ = driver(["(def f9 [x] x)"], oracle)
    r = d.step("ctx")
    assert r["transition"] == "repair"
    _assert_spans_wellformed(r)
    statuses = {s["status"] for s in r["buffer_spans"]}
    assert "repaired" in statuses
    rep = next(s for s in r["buffer_spans"] if s["status"] == "repaired")
    assert "def f9" in r["buffer_text"][rep["start"]:rep["end"]]


def test_expand_buffer_spans_clamped_and_locked(oracle):
    d, _, _ = driver(["①", ("buy milk", False)], oracle)
    r = d.step("ctx", offers=OFFERS)
    assert r["transition"] == "expand" and r["locked"]
    _assert_spans_wellformed(r)
    statuses = {s["status"] for s in r["buffer_spans"]}
    assert "locked" in statuses       # the harvested expansion
    # expansion carries its own per-hole picture + round budget
    fr = r["expansion"]
    assert fr["settle_round_budget"] >= fr["settle_rounds_used"] >= 1
    assert {"clamped", "settled", "resolving"} >= {s["status"] for s in fr["spans"]}
    # offer_status: the chosen glyph is fired, the other below-margin
    st = {s["glyph"]: s for s in r["offer_status"]}
    assert st["①"]["state"] == "fired"
    assert st["②"]["state"] == "below-margin"
    assert r["readouts"]["auto_offer_margin"] == Policy().auto_offer_margin


def test_typed_region_suppression_reported(oracle):
    """The margin clears but the model typed a clean form: the top offer
    reports suppressed/typed-region instead of silently not firing."""
    class TypedStub(AutoOfferStub):
        def decode(self, code_buffer_ids, cache, code_buffer_start,
                   self_conditioning_logits=None):
            self.calls += 1
            if self.calls == 1:
                return mx.zeros((1, CL, VOCAB))
            logits = self._emit(code_buffer_ids,
                                self.tok("(db/q 1)")["input_ids"] + [EOS])
            bump = mx.zeros((1, CL, VOCAB))
            bump[0, 0, GLYPH_ID["①"]] = 40.0
            return logits + bump

    tok = GlyphTok()
    model = TypedStub(tok, [])
    d = CursorDriver(model, tok, SpyOracle(oracle),
                     policy=Policy(probe_lengths=1), gen=gen_cfg())
    r = d.step("ctx", offers=OFFERS, null_render="null intent")
    st = {s["glyph"]: s for s in r["offer_status"]}
    assert st["①"]["state"] == "suppressed"
    assert st["①"]["reason"] == "typed-region"
    assert any(e["event"] == "auto-offer-suppressed" for e in r["events"])


def test_fill_returns_piece_spans(oracle):
    d, _, _ = driver([("buy milk", False)], oracle)
    r = d.fill("ctx", [["clamp", '(todo/add! "'], ["free", 8], ["clamp", '")']])
    assert r["text"].startswith('(todo/add! "')
    kinds = [s["status"] for s in r["spans"]]
    assert kinds[0] == "clamped" and kinds[-1] == "clamped"
    assert any(k in ("settled", "resolving") for k in kinds)
    # spans tile the text exactly
    assert "".join(r["text"][s["start"]:s["end"]] for s in r["spans"]) == r["text"]


# ---------------------------------------------------------------------------
# W2 — the draft-head prefill affordance (planner-worker-design)
# ---------------------------------------------------------------------------

RECONCILE_HEAD = "my.plan/reconcile!"
DOC_ENTRY = ':my.plan/title "t1"'
PREFILL_TMPL = [["clamp", "(my.plan/reconcile! {:my.plan/tree {"],
                ["clamp", ':my.plan/id "a1" '],
                ["prefill", DOC_ENTRY],
                ["clamp", "}})"]]


def test_norm_segments_prefill():
    from seon_diffusion.cursor import norm_segments
    out = norm_segments([["clamp", "(f "], ["prefill", "x"], ["free", 4]])
    assert out == [("clamp", "(f "), ("prefill", "x"), ("free", 4)]
    with pytest.raises(ValueError):
        norm_segments([["nope", 1]])


def test_workspace_prefill_init_not_noise():
    """A prefill hole starts holding its ids (mask False — editable), while
    a plain free hole starts as noise. Clamp stays the only guarantee."""
    from seon_diffusion.control import _Workspace
    mx.random.seed(7)
    ws = _Workspace()
    ws.clamp([9, 9])
    ws.free(4, init=[101, 102, 103])     # prefill: 3 init ids + 1 noise slot
    ws.free(4)                            # plain free: all noise
    cb, mask, _, overflow = ws.build(16, VOCAB)
    assert not overflow
    row = [int(t) for t in cb[0]]
    m = [bool(b) for b in mask[0]]
    assert row[0:2] == [9, 9] and m[0] and m[1]
    assert row[2:5] == [101, 102, 103], "init ids seed the hole"
    assert m[2:6] == [False] * 4, "prefill positions stay editable (unclamped)"


def test_prefill_match_opened_head_only(oracle):
    d, _, _ = driver([], oracle)
    pf = {RECONCILE_HEAD: PREFILL_TMPL}
    # opened call, args not begun → fires
    got = d._prefill_match("(my.plan/reconcile! ", pf)
    assert got is not None and got[0] == RECONCILE_HEAD
    # opening brace only still counts as "args not begun"
    assert d._prefill_match("(my.plan/reconcile! {", pf) is not None
    # args begun → never clobber typed content
    assert d._prefill_match(
        "(my.plan/reconcile! {:my.plan/tree {}", pf) is None
    # a different head → normal path
    assert d._prefill_match("(todo/add! ", pf) is None
    # prior content before the call → normal path
    assert d._prefill_match("(def x 1) (my.plan/reconcile! ", pf) is None
    # empty draft / no prefills → no fire
    assert d._prefill_match("", pf) is None
    assert d._prefill_match("(my.plan/reconcile! ", {}) is None


def test_step_prefill_edit_locks_document(oracle):
    """The pass shape: seeded head draft + prefills → EDIT-WITH-PREFILL
    expand; the filled reconcile! form locks parse-gated in the same step;
    clamp segments (the ids) survive verbatim."""
    d, model, spy = driver([(DOC_ENTRY, False)], oracle)
    r = d.step("ctx", draft="(my.plan/reconcile! ",
               prefills={RECONCILE_HEAD: PREFILL_TMPL})
    assert r["transition"] == "expand"
    assert r["arm"] == "prefill-edit"
    assert r["prefill_head"] == RECONCILE_HEAD
    assert any(e["event"] == "prefill-expand" for e in r["events"])
    assert len(r["locked"]) == 1
    form = r["locked"][0]
    assert form.startswith("(my.plan/reconcile! {:my.plan/tree {")
    assert ':my.plan/id "a1"' in form, "id clamp survives verbatim"
    assert ':my.plan/title "t1"' in form
    assert r["new_draft"] == ""
    # buffer picture rides: clamped id spans + locked overlay
    statuses = {s["status"] for s in r["buffer_spans"]}
    assert "locked" in statuses


def test_step_prefill_broken_edit_keeps_caller_draft(oracle):
    """A junk edit never rides forward: the ids stay clamped in the
    assembled text, the oracle rejects the broken fill, the caller keeps
    its OWN draft (the typed head), hints report why."""
    d, model, spy = driver([("]]]]", False)], oracle)
    r = d.step("ctx", draft="(my.plan/reconcile! ",
               prefills={RECONCILE_HEAD: PREFILL_TMPL})
    assert r["transition"] == "expand" and r["arm"] == "prefill-edit"
    assert r["locked"] == []
    assert r["new_draft"] == "(my.plan/reconcile! "
    assert any(e["event"] == "expand-failed" for e in r["events"])


def test_step_no_prefill_takes_normal_path(oracle):
    """No prefills on the wire → the plain step path, byte-identical
    behavior (progress arm)."""
    d, _, _ = driver(["(def a 1)"], oracle)
    r = d.step("ctx", draft="")
    assert r["transition"] in ("progress", "done")
    assert not any(e["event"] == "prefill-expand" for e in r["events"])
