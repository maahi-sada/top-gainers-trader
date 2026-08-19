/* Paisa — data layer.
 * Everything lives in one JSON blob in localStorage. Money is stored in paise
 * (integers) so that no rupee is ever lost to floating point drift. */
(function (global) {
  'use strict';

  var KEY = 'paisa.data.v1';
  var SCHEMA = 1;

  /* ---------- helpers ---------- */

  function uid(prefix) {
    return (prefix || 'id') + '_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function today() {
    var d = new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  /* Money in/out. Rupees (float, from the UI) <-> paise (int, stored). */
  function toPaise(rupees) {
    var n = typeof rupees === 'number' ? rupees : parseFloat(String(rupees).replace(/[^0-9.\-]/g, ''));
    if (!isFinite(n)) return 0;
    return Math.round(n * 100);
  }
  function toRupees(paise) { return (paise || 0) / 100; }

  /* ---------- seed ---------- */

  var DEFAULT_CATEGORIES = [
    { name: 'Food & Dining', kind: 'expense', color: '#f97316' },
    { name: 'Groceries', kind: 'expense', color: '#84cc16' },
    { name: 'Transport & Fuel', kind: 'expense', color: '#0ea5e9' },
    { name: 'Rent', kind: 'expense', color: '#8b5cf6' },
    { name: 'Bills & Recharge', kind: 'expense', color: '#06b6d4' },
    { name: 'Shopping', kind: 'expense', color: '#ec4899' },
    { name: 'Health', kind: 'expense', color: '#ef4444' },
    { name: 'Education', kind: 'expense', color: '#6366f1' },
    { name: 'Entertainment', kind: 'expense', color: '#f43f5e' },
    { name: 'Travel', kind: 'expense', color: '#14b8a6' },
    { name: 'Family & Gifts', kind: 'expense', color: '#a855f7' },
    { name: 'Interest Paid', kind: 'expense', color: '#dc2626' },
    { name: 'Other Expense', kind: 'expense', color: '#94a3b8' },
    { name: 'Salary', kind: 'income', color: '#22c55e' },
    { name: 'Business', kind: 'income', color: '#10b981' },
    { name: 'Freelance', kind: 'income', color: '#34d399' },
    { name: 'Interest Received', kind: 'income', color: '#059669' },
    { name: 'Investment Returns', kind: 'income', color: '#16a34a' },
    { name: 'Refund', kind: 'income', color: '#65a30d' },
    { name: 'Other Income', kind: 'income', color: '#94a3b8' }
  ];

  var DEFAULT_ACCOUNTS = [
    { name: 'Cash', type: 'cash', openingBalance: 0 },
    { name: 'Bank Account', type: 'bank', openingBalance: 0 },
    { name: 'UPI / Wallet', type: 'wallet', openingBalance: 0 }
  ];

  function seed() {
    var data = {
      schema: SCHEMA,
      settings: {
        currency: 'INR',
        locale: 'en-IN',
        theme: 'auto',
        monthStartDay: 1,
        monthlyBudget: 0
      },
      accounts: [],
      categories: [],
      transactions: [],
      debts: [],
      createdAt: new Date().toISOString()
    };
    DEFAULT_ACCOUNTS.forEach(function (a) {
      data.accounts.push({
        id: uid('acc'), name: a.name, type: a.type,
        openingBalance: toPaise(a.openingBalance), archived: false
      });
    });
    DEFAULT_CATEGORIES.forEach(function (c) {
      data.categories.push({ id: uid('cat'), name: c.name, kind: c.kind, color: c.color, archived: false });
    });
    return data;
  }

  /* ---------- persistence ---------- */

  var data = null;
  var listeners = [];

  function load() {
    var raw = null;
    try { raw = global.localStorage.getItem(KEY); } catch (e) { raw = null; }
    if (!raw) { data = seed(); save(); return data; }
    try {
      var parsed = JSON.parse(raw);
      data = migrate(parsed);
    } catch (e) {
      console.error('Could not read saved data, starting fresh.', e);
      data = seed();
    }
    return data;
  }

  function migrate(d) {
    if (!d || typeof d !== 'object') return seed();
    if (!d.schema) d.schema = SCHEMA;
    d.settings = Object.assign(seed().settings, d.settings || {});
    ['accounts', 'categories', 'transactions', 'debts'].forEach(function (k) {
      if (!Array.isArray(d[k])) d[k] = [];
    });
    return d;
  }

  function save() {
    try {
      global.localStorage.setItem(KEY, JSON.stringify(data));
    } catch (e) {
      alert('Could not save — device storage may be full. Export a backup from Settings.');
      console.error(e);
    }
    listeners.forEach(function (fn) { try { fn(); } catch (e) { console.error(e); } });
  }

  function onChange(fn) { listeners.push(fn); }

  /* ---------- transaction semantics ----------
   * income  : money in,  counts as earnings
   * expense : money out, counts as spending
   * transfer: between own accounts, neutral
   * lend    : money out to a person -> creates/increases a receivable
   * collect : money in from that person -> reduces the receivable
   * borrow  : money in from a person  -> creates/increases a payable
   * settle  : money out to that person -> reduces the payable
   *
   * `interest` (paise) on collect/settle is the slice that is NOT principal:
   * it counts as income (collect) or expense (settle) and never touches the
   * outstanding balance.
   */

  var INFLOW = { income: 1, collect: 1, borrow: 1 };
  var OUTFLOW = { expense: 1, lend: 1, settle: 1 };

  function isInflow(t) { return !!INFLOW[t.type]; }
  function isOutflow(t) { return !!OUTFLOW[t.type]; }

  /* Principal portion of a debt movement. */
  function principalOf(t) {
    return Math.max(0, (t.amount || 0) - (t.interest || 0));
  }

  /* ---------- queries ---------- */

  function all() { return data; }
  function settings() { return data.settings; }

  function accounts(includeArchived) {
    return data.accounts.filter(function (a) { return includeArchived || !a.archived; });
  }
  function account(id) { return data.accounts.filter(function (a) { return a.id === id; })[0] || null; }

  function categories(kind, includeArchived) {
    return data.categories.filter(function (c) {
      if (!includeArchived && c.archived) return false;
      return !kind || c.kind === kind;
    });
  }
  function category(id) { return data.categories.filter(function (c) { return c.id === id; })[0] || null; }

  function transactions() { return data.transactions; }
  function transaction(id) { return data.transactions.filter(function (t) { return t.id === id; })[0] || null; }

  /* All debts. Views decide whether to show fully-settled ones. */
  function debts() { return data.debts.slice(); }
  function debt(id) { return data.debts.filter(function (d) { return d.id === id; })[0] || null; }

  function sortedTransactions() {
    return data.transactions.slice().sort(function (a, b) {
      if (a.date === b.date) return (b.createdAt || '').localeCompare(a.createdAt || '');
      return b.date.localeCompare(a.date);
    });
  }

  /* Balance of one account, in paise. */
  function accountBalance(accountId) {
    var acc = account(accountId);
    if (!acc) return 0;
    var bal = acc.openingBalance || 0;
    data.transactions.forEach(function (t) {
      if (t.type === 'transfer') {
        if (t.accountId === accountId) bal -= t.amount;
        if (t.toAccountId === accountId) bal += t.amount;
        return;
      }
      if (t.accountId !== accountId) return;
      if (isInflow(t)) bal += t.amount;
      else if (isOutflow(t)) bal -= t.amount;
    });
    return bal;
  }

  function totalBalance() {
    return accounts(true).reduce(function (sum, a) { return sum + accountBalance(a.id); }, 0);
  }

  /* Outstanding principal on a debt, in paise. Always >= 0 in normal use. */
  function outstanding(debtId) {
    var d = debt(debtId);
    if (!d) return 0;
    var out = 0;
    data.transactions.forEach(function (t) {
      if (t.debtId !== debtId) return;
      if (t.type === 'lend' || t.type === 'borrow') out += t.amount;
      else if (t.type === 'collect' || t.type === 'settle') out -= principalOf(t);
    });
    return out;
  }

  /* Everything people owe me, and everything I owe, in paise. */
  function receivables() {
    return data.debts.reduce(function (s, d) {
      return d.direction === 'owed' ? s + Math.max(0, outstanding(d.id)) : s;
    }, 0);
  }
  function payables() {
    return data.debts.reduce(function (s, d) {
      return d.direction === 'owe' ? s + Math.max(0, outstanding(d.id)) : s;
    }, 0);
  }
  function netWorth() { return totalBalance() + receivables() - payables(); }

  /* ---------- period helpers ---------- */

  /* Returns {from, to} ISO dates for the financial month containing `ref`,
   * honouring settings.monthStartDay (e.g. salary on the 5th). */
  function monthRange(ref, offset) {
    var startDay = Math.min(28, Math.max(1, parseInt(data.settings.monthStartDay, 10) || 1));
    var d = ref ? new Date(ref + 'T00:00:00') : new Date();
    var y = d.getFullYear(), m = d.getMonth();
    if (d.getDate() < startDay) m -= 1;
    m += (offset || 0);
    var from = new Date(y, m, startDay);
    var to = new Date(y, m + 1, startDay - 1);
    return { from: iso(from), to: iso(to), label: from.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' }) };
  }

  function iso(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }

  function inRange(t, from, to) {
    return (!from || t.date >= from) && (!to || t.date <= to);
  }

  /* Income / expense totals for a window. Debt movements are excluded on
   * purpose — borrowing is not income and lending is not spending — but the
   * interest slice of a settle/collect IS counted. */
  function summary(from, to) {
    var income = 0, expense = 0;
    data.transactions.forEach(function (t) {
      if (!inRange(t, from, to)) return;
      if (t.type === 'income') income += t.amount;
      else if (t.type === 'expense') expense += t.amount;
      else if (t.type === 'collect') income += (t.interest || 0);
      else if (t.type === 'settle') expense += (t.interest || 0);
    });
    return { income: income, expense: expense, net: income - expense };
  }

  function categoryByName(name) {
    return data.categories.filter(function (c) { return c.name === name; })[0] || null;
  }

  /* Spend (or earn) grouped by category for a window. Debt interest carries no
   * category of its own, so it is folded into the interest bucket — that keeps
   * the category totals equal to the headline income/expense figures. */
  function byCategory(kind, from, to) {
    var map = {};

    function bucket(key, label, color) {
      if (!map[key]) map[key] = { id: key, label: label, color: color, value: 0, count: 0 };
      return map[key];
    }

    var interestFrom = kind === 'expense' ? 'settle' : (kind === 'income' ? 'collect' : null);
    var interestName = kind === 'expense' ? 'Interest Paid' : 'Interest Received';

    data.transactions.forEach(function (t) {
      if (!inRange(t, from, to)) return;

      if (t.type === kind) {
        var c = category(t.categoryId);
        var b = bucket(c ? c.id : 'uncategorised', c ? c.name : 'Uncategorised', c ? c.color : '#94a3b8');
        b.value += t.amount;
        b.count += 1;
        return;
      }

      if (interestFrom && t.type === interestFrom && t.interest > 0) {
        var known = categoryByName(interestName);
        var ib = bucket(known ? known.id : 'interest', interestName, known ? known.color : '#94a3b8');
        ib.value += t.interest;
        ib.count += 1;
      }
    });

    return Object.keys(map).map(function (k) { return map[k]; })
      .sort(function (a, b) { return b.value - a.value; });
  }

  /* ---------- mutations ---------- */

  function addTransaction(t) {
    var rec = {
      id: uid('txn'),
      type: t.type,
      amount: t.amount,
      interest: t.interest || 0,
      date: t.date || today(),
      accountId: t.accountId || null,
      toAccountId: t.toAccountId || null,
      categoryId: t.categoryId || null,
      debtId: t.debtId || null,
      note: (t.note || '').trim(),
      createdAt: new Date().toISOString()
    };
    data.transactions.push(rec);
    save();
    return rec;
  }

  function updateTransaction(id, patch) {
    var t = transaction(id);
    if (!t) return null;
    Object.keys(patch).forEach(function (k) { t[k] = patch[k]; });
    t.updatedAt = new Date().toISOString();
    save();
    return t;
  }

  function deleteTransaction(id) {
    data.transactions = data.transactions.filter(function (t) { return t.id !== id; });
    save();
  }

  function addDebt(d) {
    var rec = {
      id: uid('debt'),
      direction: d.direction,          /* 'owe' = I owe them, 'owed' = they owe me */
      person: (d.person || '').trim(),
      note: (d.note || '').trim(),
      dueDate: d.dueDate || null,
      archived: false,
      createdAt: new Date().toISOString()
    };
    data.debts.push(rec);
    save();
    return rec;
  }

  function updateDebt(id, patch) {
    var d = debt(id);
    if (!d) return null;
    Object.keys(patch).forEach(function (k) { d[k] = patch[k]; });
    save();
    return d;
  }

  function deleteDebt(id) {
    data.debts = data.debts.filter(function (d) { return d.id !== id; });
    data.transactions = data.transactions.filter(function (t) { return t.debtId !== id; });
    save();
  }

  function addAccount(a) {
    var rec = {
      id: uid('acc'), name: (a.name || 'Account').trim(), type: a.type || 'bank',
      openingBalance: a.openingBalance || 0, archived: false
    };
    data.accounts.push(rec);
    save();
    return rec;
  }
  function updateAccount(id, patch) {
    var a = account(id);
    if (!a) return null;
    Object.keys(patch).forEach(function (k) { a[k] = patch[k]; });
    save();
    return a;
  }
  function deleteAccount(id) {
    var used = data.transactions.some(function (t) { return t.accountId === id || t.toAccountId === id; });
    if (used) { updateAccount(id, { archived: true }); return false; }
    data.accounts = data.accounts.filter(function (a) { return a.id !== id; });
    save();
    return true;
  }

  function addCategory(c) {
    var rec = {
      id: uid('cat'), name: (c.name || 'Category').trim(), kind: c.kind || 'expense',
      color: c.color || '#94a3b8', archived: false
    };
    data.categories.push(rec);
    save();
    return rec;
  }
  function updateCategory(id, patch) {
    var c = category(id);
    if (!c) return null;
    Object.keys(patch).forEach(function (k) { c[k] = patch[k]; });
    save();
    return c;
  }
  function deleteCategory(id) {
    var used = data.transactions.some(function (t) { return t.categoryId === id; });
    if (used) { updateCategory(id, { archived: true }); return false; }
    data.categories = data.categories.filter(function (c) { return c.id !== id; });
    save();
    return true;
  }

  function updateSettings(patch) {
    Object.keys(patch).forEach(function (k) { data.settings[k] = patch[k]; });
    save();
  }

  /* ---------- backup ---------- */

  function exportJSON() { return JSON.stringify(data, null, 2); }

  /* mode: 'replace' wipes what is here; 'merge' keeps both, skipping records
   * whose id already exists. */
  function importJSON(text, mode) {
    var incoming = JSON.parse(text);
    if (!incoming || !Array.isArray(incoming.transactions)) {
      throw new Error('That file does not look like a Paisa backup.');
    }
    if (mode === 'replace') {
      data = migrate(incoming);
      save();
      return { added: incoming.transactions.length, skipped: 0 };
    }
    var added = 0, skipped = 0;
    ['accounts', 'categories', 'debts', 'transactions'].forEach(function (key) {
      var existing = {};
      data[key].forEach(function (r) { existing[r.id] = true; });
      (incoming[key] || []).forEach(function (r) {
        if (existing[r.id]) { skipped++; return; }
        data[key].push(r);
        added++;
      });
    });
    save();
    return { added: added, skipped: skipped };
  }

  function exportCSV() {
    var head = ['Date', 'Type', 'Amount (INR)', 'Interest (INR)', 'Category', 'Account', 'To Account', 'Person', 'Note'];
    var rows = sortedTransactions().map(function (t) {
      var d = t.debtId ? debt(t.debtId) : null;
      var c = t.categoryId ? category(t.categoryId) : null;
      var a = t.accountId ? account(t.accountId) : null;
      var a2 = t.toAccountId ? account(t.toAccountId) : null;
      return [
        t.date, t.type, toRupees(t.amount).toFixed(2), toRupees(t.interest || 0).toFixed(2),
        c ? c.name : '', a ? a.name : '', a2 ? a2.name : '', d ? d.person : '', t.note || ''
      ];
    });
    return [head].concat(rows).map(function (r) {
      return r.map(function (cell) {
        var s = String(cell == null ? '' : cell);
        return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
      }).join(',');
    }).join('\n');
  }

  function reset() {
    data = seed();
    save();
  }

  global.Store = {
    SCHEMA: SCHEMA,
    load: load, save: save, onChange: onChange,
    uid: uid, today: today, toPaise: toPaise, toRupees: toRupees,
    all: all, settings: settings, updateSettings: updateSettings,
    accounts: accounts, account: account, addAccount: addAccount,
    updateAccount: updateAccount, deleteAccount: deleteAccount,
    categories: categories, category: category, addCategory: addCategory,
    updateCategory: updateCategory, deleteCategory: deleteCategory,
    transactions: transactions, transaction: transaction, sortedTransactions: sortedTransactions,
    addTransaction: addTransaction, updateTransaction: updateTransaction, deleteTransaction: deleteTransaction,
    debts: debts, debt: debt, addDebt: addDebt, updateDebt: updateDebt, deleteDebt: deleteDebt,
    accountBalance: accountBalance, totalBalance: totalBalance,
    outstanding: outstanding, receivables: receivables, payables: payables, netWorth: netWorth,
    monthRange: monthRange, inRange: inRange, summary: summary, byCategory: byCategory,
    isInflow: isInflow, isOutflow: isOutflow, principalOf: principalOf,
    exportJSON: exportJSON, importJSON: importJSON, exportCSV: exportCSV, reset: reset
  };
})(window);
