/* Paisa — screen rendering. Every function returns an HTML string; all
 * interaction is wired up by app.js through delegated events. */
(function (global) {
  'use strict';

  var U = global.U, S = global.Store, C = global.Charts;
  var esc = function (s) { return U.esc(s); };

  var TYPE_META = {
    expense: { label: 'Spent', icon: '↓', cls: 'out', sign: -1 },
    income: { label: 'Earned', icon: '↑', cls: 'in', sign: 1 },
    transfer: { label: 'Transfer', icon: '⇄', cls: 'neutral', sign: 0 },
    lend: { label: 'Lent out', icon: '→', cls: 'lend', sign: -1 },
    collect: { label: 'Got back', icon: '←', cls: 'collect', sign: 1 },
    borrow: { label: 'Borrowed', icon: '←', cls: 'borrow', sign: 1 },
    settle: { label: 'Repaid', icon: '→', cls: 'settle', sign: -1 }
  };

  function typeMeta(t) { return TYPE_META[t] || TYPE_META.expense; }

  /* ---------- shared fragments ---------- */

  function emptyState(icon, title, body, actionLabel, actionAttr) {
    return '<div class="empty">' +
      '<div class="empty-icon">' + icon + '</div>' +
      '<h3>' + esc(title) + '</h3>' +
      '<p>' + esc(body) + '</p>' +
      (actionLabel ? '<button class="btn primary" ' + actionAttr + '>' + esc(actionLabel) + '</button>' : '') +
      '</div>';
  }

  /* One row in any transaction list. */
  function txnRow(t) {
    var meta = typeMeta(t.type);
    var cat = t.categoryId ? S.category(t.categoryId) : null;
    var acc = t.accountId ? S.account(t.accountId) : null;
    var acc2 = t.toAccountId ? S.account(t.toAccountId) : null;
    var d = t.debtId ? S.debt(t.debtId) : null;

    var title, sub;
    if (t.type === 'transfer') {
      title = (acc ? acc.name : '?') + ' → ' + (acc2 ? acc2.name : '?');
      sub = t.note || 'Moved between accounts';
    } else if (d) {
      title = d.person || 'Someone';
      sub = meta.label + (t.note ? ' · ' + t.note : '');
    } else {
      title = cat ? cat.name : 'Uncategorised';
      sub = (acc ? acc.name : '') + (t.note ? ' · ' + t.note : '');
    }

    var dot = cat ? cat.color : (d ? U.colorFor(d.person) : 'var(--muted)');
    var amountCls = meta.sign > 0 ? 'amt in' : (meta.sign < 0 ? 'amt out' : 'amt neutral');
    var interestNote = t.interest ? '<span class="pill tiny">incl. ' + esc(U.money(t.interest)) + ' interest</span>' : '';

    return '<li class="row txn" data-txn="' + esc(t.id) + '" tabindex="0" role="button">' +
      '<span class="row-dot" style="background:' + esc(dot) + '"><span class="row-glyph">' + meta.icon + '</span></span>' +
      '<span class="row-main">' +
        '<span class="row-title">' + esc(title) + '</span>' +
        '<span class="row-sub">' + esc(sub) + ' ' + interestNote + '</span>' +
      '</span>' +
      '<span class="' + amountCls + '">' + esc(U.signedMoney(t.amount, meta.sign)) + '</span>' +
      '</li>';
  }

  /* Transactions grouped under date headings, with a per-day net. */
  function txnGroups(list) {
    if (!list.length) return '';
    var groups = [];
    var index = {};
    list.forEach(function (t) {
      if (!index[t.date]) { index[t.date] = { date: t.date, items: [], net: 0 }; groups.push(index[t.date]); }
      index[t.date].items.push(t);
      index[t.date].net += typeMeta(t.type).sign * t.amount;
    });
    return groups.map(function (g) {
      return '<div class="group">' +
        '<div class="group-head"><span>' + esc(U.relativeDay(g.date)) + '</span>' +
        '<span class="' + (g.net >= 0 ? 'in' : 'out') + '">' + esc(U.signedMoney(g.net, g.net >= 0 ? 1 : -1)) + '</span></div>' +
        '<ul class="rows">' + g.items.map(txnRow).join('') + '</ul>' +
        '</div>';
    }).join('');
  }

  /* ---------- dashboard ---------- */

  function dashboard() {
    var range = S.monthRange(null, global.App.state.monthOffset);
    var sum = S.summary(range.from, range.to);
    var balance = S.totalBalance();
    var recv = S.receivables(), pay = S.payables();
    var budget = S.settings().monthlyBudget || 0;

    var html = '';

    html += '<section class="hero card">' +
      '<div class="hero-label">Money in hand</div>' +
      '<div class="hero-value ' + (balance < 0 ? 'out' : '') + '">' + esc(U.money(balance)) + '</div>' +
      '<div class="hero-foot">' +
        '<span>Net worth <strong>' + esc(U.money(S.netWorth())) + '</strong></span>' +
        '<span class="hero-sep"></span>' +
        '<span>' + S.accounts().length + ' accounts</span>' +
      '</div>' +
      '</section>';

    var waiting = S.inbox();
    if (waiting.length) {
      html += '<a class="card nudge" href="#/inbox">' +
        '<span class="nudge-badge">' + waiting.length + '</span>' +
        '<span class="row-main"><span class="row-title">' +
          (waiting.length === 1 ? 'One entry to review' : waiting.length + ' entries to review') + '</span>' +
        '<span class="row-sub">Captured automatically — tap to confirm</span></span>' +
        '<span class="nudge-go">→</span>' +
        '</a>';
    }

    html += '<div class="month-switch">' +
      '<button class="icon-btn" data-month="-1" aria-label="Previous month">‹</button>' +
      '<span>' + esc(range.label) + '</span>' +
      '<button class="icon-btn" data-month="1" aria-label="Next month"' +
        (global.App.state.monthOffset >= 0 ? ' disabled' : '') + '>›</button>' +
      '</div>';

    html += '<div class="tiles">' +
      tile('Earned', U.money(sum.income), 'in') +
      tile('Spent', U.money(sum.expense), 'out') +
      tile('Saved', U.signedMoney(sum.net, sum.net >= 0 ? 1 : -1), sum.net >= 0 ? 'in' : 'out') +
      '</div>';

    if (budget > 0) {
      var used = Math.min(1, sum.expense / budget);
      var over = sum.expense > budget;
      html += '<section class="card budget">' +
        '<div class="card-head"><h3>Monthly budget</h3>' +
        '<span class="' + (over ? 'out' : 'muted') + '">' + esc(U.money(sum.expense)) + ' of ' + esc(U.money(budget)) + '</span></div>' +
        '<div class="progress"><div class="progress-fill' + (over ? ' danger' : '') + '" style="width:' + (used * 100).toFixed(1) + '%"></div></div>' +
        '<p class="muted small">' + (over
          ? esc(U.money(sum.expense - budget)) + ' over budget'
          : esc(U.money(budget - sum.expense)) + ' left for this month') + '</p>' +
        '</section>';
    }

    html += '<div class="duo">' +
      debtTile('To receive', recv, 'owed') +
      debtTile('To pay', pay, 'owe') +
      '</div>';

    var cats = S.byCategory('expense', range.from, range.to).slice(0, 5);
    if (cats.length) {
      var totalSpend = S.byCategory('expense', range.from, range.to)
        .reduce(function (s, c) { return s + c.value; }, 0);
      html += '<section class="card">' +
        '<div class="card-head"><h3>Where it went</h3><a href="#/reports" class="link">Reports</a></div>' +
        '<ul class="cat-list">' + cats.map(function (c) {
          return '<li>' +
            '<span class="cat-name"><i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.label) + '</span>' +
            '<span class="cat-val">' + esc(U.money(c.value)) + '</span>' +
            C.meter(c.value / totalSpend, c.color) +
            '</li>';
        }).join('') + '</ul>' +
        '</section>';
    }

    html += cardsPanel();

    var recent = S.sortedTransactions().slice(0, 8);
    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Recent</h3><a href="#/ledger" class="link">See all</a></div>' +
      (recent.length ? '<ul class="rows">' + recent.map(txnRow).join('') + '</ul>'
        : emptyState('₹', 'Nothing recorded yet',
            'Tap the + button to log your first rupee — a chai, a salary, a loan to a friend.',
            'Add the first entry', 'data-open-add="1"')) +
      '</section>';

    return html;
  }

  /* Credit cards, each with what it owes, how much of the limit that is, and
   * when the bill lands. The limit and the dates come off your statements if
   * you have shared any into Paisa; otherwise from what you typed. */
  function cardsPanel() {
    var cards = S.accounts().filter(function (a) { return a.type === 'card'; });
    if (!cards.length) return '';

    var ordinal = global.CardStatement.ordinal;

    return '<section class="card">' +
      '<div class="card-head"><h3>Credit cards</h3><a href="#/settings" class="link">Manage</a></div>' +
      '<ul class="cat-list">' + cards.map(function (a) {
        var owed = Math.max(0, -S.accountBalance(a.id));
        var limit = a.creditLimit || 0;
        var used = limit > 0 ? Math.min(1, owed / limit) : 0;
        var line = '<li>' +
          '<span class="cat-name">' + esc(a.name) + (a.last4 ? ' <span class="pill">••' + esc(a.last4) + '</span>' : '') + '</span>' +
          '<span class="cat-val ' + (owed > 0 ? 'out' : 'in') + '">' + esc(U.money(owed)) + '</span>';

        if (limit > 0) {
          line += C.meter(used, used > 0.7 ? '#ef4444' : '#6366f1') +
            '<span class="muted small">' + Math.round(used * 100) + '% of ' + esc(U.money(limit)) +
            ' used · ' + esc(U.money(Math.max(0, limit - owed))) + ' available</span>';
        }

        line += '<span class="muted small">Statement closes on the ' + ordinal(a.statementDay || 1) +
          ', bill due on the ' + ordinal(a.dueDay || 1) + '.</span>';

        if (a.lastStatementDue || a.lastMinimumDue) {
          line += '<span class="muted small">Bank billed ' + esc(U.money(a.lastStatementDue || 0)) +
            ' · minimum ' + esc(U.money(a.lastMinimumDue || 0)) + '</span>';
        }
        if (a.detailsFrom) {
          line += '<span class="muted small">Limit and dates read from your ' + esc(a.detailsFrom) + '.</span>';
        }
        return line + '</li>';
      }).join('') + '</ul>' +
      '</section>';
  }

  function tile(label, value, cls) {
    return '<div class="tile"><span class="tile-label">' + esc(label) + '</span>' +
      '<span class="tile-value ' + cls + '">' + esc(value) + '</span></div>';
  }

  function debtTile(label, amount, direction) {
    var people = S.debts().filter(function (d) {
      return d.direction === direction && S.outstanding(d.id) > 0;
    }).length;
    return '<a class="card mini" href="#/debts">' +
      '<span class="tile-label">' + esc(label) + '</span>' +
      '<span class="mini-value ' + (direction === 'owed' ? 'in' : 'out') + '">' + esc(U.money(amount)) + '</span>' +
      '<span class="muted small">' + people + (people === 1 ? ' person' : ' people') + '</span>' +
      '</a>';
  }

  /* ---------- ledger ---------- */

  function ledger() {
    var st = global.App.state;
    var q = (st.search || '').toLowerCase().trim();
    var list = S.sortedTransactions().filter(function (t) {
      if (st.filterType !== 'all') {
        if (st.filterType === 'debt') {
          if (!t.debtId) return false;
        } else if (t.type !== st.filterType) return false;
      }
      if (st.filterAccount !== 'all' && t.accountId !== st.filterAccount && t.toAccountId !== st.filterAccount) return false;
      if (st.filterMonth !== 'all') {
        var r = S.monthRange(st.filterMonth + '-15', 0);
        if (!S.inRange(t, r.from, r.to)) return false;
      }
      if (!q) return true;
      var cat = t.categoryId ? S.category(t.categoryId) : null;
      var d = t.debtId ? S.debt(t.debtId) : null;
      var acc = t.accountId ? S.account(t.accountId) : null;
      var hay = [t.note, cat && cat.name, d && d.person, acc && acc.name, U.num(t.amount)]
        .filter(Boolean).join(' ').toLowerCase();
      return hay.indexOf(q) !== -1;
    });

    var totals = list.reduce(function (a, t) {
      var s = typeMeta(t.type).sign;
      if (s > 0) a.in += t.amount; else if (s < 0) a.out += t.amount;
      return a;
    }, { in: 0, out: 0 });

    var months = monthOptions();

    var html = '<div class="toolbar">' +
      '<input id="search" class="input search" type="search" placeholder="Search notes, people, amounts…" value="' + esc(st.search || '') + '" autocomplete="off">' +
      '</div>';

    html += '<div class="chips scroll-x">' +
      ['all', 'expense', 'income', 'transfer', 'debt'].map(function (k) {
        var label = k === 'all' ? 'Everything' : (k === 'debt' ? 'Debt moves' : typeMeta(k).label);
        return '<button class="chip' + (st.filterType === k ? ' on' : '') + '" data-filter-type="' + k + '">' + esc(label) + '</button>';
      }).join('') +
      '</div>';

    html += '<div class="filters">' +
      '<select class="input" data-filter-month>' +
        '<option value="all"' + (st.filterMonth === 'all' ? ' selected' : '') + '>All months</option>' +
        months.map(function (m) {
          return '<option value="' + esc(m.value) + '"' + (st.filterMonth === m.value ? ' selected' : '') + '>' + esc(m.label) + '</option>';
        }).join('') +
      '</select>' +
      '<select class="input" data-filter-account>' +
        '<option value="all"' + (st.filterAccount === 'all' ? ' selected' : '') + '>All accounts</option>' +
        S.accounts(true).map(function (a) {
          return '<option value="' + esc(a.id) + '"' + (st.filterAccount === a.id ? ' selected' : '') + '>' + esc(a.name) + '</option>';
        }).join('') +
      '</select>' +
      '</div>';

    html += '<div class="ledger-summary">' +
      '<span>' + list.length + (list.length === 1 ? ' entry' : ' entries') + '</span>' +
      '<span class="in">+' + esc(U.money(totals.in)) + '</span>' +
      '<span class="out">−' + esc(U.money(totals.out)) + '</span>' +
      '</div>';

    html += list.length ? txnGroups(list)
      : emptyState('🔍', 'No matching entries', 'Try a different month, account or search term.', '', '');

    return html;
  }

  function monthOptions() {
    var seen = {}, out = [];
    S.transactions().forEach(function (t) {
      var key = t.date.slice(0, 7);
      if (seen[key]) return;
      seen[key] = true;
      out.push({ value: key, label: U.date(key + '-01').replace(/^\d+ /, '') });
    });
    return out.sort(function (a, b) { return b.value.localeCompare(a.value); });
  }

  /* ---------- debts ---------- */

  function debts() {
    var st = global.App.state;
    var all = S.debts().map(function (d) {
      return { debt: d, out: S.outstanding(d.id) };
    });
    var showSettled = !!st.showSettled;
    var live = all.filter(function (x) { return showSettled || x.out > 0.5; });

    var owed = live.filter(function (x) { return x.debt.direction === 'owed'; });
    var owe = live.filter(function (x) { return x.debt.direction === 'owe'; });

    var html = '<div class="duo tight">' +
      '<div class="card mini"><span class="tile-label">They owe me</span>' +
      '<span class="mini-value in">' + esc(U.money(S.receivables())) + '</span></div>' +
      '<div class="card mini"><span class="tile-label">I owe them</span>' +
      '<span class="mini-value out">' + esc(U.money(S.payables())) + '</span></div>' +
      '</div>';

    var net = S.receivables() - S.payables();
    html += '<p class="net-note ' + (net >= 0 ? 'in' : 'out') + '">' +
      (net >= 0 ? 'Net position: ' + esc(U.money(net)) + ' in your favour'
                : 'Net position: ' + esc(U.money(-net)) + ' against you') + '</p>';

    html += '<label class="switch-row"><input type="checkbox" data-show-settled' + (showSettled ? ' checked' : '') + '> <span>Show settled</span></label>';

    if (!all.length) {
      html += emptyState('🤝', 'No debts tracked',
        'Record money you lent to someone, or money you borrowed. Every repayment gets tracked against it.',
        'Add a debt', 'data-open-debt="new"');
      return html;
    }

    html += debtSection('They owe me', owed, 'owed');
    html += debtSection('I owe them', owe, 'owe');
    return html;
  }

  function debtSection(title, items, direction) {
    if (!items.length) {
      return '<section class="card"><div class="card-head"><h3>' + esc(title) + '</h3></div>' +
        '<p class="muted small pad">Nothing here.</p></section>';
    }
    items.sort(function (a, b) { return b.out - a.out; });
    return '<section class="card flush">' +
      '<div class="card-head"><h3>' + esc(title) + '</h3>' +
      '<button class="link" data-open-debt="new" data-direction="' + direction + '">+ Add</button></div>' +
      '<ul class="rows">' + items.map(function (x) { return debtRow(x.debt, x.out); }).join('') + '</ul>' +
      '</section>';
  }

  function debtRow(d, out) {
    var due = d.dueDate ? U.daysUntil(d.dueDate) : null;
    var dueLabel = '';
    if (out > 0 && due !== null) {
      if (due < 0) dueLabel = '<span class="pill danger">' + Math.abs(due) + 'd overdue</span>';
      else if (due <= 7) dueLabel = '<span class="pill warn">due in ' + due + 'd</span>';
      else dueLabel = '<span class="pill">due ' + esc(U.date(d.dueDate, 'short')) + '</span>';
    }
    var settled = out <= 0.5;
    return '<li class="row debt' + (settled ? ' settled' : '') + '" data-debt="' + esc(d.id) + '" tabindex="0" role="button">' +
      '<span class="avatar" style="background:' + esc(U.colorFor(d.person)) + '">' + esc(U.initials(d.person)) + '</span>' +
      '<span class="row-main">' +
        '<span class="row-title">' + esc(d.person || 'Someone') + ' ' + dueLabel + '</span>' +
        '<span class="row-sub">' + esc(d.note || (d.direction === 'owed' ? 'Lent out' : 'Borrowed')) + '</span>' +
      '</span>' +
      '<span class="amt ' + (settled ? 'neutral' : (d.direction === 'owed' ? 'in' : 'out')) + '">' +
        (settled ? 'Settled' : esc(U.money(out))) + '</span>' +
      '</li>';
  }

  /* ---------- reports ---------- */

  function reports() {
    var st = global.App.state;
    var range = S.monthRange(null, st.monthOffset);
    var sum = S.summary(range.from, range.to);

    var html = '<div class="month-switch">' +
      '<button class="icon-btn" data-month="-1" aria-label="Previous month">‹</button>' +
      '<span>' + esc(range.label) + '</span>' +
      '<button class="icon-btn" data-month="1" aria-label="Next month"' + (st.monthOffset >= 0 ? ' disabled' : '') + '>›</button>' +
      '</div>';

    html += '<div class="tiles">' +
      tile('Earned', U.money(sum.income), 'in') +
      tile('Spent', U.money(sum.expense), 'out') +
      tile('Saved', U.signedMoney(sum.net, sum.net >= 0 ? 1 : -1), sum.net >= 0 ? 'in' : 'out') +
      '</div>';

    /* 12-month trend */
    var points = [];
    for (var i = 11; i >= 0; i--) {
      var r = S.monthRange(null, st.monthOffset - i);
      var s = S.summary(r.from, r.to);
      points.push({ label: r.label.slice(0, 3), income: s.income, expense: s.expense });
    }
    var peak = points.reduce(function (m, p) { return Math.max(m, p.income, p.expense); }, 0);
    html += '<section class="card">' +
      '<div class="card-head"><h3>Last 12 months</h3><span class="muted small">peak ' + esc(U.moneyShort(peak)) + '</span></div>' +
      C.bars(points) +
      '<div class="legend"><span><i class="swatch in-bg"></i>Earned</span><span><i class="swatch out-bg"></i>Spent</span></div>' +
      '</section>';

    /* spending donut */
    var cats = S.byCategory('expense', range.from, range.to);
    var totalSpend = cats.reduce(function (s, c) { return s + c.value; }, 0);
    html += '<section class="card">' +
      '<div class="card-head"><h3>Spending by category</h3></div>' +
      (totalSpend ? '<div class="donut-wrap">' +
          C.donut(cats.slice(0, 8), { centerTop: U.moneyShort(totalSpend), centerBottom: 'spent' }) +
          '<ul class="cat-list grow">' + cats.map(function (c) {
            return '<li>' +
              '<span class="cat-name"><i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.label) +
              '<span class="muted small"> · ' + c.count + '</span></span>' +
              '<span class="cat-val">' + esc(U.money(c.value)) +
              '<span class="muted small"> ' + ((c.value / totalSpend) * 100).toFixed(0) + '%</span></span>' +
              C.meter(c.value / totalSpend, c.color) +
              '</li>';
          }).join('') + '</ul>' +
        '</div>'
        : '<p class="muted small pad">No spending recorded this month.</p>') +
      '</section>';

    /* income sources */
    var inc = S.byCategory('income', range.from, range.to);
    if (inc.length) {
      var totalInc = inc.reduce(function (s, c) { return s + c.value; }, 0);
      html += '<section class="card">' +
        '<div class="card-head"><h3>Where it came from</h3></div>' +
        '<ul class="cat-list">' + inc.map(function (c) {
          return '<li><span class="cat-name"><i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.label) + '</span>' +
            '<span class="cat-val">' + esc(U.money(c.value)) + '</span>' +
            C.meter(c.value / totalInc, c.color) + '</li>';
        }).join('') + '</ul>' +
        '</section>';
    }

    /* account balances */
    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Accounts</h3><a href="#/settings" class="link">Manage</a></div>' +
      '<ul class="rows">' + S.accounts(true).map(function (a) {
        var bal = S.accountBalance(a.id);
        return '<li class="row">' +
          '<span class="row-dot" style="background:' + esc(U.colorFor(a.name)) + '"><span class="row-glyph">' + accountGlyph(a.type) + '</span></span>' +
          '<span class="row-main"><span class="row-title">' + esc(a.name) + (a.archived ? ' <span class="pill">archived</span>' : '') + '</span>' +
          '<span class="row-sub">' + esc(a.type) + '</span></span>' +
          '<span class="amt ' + (bal < 0 ? 'out' : '') + '">' + esc(U.money(bal)) + '</span>' +
          '</li>';
      }).join('') + '</ul>' +
      '</section>';

    return html;
  }

  /* What to say under an account's name: for a card, the facts that matter. */
  function accountSub(a) {
    if (a.type !== 'card') return 'Opening ' + U.money(a.openingBalance);
    var ordinal = global.CardStatement.ordinal;
    var bits = [];
    if (a.creditLimit) bits.push('Limit ' + U.money(a.creditLimit));
    bits.push('statement ' + ordinal(a.statementDay || 1));
    bits.push('due ' + ordinal(a.dueDay || 1));
    return bits.join(' · ');
  }

  function accountGlyph(type) {
    return type === 'cash' ? '₹' : type === 'wallet' ? '◈' : type === 'card' ? '▤' : '▦';
  }

  /* ---------- settings ---------- */

  function settings() {
    var s = S.settings();
    var html = '';

    html += '<section class="card">' +
      '<div class="card-head"><h3>Preferences</h3></div>' +
      '<div class="field"><label for="set-budget">Monthly spending budget (₹)</label>' +
        '<input id="set-budget" class="input" type="number" inputmode="decimal" min="0" step="1" value="' + (s.monthlyBudget ? U.num(s.monthlyBudget) : '') + '" placeholder="0 = no budget"></div>' +
      '<div class="field"><label for="set-start">Month starts on day</label>' +
        '<input id="set-start" class="input" type="number" min="1" max="28" value="' + esc(s.monthStartDay) + '">' +
        '<p class="hint">Set this to your salary date to align months with your pay cycle.</p></div>' +
      '<div class="field"><label for="set-theme">Appearance</label>' +
        '<select id="set-theme" class="input">' +
          ['auto', 'light', 'dark'].map(function (t) {
            return '<option value="' + t + '"' + (s.theme === t ? ' selected' : '') + '>' + t.charAt(0).toUpperCase() + t.slice(1) + '</option>';
          }).join('') +
        '</select></div>' +
      '<button class="btn primary" data-save-settings>Save preferences</button>' +
      '</section>';

    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Accounts</h3><button class="link" data-add-account>+ Add</button></div>' +
      '<ul class="rows">' + S.accounts(true).map(function (a) {
        return '<li class="row" data-account="' + esc(a.id) + '" tabindex="0" role="button">' +
          '<span class="row-dot" style="background:' + esc(U.colorFor(a.name)) + '"><span class="row-glyph">' + accountGlyph(a.type) + '</span></span>' +
          '<span class="row-main"><span class="row-title">' + esc(a.name) + (a.archived ? ' <span class="pill">archived</span>' : '') + '</span>' +
          '<span class="row-sub">' + esc(accountSub(a)) + '</span></span>' +
          '<span class="amt">' + esc(U.money(S.accountBalance(a.id))) + '</span></li>';
      }).join('') + '</ul>' +
      '</section>';

    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Categories</h3><button class="link" data-add-category>+ Add</button></div>' +
      '<div class="chips pad">' + S.categories(null, true).map(function (c) {
        return '<button class="chip cat' + (c.archived ? ' muted' : '') + '" data-category="' + esc(c.id) + '">' +
          '<i class="swatch" style="background:' + esc(c.color) + '"></i>' + esc(c.name) + '</button>';
      }).join('') + '</div>' +
      '</section>';

    html += automationSection();

    var d = S.all();
    html += '<section class="card">' +
      '<div class="card-head"><h3>Backup &amp; sync</h3></div>' +
      '<p class="muted small">Your data lives only on this device. Export a file and import it on your other device to keep both in step.</p>' +
      '<div class="btn-row">' +
        '<button class="btn" data-export-json>Export backup (.json)</button>' +
        '<button class="btn" data-export-csv>Export ledger (.csv)</button>' +
      '</div>' +
      '<div class="field"><label for="import-file">Import a backup</label>' +
        '<input id="import-file" class="input" type="file" accept="application/json,.json">' +
        '<p class="hint">Merge keeps what is already here and adds anything new. Replace wipes this device first.</p>' +
        '<div class="btn-row">' +
          '<button class="btn" data-import="merge">Merge in</button>' +
          '<button class="btn danger" data-import="replace">Replace everything</button>' +
        '</div>' +
      '</div>' +
      '<p class="muted small">' + d.transactions.length + ' entries · ' + d.debts.length + ' debts · ' + d.accounts.length + ' accounts</p>' +
      '</section>';

    html += '<section class="card">' +
      '<div class="card-head"><h3>Danger zone</h3></div>' +
      '<button class="btn danger" data-reset>Delete all data</button>' +
      '</section>';

    html += '<p class="footnote">Paisa · every rupee accounted for</p>';
    return html;
  }

  /* ---------- automation settings ---------- */

  var FREQ_LABEL = {
    monthly: function (r) { return 'Monthly on the ' + ordinal(r.day); },
    weekly: function (r) { return 'Every ' + ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][r.day || 0]; }
  };

  function ordinal(n) {
    var v = parseInt(n, 10) || 1;
    var suffix = (v % 10 === 1 && v !== 11) ? 'st' : (v % 10 === 2 && v !== 12) ? 'nd' : (v % 10 === 3 && v !== 13) ? 'rd' : 'th';
    return v + suffix;
  }

  function automationSection() {
    var s = S.settings();
    var recs = S.recurring();
    var rules = S.rules();

    var html = '<section class="card">' +
      '<div class="card-head"><h3>Automatic logging</h3><a class="link" href="#/inbox">Open inbox</a></div>' +
      '<label class="switch-row"><input type="checkbox" data-auto-confirm' + (s.autoConfirm ? ' checked' : '') + '>' +
        ' <span>Log straight away when I have filed that shop before</span></label>' +
      '<p class="hint">Off by default: everything captured waits in the inbox for a tap. Turn this on once the categories look right and known shops will skip the review step.</p>' +
      '<div class="btn-row">' +
        '<button class="btn" data-open-capture>Paste messages</button>' +
        '<button class="btn" data-open-statement>Import statement</button>' +
      '</div>' +
      '</section>';

    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Repeating entries</h3><button class="link" data-recurring="new">+ Add</button></div>' +
      (recs.length
        ? '<ul class="rows">' + recs.map(function (r) {
            var cat = r.categoryId ? S.category(r.categoryId) : null;
            var freq = (FREQ_LABEL[r.freq] || FREQ_LABEL.monthly)(r);
            return '<li class="row' + (r.paused ? ' settled' : '') + '" data-recurring="' + esc(r.id) + '" tabindex="0" role="button">' +
              '<span class="row-dot" style="background:' + esc(cat ? cat.color : U.colorFor(r.label)) + '">' +
                '<span class="row-glyph">' + (r.type === 'income' ? '↑' : '↓') + '</span></span>' +
              '<span class="row-main"><span class="row-title">' + esc(r.label) +
                (r.autoPost ? ' <span class="pill tiny">auto</span>' : '') +
                (r.paused ? ' <span class="pill tiny">paused</span>' : '') + '</span>' +
              '<span class="row-sub">' + esc(freq) + ' · next ' + esc(U.date(r.nextDate, 'short')) + '</span></span>' +
              '<span class="amt ' + (r.type === 'income' ? 'in' : 'out') + '">' + esc(U.money(r.amount)) + '</span></li>';
          }).join('') + '</ul>'
        : '<p class="muted small pad">Add rent, salary, EMIs and subscriptions here and they log themselves on schedule.</p>') +
      '</section>';

    html += '<section class="card flush">' +
      '<div class="card-head"><h3>Learned categories</h3>' +
        (rules.length ? '<span class="muted small">' + rules.length + ' shops</span>' : '') + '</div>' +
      (rules.length
        ? '<ul class="rows">' + rules.slice().sort(function (a, b) { return (b.hits || 0) - (a.hits || 0); }).map(function (r) {
            var cat = r.categoryId ? S.category(r.categoryId) : null;
            return '<li class="row"><span class="row-dot" style="background:' + esc(cat ? cat.color : '#94a3b8') + '">' +
              '<span class="row-glyph">◆</span></span>' +
              '<span class="row-main"><span class="row-title">' + esc(r.match) + '</span>' +
              '<span class="row-sub">' + esc(cat ? cat.name : 'No category') +
                ((r.hits || 0) > 1 ? ' · used ' + r.hits + ' times' : '') + '</span></span>' +
              '<button class="mini-btn" data-rule-delete="' + esc(r.id) + '" aria-label="Forget this rule">×</button></li>';
          }).join('') + '</ul>'
        : '<p class="muted small pad">Every time you file a captured entry, Paisa remembers that shop and files it the same way next time.</p>') +
      '</section>';

    return html;
  }

  /* ---------- inbox ----------
   * Everything captured automatically waits here until it is confirmed. */

  function inboxRow(item) {
    var p = item.parsed;
    var draft = S.suggest(p);
    var cat = draft.categoryId ? S.category(draft.categoryId) : null;
    var acc = draft.accountId ? S.account(draft.accountId) : null;
    var sign = p.type === 'income' ? 1 : -1;

    var sourceLabel = { share: 'shared', paste: 'pasted', csv: 'statement', recurring: 'scheduled' }[item.source] || item.source;
    var bits = [U.date(draft.date, 'short'), cat ? cat.name : 'Uncategorised', acc ? acc.name : '', sourceLabel];

    return '<li class="row inbox-row" data-inbox="' + esc(item.id) + '" tabindex="0" role="button">' +
      '<span class="row-dot" style="background:' + esc(cat ? cat.color : U.colorFor(p.counterparty || 'x')) + '">' +
        '<span class="row-glyph">' + (sign > 0 ? '↑' : '↓') + '</span></span>' +
      '<span class="row-main">' +
        '<span class="row-title">' + esc(p.counterparty || 'Unnamed') +
          (draft.matchedRule ? ' <span class="pill tiny">auto</span>' : '') + '</span>' +
        '<span class="row-sub">' + esc(bits.filter(Boolean).join(' · ')) + '</span>' +
      '</span>' +
      '<span class="amt ' + (sign > 0 ? 'in' : 'out') + '">' + esc(U.signedMoney(p.amount, sign)) + '</span>' +
      '<span class="row-tools">' +
        '<button class="mini-btn ok" data-inbox-confirm="' + esc(item.id) + '" aria-label="Log this entry">✓</button>' +
        '<button class="mini-btn" data-inbox-drop="' + esc(item.id) + '" aria-label="Discard">×</button>' +
      '</span>' +
      '</li>';
  }

  function inbox() {
    var items = S.inbox();
    var html = '';

    html += '<div class="btn-row">' +
      '<button class="btn primary grow" data-open-capture>Paste a bank message</button>' +
      '<button class="btn" data-open-statement>Import statement</button>' +
      '</div>';

    if (!items.length) {
      html += emptyState('⚡', 'Inbox is empty',
        'Share a bank SMS to Paisa, paste one here, or import a statement. Anything captured waits here until you tap ✓.',
        'Paste a message', 'data-open-capture="1"');
      html += howItWorks();
      return html;
    }

    var totalOut = items.reduce(function (s2, i) { return s2 + (i.parsed.type === 'expense' ? i.parsed.amount : 0); }, 0);
    var totalIn = items.reduce(function (s2, i) { return s2 + (i.parsed.type === 'income' ? i.parsed.amount : 0); }, 0);

    html += '<div class="ledger-summary">' +
      '<span>' + items.length + (items.length === 1 ? ' entry waiting' : ' entries waiting') + '</span>' +
      '<span class="in">+' + esc(U.money(totalIn)) + '</span>' +
      '<span class="out">−' + esc(U.money(totalOut)) + '</span>' +
      '</div>';

    html += '<section class="card flush">' +
      '<div class="card-head"><h3>To review</h3>' +
      '<button class="link" data-inbox-confirm-all>Log all ' + items.length + '</button></div>' +
      '<ul class="rows">' + items.map(inboxRow).join('') + '</ul>' +
      '</section>';

    html += '<p class="hint pad">Tap a row to change anything before logging. Paisa remembers the category you pick and files that shop the same way next time.</p>';
    return html;
  }

  function howItWorks() {
    return '<section class="card">' +
      '<div class="card-head"><h3>Three ways to skip the typing</h3></div>' +
      '<ol class="steps">' +
        '<li><strong>Share a bank SMS.</strong> Long-press the message in your SMS app → Share → Paisa. It arrives here already read. Needs Paisa installed to your home screen.</li>' +
        '<li><strong>Paste in bulk.</strong> Copy a batch of messages and paste them all at once — each one becomes its own entry.</li>' +
        '<li><strong>Import a statement.</strong> Download the CSV from your bank and drop it in. Entries you already logged are skipped automatically.</li>' +
      '</ol>' +
      '<p class="hint">Rent, salary and EMIs do not need any of this — set them up as repeating entries in Settings.</p>' +
      '</section>';
  }

  global.Views = {
    inbox: inbox, dashboard: dashboard, ledger: ledger, debts: debts, reports: reports, settings: settings,
    txnRow: txnRow, txnGroups: txnGroups, typeMeta: typeMeta, emptyState: emptyState,
    accountGlyph: accountGlyph, monthOptions: monthOptions
  };
})(window);
