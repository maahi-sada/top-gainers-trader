"""
Scoring engine.
Takes a list of gainer dicts + their candle DataFrames.
Returns the single highest-scoring candidate, or None if nothing qualifies.
"""

import logging
import pandas as pd
from typing import Optional

from core.indicators import (
    vwap, ema, atr, opening_range, is_hh_hl,
    has_nearby_resistance, spread_pct, momentum_score
)
from config.settings import MIN_SCORE, MIN_REL_VOLUME, CANDLE_INTERVAL

log = logging.getLogger(__name__)

# ── Score weights (must sum to 100) ───────────────────────────────────────
WEIGHTS = {
    "rel_volume"  : 25,
    "momentum"    : 20,
    "trend"       : 15,
    "vol_confirm" : 10,
    "vwap"        : 10,
    "liquidity"   : 10,
    "spread"      : 5,
    "breakout"    : 5,
}


def score_candidate(candidate: dict, df: pd.DataFrame) -> dict:
    """
    Score a single candidate. Returns candidate dict enriched with:
      score, score_breakdown, entry_valid (bool), rejection_reason (str)
    """
    sym = candidate["symbol"]
    ltp = candidate["ltp"]

    if df.empty or len(df) < 20:
        return {**candidate, "score": 0, "entry_valid": False,
                "rejection_reason": "Insufficient candle data"}

    scores = {}

    # 1. Relative volume (25 pts)
    rv = candidate.get("rel_vol", 1.0)
    scores["rel_volume"] = min((rv / 5.0) * WEIGHTS["rel_volume"], WEIGHTS["rel_volume"])

    # 2. Momentum (20 pts)
    mom = momentum_score(df, periods=5)
    scores["momentum"] = (mom / 100) * WEIGHTS["momentum"]

    # 3. Trend strength — EMA slope (15 pts)
    ema20 = ema(df["close"], 20)
    ema_slope = (ema20.iloc[-1] - ema20.iloc[-5]) / ema20.iloc[-5] * 100
    scores["trend"] = min(max(ema_slope * 50, 0), WEIGHTS["trend"])

    # 4. Volume confirmation — last 3 bars increasing (10 pts)
    last_vols = df["volume"].tail(3).values
    vol_increasing = all(last_vols[i] >= last_vols[i-1] for i in range(1, 3))
    scores["vol_confirm"] = WEIGHTS["vol_confirm"] if vol_increasing else 0

    # 5. VWAP position (10 pts)
    vwap_series = vwap(df)
    current_vwap = vwap_series.iloc[-1]
    vwap_gap_pct  = (ltp - current_vwap) / current_vwap * 100
    if vwap_gap_pct > 0:
        scores["vwap"] = min(vwap_gap_pct * 5, WEIGHTS["vwap"])
    else:
        scores["vwap"] = 0

    # 6. Liquidity (10 pts)
    dv = candidate.get("daily_value_cr", 0)
    scores["liquidity"] = min((dv / 200) * WEIGHTS["liquidity"], WEIGHTS["liquidity"])

    # 7. Spread quality (5 pts)
    sp = spread_pct(candidate.get("bid", 0), candidate.get("ask", ltp))
    scores["spread"] = WEIGHTS["spread"] if sp < 0.1 else (WEIGHTS["spread"] * 0.5 if sp < 0.2 else 0)

    # 8. Breakout quality (5 pts)
    orb = opening_range(df, minutes=30)
    above_orb = ltp > (orb["orb_high"] or 0)
    above_prev_high = ltp > df["high"].iloc[-6:-1].max() if len(df) >= 6 else False
    if above_orb and above_prev_high:
        scores["breakout"] = WEIGHTS["breakout"]
    elif above_orb or above_prev_high:
        scores["breakout"] = WEIGHTS["breakout"] * 0.5
    else:
        scores["breakout"] = 0

    total_score = sum(scores.values())

    # ── Entry confirmation gates ──────────────────────────────────────────
    rejection = _check_entry_gates(candidate, df, ltp, current_vwap, ema20.iloc[-1], orb)

    return {
        **candidate,
        "score"            : round(total_score, 2),
        "score_breakdown"  : scores,
        "vwap_value"       : round(current_vwap, 2),
        "ema20_value"      : round(ema20.iloc[-1], 2),
        "orb_high"         : orb["orb_high"],
        "orb_low"          : orb["orb_low"],
        "entry_valid"      : rejection is None and total_score >= MIN_SCORE,
        "rejection_reason" : rejection or (f"Score {total_score:.1f} < min {MIN_SCORE}" if total_score < MIN_SCORE else None),
    }


def _check_entry_gates(candidate, df, ltp, current_vwap, current_ema20, orb) -> Optional[str]:
    """Returns rejection reason string, or None if all gates pass."""

    # Price above VWAP
    if ltp <= current_vwap:
        return f"Price ₹{ltp} below VWAP ₹{current_vwap:.2f}"

    # Price above 20 EMA
    if ltp <= current_ema20:
        return f"Price below EMA20 ₹{current_ema20:.2f}"

    # HH + HL structure
    if not is_hh_hl(df, lookback=3):
        return "No HH+HL structure on last 3 bars"

    # No nearby resistance
    if has_nearby_resistance(df, ltp, pct_threshold=1.5):
        return "Resistance within 1.5% above entry"

    # Spread check
    sp = spread_pct(candidate.get("bid", 0), candidate.get("ask", ltp))
    if sp > 0.25:
        return f"Spread too wide: {sp:.2f}%"

    # Rel volume
    if candidate.get("rel_vol", 0) < MIN_REL_VOLUME:
        return f"Rel vol {candidate.get('rel_vol')} < min {MIN_REL_VOLUME}"

    return None


def select_best_candidate(candidates: list[dict], candle_map: dict[str, pd.DataFrame]) -> Optional[dict]:
    """
    Score all candidates, return the single highest-scoring valid one.
    candle_map: {symbol: DataFrame}
    """
    scored = []
    for c in candidates:
        df = candle_map.get(c["symbol"], pd.DataFrame())
        result = score_candidate(c, df)
        log.info(f"[SCORE] {c['symbol']:25s} score={result['score']:5.1f} "
                 f"valid={result['entry_valid']} "
                 f"reason={result.get('rejection_reason', '')}")
        if result["entry_valid"]:
            scored.append(result)

    if not scored:
        return None

    scored.sort(key=lambda x: x["score"], reverse=True)
    best = scored[0]
    log.info(f"[SCORE] ✅ Best candidate: {best['symbol']} score={best['score']}")
    return best

