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
        monthlyBudget: 0,
        autoConfirm: false,      /* log a parsed message straight away when a rule matches */
        accountTails: {}         /* last 4 digits from a bank message -> account id */
      },
      accounts: [],
      categories: [],
      transactions: [],
      debts: [],
      inbox: [],
      rules: [],
      recurring: [],
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
    ['accounts', 'categories', 'transactions', 'debts', 'inbox', 'rules', 'recurring'].forEach(function (k) {
      if (!Array.isArray(d[k])) d[k] = [];
    });
    if (!d.settings.accountTails || typeof d.settings.accountTails !== 'object') d.settings.accountTails = {};
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
      fp: t.fp || null,          /* set when the entry came from a message or a schedule */
      source: t.source || 'manual',
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
    /* Credit card facts, whether typed in or read off a statement. Carried
     * through so the Android app and this one describe a card the same way. */
    CARD_FIELDS.forEach(function (k) { if (a[k] !== undefined && a[k] !== null) rec[k] = a[k]; });
    data.accounts.push(rec);
    save();
    return rec;
  }

  var CARD_FIELDS = ['creditLimit', 'statementDay', 'dueDay', 'last4',
    'lastStatementDate', 'lastStatementDue', 'lastMinimumDue', 'detailsFrom'];

  /* Files what a statement said about a card: its limit, when the bill closes
   * and when it falls due. Creating the card if this is the first sight of it,
   * and remembering its digits so later purchases route themselves.
   *
   * All the deciding happens in cardstatement.js, which is tested on its own;
   * this only persists the answer. */
  function applyCardStatement(statement) {
    var result = global.CardStatement.apply(statement, data.accounts, data.settings.accountTails, today());
    if (!result.applied) return result;

    var account;
    if (result.created) {
      account = addAccount(result.account);
    } else {
      account = updateAccount(result.accountId, result.patch);
    }
    if (!account) return { applied: false, created: false, changes: [], reason: 'That card is gone' };

    rememberTail(statement.last4, account.id);
    return {
      applied: true, created: result.created, accountId: account.id,
      account: account, changes: result.changes,
      describe: global.CardStatement.summarise(result, account.name)
    };
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

  /* ---------- automation: inbox, rules, recurring ----------
   * Anything captured automatically (a shared bank message, a statement row, a
   * due recurring entry) lands in the inbox first. Nothing reaches the ledger
   * without either a rule that says it is safe or a tap from the user. */

  function inbox() {
    return data.inbox.slice().sort(function (a, b) {
      return (b.receivedAt || '').localeCompare(a.receivedAt || '');
    });
  }

  /* Fingerprints of everything already logged, so the same message imported
   * twice does not become two entries. */
  function knownFingerprints() {
    var set = {};
    data.transactions.forEach(function (t) { if (t.fp) set[t.fp] = true; });
    data.inbox.forEach(function (i) { if (i.fp) set[i.fp] = true; });
    return set;
  }

  /* parsed: the object from Parse.parse(), or a statement row shaped like one.
   * Returns {status: 'added'|'duplicate'|'rejected', item, why}. */
  function addToInbox(parsed, source) {
    if (!parsed || !parsed.ok) {
      return { status: 'rejected', why: (parsed && parsed.why) || 'Could not read that message' };
    }
    var fp = global.Parse ? global.Parse.fingerprint(parsed) : null;
    if (fp && knownFingerprints()[fp]) {
      return { status: 'duplicate', why: 'Already logged' };
    }
    var item = {
      id: uid('in'),
      fp: fp,
      source: source || 'paste',
      receivedAt: new Date().toISOString(),
      parsed: parsed
    };
    data.inbox.push(item);
    save();
    return { status: 'added', item: item };
  }

  function removeFromInbox(id) {
    data.inbox = data.inbox.filter(function (i) { return i.id !== id; });
    save();
  }

  function clearInbox() {
    data.inbox = [];
    save();
  }

  /* ---------- rules ----------
   * A rule maps a counterparty to a category (and optionally an account), so
   * the second Swiggy order files itself. */

  function rules() { return data.rules.slice(); }

  function normaliseMatch(text) {
    return String(text || '').toLowerCase().replace(/\s+/g, ' ').trim();
  }

  function addRule(r) {
    var match = normaliseMatch(r.match);
    if (!match) return null;
    var existing = data.rules.filter(function (x) { return x.match === match; })[0];
    if (existing) {
      if (r.categoryId) existing.categoryId = r.categoryId;
      if (r.accountId) existing.accountId = r.accountId;
      save();
      return existing;
    }
    var rec = {
      id: uid('rule'),
      match: match,
      categoryId: r.categoryId || null,
      accountId: r.accountId || null,
      hits: 0
    };
    data.rules.push(rec);
    save();
    return rec;
  }

  function deleteRule(id) {
    data.rules = data.rules.filter(function (r) { return r.id !== id; });
    save();
  }

  /* Best matching rule for a parsed message: the longest match wins, so a
   * specific "swiggy instamart" beats a general "swiggy". */
  function matchRule(parsed) {
    var hay = normaliseMatch([parsed.counterparty, parsed.vpa, parsed.raw].filter(Boolean).join(' '));
    var best = null;
    data.rules.forEach(function (r) {
      if (hay.indexOf(r.match) === -1) return;
      if (!best || r.match.length > best.match.length) best = r;
    });
    return best;
  }

  /* Which of my accounts a message refers to, learned from its last 4 digits. */
  function accountForTail(tail) {
    if (!tail) return null;
    var id = data.settings.accountTails[tail];
    return id && account(id) ? id : null;
  }

  function rememberTail(tail, accountId) {
    if (!tail || !accountId) return;
    data.settings.accountTails[tail] = accountId;
    save();
  }

  /* Fills in the blanks on a parsed message from rules, learned account tails
   * and sensible fallbacks. Never guesses the amount or the direction. */
  function suggest(parsed) {
    var rule = matchRule(parsed);
    /* A scheduled entry carries its own category and account; a parsed message
     * does not, so it falls back to rules, then to the learned account tail. */
    var accId = parsed.accountId
      || accountForTail(parsed.accountTail)
      || (rule && rule.accountId)
      || (accounts()[0] && accounts()[0].id);

    var catId = parsed.categoryId || (rule && rule.categoryId);
    if (!catId) {
      var kind = parsed.type === 'income' ? 'income' : 'expense';
      var fallback = categories(kind)[0];
      catId = fallback ? fallback.id : null;
    }
    return {
      type: parsed.type,
      amount: parsed.amount,
      date: parsed.date || today(),
      accountId: accId,
      categoryId: catId,
      note: parsed.note || parsed.counterparty || (parsed.raw || '').slice(0, 60),
      fp: global.Parse ? global.Parse.fingerprint(parsed) : null,
      matchedRule: !!rule
    };
  }

  function inboxItem(id) {
    return data.inbox.filter(function (i) { return i.id === id; })[0] || null;
  }

  /* Records what the user decided about an inbox item so the next message from
   * the same shop files itself, then drops the item. */
  function learnFromInbox(id, draft) {
    var item = inboxItem(id);
    if (!item) return;
    var key = item.parsed.vpa || item.parsed.counterparty;
    if (key && draft.categoryId) {
      var rule = addRule({ match: key, categoryId: draft.categoryId, accountId: draft.accountId });
      if (rule) rule.hits = (rule.hits || 0) + 1;
    }
    rememberTail(item.parsed.accountTail, draft.accountId);
    removeFromInbox(id);
  }

  /* Turns an inbox item into a real entry, and remembers what it was told. */
  function confirmInboxItem(id, overrides) {
    var item = inboxItem(id);
    if (!item) return null;
    var draft = Object.assign(suggest(item.parsed), overrides || {});

    var txn = addTransaction({
      type: draft.type,
      amount: draft.amount,
      date: draft.date,
      accountId: draft.accountId,
      categoryId: draft.categoryId,
      note: draft.note,
      fp: item.fp,
      source: item.source
    });

    learnFromInbox(id, draft);
    return txn;
  }

  /* ---------- recurring ----------
   * Rent, salary, EMIs, subscriptions: things that happen on a schedule and
   * should not need typing at all. */

  function recurring() { return data.recurring.slice(); }

  function addRecurring(r) {
    var rec = {
      id: uid('rec'),
      label: (r.label || 'Recurring').trim(),
      type: r.type || 'expense',
      amount: r.amount || 0,
      categoryId: r.categoryId || null,
      accountId: r.accountId || null,
      note: (r.note || '').trim(),
      freq: r.freq || 'monthly',
      day: r.day || 1,
      nextDate: r.nextDate || nextOccurrence(r.freq || 'monthly', r.day || 1, today()),
      autoPost: !!r.autoPost,
      paused: false,
      createdAt: new Date().toISOString()
    };
    data.recurring.push(rec);
    save();
    return rec;
  }

  function updateRecurring(id, patch) {
    var r = data.recurring.filter(function (x) { return x.id === id; })[0];
    if (!r) return null;
    Object.keys(patch).forEach(function (k) { r[k] = patch[k]; });
    save();
    return r;
  }

  function deleteRecurring(id) {
    data.recurring = data.recurring.filter(function (r) { return r.id !== id; });
    save();
  }

  /* First date on or after `from` that matches the schedule. */
  function nextOccurrence(freq, day, from) {
    var d = new Date(from + 'T00:00:00');
    if (freq === 'weekly') {
      var target = Math.min(6, Math.max(0, parseInt(day, 10) || 0));
      var delta = (target - d.getDay() + 7) % 7;
      d.setDate(d.getDate() + (delta === 0 ? 7 : delta));
      return iso(d);
    }
    var dom = Math.min(28, Math.max(1, parseInt(day, 10) || 1));
    var candidate = new Date(d.getFullYear(), d.getMonth(), dom);
    if (candidate <= d) candidate = new Date(d.getFullYear(), d.getMonth() + 1, dom);
    return iso(candidate);
  }

  function advance(freq, day, from) {
    var d = new Date(from + 'T00:00:00');
    if (freq === 'weekly') { d.setDate(d.getDate() + 7); return iso(d); }
    var dom = Math.min(28, Math.max(1, parseInt(day, 10) || 1));
    return iso(new Date(d.getFullYear(), d.getMonth() + 1, dom));
  }

  /* Called on every app open: posts anything now due, catching up on missed
   * periods. Auto-post templates go straight to the ledger, the rest queue in
   * the inbox for a tap. Returns {posted, queued}. */
  function runRecurring() {
    var now = today();
    var posted = 0, queued = 0;

    data.recurring.forEach(function (r) {
      if (r.paused || !r.amount) return;
      var guard = 0;
      while (r.nextDate <= now && guard < 60) {
        guard++;
        var due = r.nextDate;
        var fp = 'rec:' + r.id + ':' + due;

        if (!knownFingerprints()[fp]) {
          if (r.autoPost) {
            data.transactions.push({
              id: uid('txn'),
              type: r.type, amount: r.amount, interest: 0, date: due,
              accountId: r.accountId, toAccountId: null, categoryId: r.categoryId,
              debtId: null, note: r.note || r.label, fp: fp, source: 'recurring',
              createdAt: new Date().toISOString()
            });
            posted++;
          } else {
            data.inbox.push({
              id: uid('in'), fp: fp, source: 'recurring',
              receivedAt: new Date().toISOString(),
              parsed: {
                ok: true, confidence: 1, raw: r.label,
                type: r.type, amount: r.amount, date: due,
                counterparty: r.label, vpa: null, accountTail: null,
                bank: null, method: null, ref: null, balance: null,
                recurringId: r.id, categoryId: r.categoryId, accountId: r.accountId,
                note: r.note || r.label
              }
            });
            queued++;
          }
        }
        r.nextDate = advance(r.freq, r.day, due);
      }
    });

    if (posted || queued) save();
    return { posted: posted, queued: queued };
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
    ['accounts', 'categories', 'debts', 'transactions', 'rules', 'recurring'].forEach(function (key) {
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
    applyCardStatement: applyCardStatement,
    inbox: inbox, addToInbox: addToInbox, removeFromInbox: removeFromInbox, clearInbox: clearInbox,
    confirmInboxItem: confirmInboxItem, suggest: suggest,
    inboxItem: inboxItem, learnFromInbox: learnFromInbox,
    rules: rules, addRule: addRule, deleteRule: deleteRule, matchRule: matchRule,
    accountForTail: accountForTail, rememberTail: rememberTail,
    recurring: recurring, addRecurring: addRecurring, updateRecurring: updateRecurring,
    deleteRecurring: deleteRecurring, runRecurring: runRecurring, nextOccurrence: nextOccurrence,
    exportJSON: exportJSON, importJSON: importJSON, exportCSV: exportCSV, reset: reset
  };
})(window);
