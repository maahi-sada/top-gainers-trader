"""
Database: trades.db
Tables:
  - trades        : one row per completed trade
  - daily_memory  : AI memory — daily session stats for adaptive risk
  - pattern_memory: AI memory — which market conditions led to losses/wins
"""

import sqlite3
import os
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "trades.db"


def get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init_db():
    with get_conn() as conn:
        conn.executescript("""
        CREATE TABLE IF NOT EXISTS trades (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            date            TEXT NOT NULL,
            entry_time      TEXT NOT NULL,
            exit_time       TEXT,
            symbol          TEXT NOT NULL,
            entry_price     REAL NOT NULL,
            exit_price      REAL,
            sl_price        REAL NOT NULL,
            target_price    REAL NOT NULL,
            qty             INTEGER NOT NULL,
            risk_amount     REAL NOT NULL,
            risk_pct_used   REAL NOT NULL,
            gross_pnl       REAL,
            net_pnl         REAL,
            total_cost      REAL,
            r_multiple      REAL,
            exit_reason     TEXT,          -- SL / TARGET / TRAIL / FORCE / PARTIAL
            sl_model        TEXT,          -- ATR / PREV_CANDLE / SWING / VWAP
            score_at_entry  REAL,
            rel_vol         REAL,
            regime          TEXT,          -- TRENDING / CHOPPY / VOLATILE
            paper           INTEGER DEFAULT 1,
            created_at      TEXT DEFAULT (datetime('now','localtime'))
        );

        CREATE TABLE IF NOT EXISTS daily_memory (
            date                TEXT PRIMARY KEY,
            trades_taken        INTEGER DEFAULT 0,
            wins                INTEGER DEFAULT 0,
            losses              INTEGER DEFAULT 0,
            consec_losses       INTEGER DEFAULT 0,
            max_consec_losses   INTEGER DEFAULT 0,
            gross_pnl           REAL DEFAULT 0,
            net_pnl             REAL DEFAULT 0,
            risk_pct_start      REAL,
            risk_pct_end        REAL,       -- what risk ended the day at
            session_stopped     INTEGER DEFAULT 0,
            stop_reason         TEXT,
            notes               TEXT,
            created_at          TEXT DEFAULT (datetime('now','localtime'))
        );

        CREATE TABLE IF NOT EXISTS pattern_memory (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            date            TEXT NOT NULL,
            pattern_key     TEXT NOT NULL,  -- e.g. "HIGH_VIX+MORNING" or "CHOPPY+LOW_RELVOL"
            outcome         TEXT NOT NULL,  -- WIN / LOSS
            r_multiple      REAL,
            notes           TEXT,
            created_at      TEXT DEFAULT (datetime('now','localtime'))
        );

        CREATE INDEX IF NOT EXISTS idx_trades_date ON trades(date);
        CREATE INDEX IF NOT EXISTS idx_pattern_key ON pattern_memory(pattern_key, outcome);
        """)
    print(f"[DB] Initialised at {DB_PATH}")


if __name__ == "__main__":
    init_db()
