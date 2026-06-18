"""
Analytics module.
Generates daily summary, computes performance metrics,
sends weekly report to Telegram.
"""

import logging
from datetime import date, timedelta

from core.database import get_conn
from core.notifier import notify, alert_daily_summary

log = logging.getLogger(__name__)


def compute_daily_summary(target_date: str = None) -> dict:
    d = target_date or date.today().isoformat()
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT * FROM trades WHERE date=? AND exit_time IS NOT NULL", (d,)
        ).fetchall()

    if not rows:
        return {"date": d, "trades": 0, "net_pnl": 0, "message": "No completed trades"}

    trades     = len(rows)
    wins       = sum(1 for r in rows if (r["net_pnl"] or 0) >= 0)
    losses     = trades - wins
    net_pnl    = sum(r["net_pnl"] or 0 for r in rows)
    gross_pnl  = sum(r["gross_pnl"] or 0 for r in rows)
    total_cost = sum(r["total_cost"] or 0 for r in rows)
    win_pnls   = [r["net_pnl"] for r in rows if (r["net_pnl"] or 0) >= 0]
    loss_pnls  = [r["net_pnl"] for r in rows if (r["net_pnl"] or 0) < 0]
    r_mults    = [r["r_multiple"] or 0 for r in rows]

    avg_win    = sum(win_pnls) / len(win_pnls)   if win_pnls  else 0
    avg_loss   = sum(loss_pnls) / len(loss_pnls) if loss_pnls else 0
    profit_factor = abs(sum(win_pnls) / sum(loss_pnls)) if loss_pnls and sum(loss_pnls) != 0 else 999
    expectancy    = (wins/trades * avg_win) + (losses/trades * avg_loss) if trades else 0
    avg_r         = sum(r_mults) / len(r_mults) if r_mults else 0

    # Intraday drawdown
    running_pnl = 0
    peak = 0
    max_dd = 0
    for r in sorted(rows, key=lambda x: x["exit_time"]):
        running_pnl += r["net_pnl"] or 0
        peak = max(peak, running_pnl)
        max_dd = min(max_dd, running_pnl - peak)

    mem = conn.execute("SELECT * FROM daily_memory WHERE date=?", (d,)).fetchone() if True else None
    with get_conn() as conn2:
        mem = conn2.execute("SELECT * FROM daily_memory WHERE date=?", (d,)).fetchone()

    return {
        "date"         : d,
        "trades"       : trades,
        "wins"         : wins,
        "losses"       : losses,
        "win_rate"     : round(wins / trades * 100, 1) if trades else 0,
        "gross_pnl"    : round(gross_pnl, 2),
        "net_pnl"      : round(net_pnl, 2),
        "total_cost"   : round(total_cost, 2),
        "avg_win"      : round(avg_win, 2),
        "avg_loss"     : round(avg_loss, 2),
        "profit_factor": round(profit_factor, 2),
        "expectancy"   : round(expectancy, 2),
        "avg_r"        : round(avg_r, 3),
        "max_dd"       : round(max_dd, 2),
        "risk_pct_end" : mem["risk_pct_end"] if mem else None,
    }


def send_daily_summary():
    summary = compute_daily_summary()
    if summary["trades"] == 0:
        notify(f"<b>[TRADE] 📊 DAILY SUMMARY — {summary['date']}</b>\nNo trades taken today.")
        return
    alert_daily_summary(
        summary["date"], summary["trades"], summary["wins"], summary["losses"],
        summary["net_pnl"], summary["max_dd"], summary["win_rate"],
        summary["risk_pct_end"] or 0
    )
    # Extra detail
    notify(
        f"<b>[TRADE] Detail</b>\n"
        f"Avg win    : ₹{summary['avg_win']:.2f}\n"
        f"Avg loss   : ₹{summary['avg_loss']:.2f}\n"
        f"Profit factor: {summary['profit_factor']:.2f}\n"
        f"Expectancy : ₹{summary['expectancy']:.2f}\n"
        f"Avg R      : {summary['avg_r']:.2f}R\n"
        f"Total cost : ₹{summary['total_cost']:.2f}"
    )


def send_weekly_report():
    today = date.today()
    days  = [(today - timedelta(days=i)).isoformat() for i in range(7)]
    summaries = [compute_daily_summary(d) for d in days]
    active = [s for s in summaries if s["trades"] > 0]

    if not active:
        notify("<b>[TRADE] 📅 WEEKLY REPORT</b>\nNo trades this week.")
        return

    total_trades  = sum(s["trades"]  for s in active)
    total_wins    = sum(s["wins"]    for s in active)
    total_net_pnl = sum(s["net_pnl"] for s in active)
    weekly_wr     = round(total_wins / total_trades * 100, 1) if total_trades else 0

    notify(
        f"<b>[TRADE] 📅 WEEKLY REPORT</b>\n"
        f"Period  : {days[-1]} → {days[0]}\n"
        f"Days    : {len(active)} active\n"
        f"Trades  : {total_trades}  |  Win rate: {weekly_wr}%\n"
        f"Net P&L : ₹{total_net_pnl:.2f}\n"
        f"Best day: ₹{max(s['net_pnl'] for s in active):.2f}\n"
        f"Worst   : ₹{min(s['net_pnl'] for s in active):.2f}"
    )


def get_pattern_insights() -> str:
    """Returns text summary of which patterns are working / not working."""
    with get_conn() as conn:
        rows = conn.execute("""
            SELECT pattern_key,
                   COUNT(*) as total,
                   SUM(CASE WHEN outcome='WIN' THEN 1 ELSE 0 END) as wins,
                   ROUND(AVG(r_multiple),2) as avg_r
            FROM pattern_memory
            WHERE date >= date('now','-14 days')
            GROUP BY pattern_key
            HAVING total >= 3
            ORDER BY wins*1.0/total DESC
        """).fetchall()

    if not rows:
        return "Not enough pattern data yet (need 3+ trades per pattern)."

    lines = ["<b>[TRADE] 🧠 Pattern Insights (14d)</b>"]
    for r in rows:
        wr = round(r["wins"] / r["total"] * 100, 1)
        emoji = "✅" if wr >= 55 else ("⚠️" if wr >= 45 else "❌")
        lines.append(f"{emoji} {r['pattern_key']}: WR={wr}% avgR={r['avg_r']} ({r['total']} trades)")
    return "\n".join(lines)
