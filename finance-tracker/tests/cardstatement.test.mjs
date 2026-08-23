/* Card statement reading — the same cases the Android core is held to, so a
 * message that fills in a card on the phone fills in the same card here. */
import C from '../js/cardstatement.js';

const TODAY = '2026-08-23';
const R = p => (p === null || p === undefined ? null : (p / 100).toFixed(2));
let pass = 0, fail = 0;

function report(label, problems) {
  if (problems.length) { fail++; console.log(`✗ ${label}\n    ` + problems.join('\n    ')); }
  else { pass++; console.log(`✓ ${label}`); }
}

/* Reads an email (subject + HTML body) and checks the fields named in want. */
function mail(label, subject, body, want) {
  check(label, C.read(subject, body, TODAY), want);
}

/* Reads an SMS and checks the fields named in want. */
function sms(label, text, want) {
  check(label, C.readMessage(text, TODAY), want);
}

const MONEY_FIELDS = ['creditLimit', 'availableLimit', 'cashLimit', 'totalDue', 'minimumDue'];

function check(label, got, want) {
  const problems = [];
  for (const [k, v] of Object.entries(want)) {
    const actual = MONEY_FIELDS.includes(k) ? R(got[k]) : got[k];
    const expected = MONEY_FIELDS.includes(k) && v !== null ? Number(v).toFixed(2) : v;
    if (String(actual) !== String(expected)) {
      problems.push(`${k}: want ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}` +
        (got.why ? `  (why: ${got.why})` : ''));
    }
  }
  report(label, problems);
}

console.log('--- statement emails ---');

mail('HDFC table statement',
  'Your HDFC Bank Credit Card Statement',
  `<p>Dear Customer,</p>
   <table>
     <tr><td>Card No</td><td>XXXX XXXX XXXX 4321</td></tr>
     <tr><td>Statement Date</td><td>18/08/2026</td></tr>
     <tr><td>Payment Due Date</td><td>07/09/2026</td></tr>
     <tr><td>Total Dues</td><td>Rs. 24,530.00</td></tr>
     <tr><td>Minimum Amount Due</td><td>Rs. 1,230.00</td></tr>
     <tr><td>Credit Limit</td><td>Rs. 3,00,000.00</td></tr>
     <tr><td>Available Credit Limit</td><td>Rs. 2,75,470.00</td></tr>
   </table>`,
  {
    ok: true, bank: 'HDFC', last4: '4321',
    statementDate: '2026-08-18', dueDate: '2026-09-07', statementDay: 18, dueDay: 7,
    totalDue: 24530, minimumDue: 1230, creditLimit: 300000, availableLimit: 275470
  });

mail('SBI Card, month names and a billing period',
  'SBI Card e-Statement',
  `Your SBI Card statement for the period 19 Jul 2026 to 18 Aug 2026
   Total Amount Due: Rs 12,345.67
   Minimum Amount Due: Rs 620.00
   Payment Due Date: 07 Sep 2026
   Credit Limit: Rs 1,50,000
   Available Credit Limit: Rs 1,37,654.33
   Cash Limit: Rs 30,000
   Card ending 8890`,
  {
    ok: true, bank: 'SBI', last4: '8890',
    statementDate: '2026-08-18', dueDate: '2026-09-07',
    creditLimit: 150000, availableLimit: 137654.33, cashLimit: 30000,
    totalDue: 12345.67, minimumDue: 620
  });

mail('American Express, month written first',
  'Your American Express Card statement is ready',
  `Statement Date: August 18, 2026
   Payment Due Date: September 07, 2026
   New Balance: Rs 45,000.00
   Minimum Amount Due: Rs 2,250.00
   Credit Limit: Rs 5,00,000.00
   Your Card ending 1007`,
  { ok: true, bank: 'Amex', last4: '1007', statementDate: '2026-08-18', dueDate: '2026-09-07', creditLimit: 500000 });

mail('ICICI two-column table',
  'ICICI Bank Credit Card Statement',
  '<table><tr><td>Total Amount due</td><td>Rs.8,940.55</td></tr>' +
  '<tr><td>Minimum Amount due</td><td>Rs.450.00</td></tr>' +
  '<tr><td>Due Date</td><td>02-Sep-2026</td></tr>' +
  '<tr><td>Statement Date</td><td>12-Aug-2026</td></tr></table>' +
  '<p>ICICI Bank Credit Card XX7788</p>',
  { ok: true, bank: 'ICICI', last4: '7788', statementDay: 12, dueDay: 2, totalDue: 8940.55, minimumDue: 450 });

mail('each value on the line below its label',
  'Your card statement',
  `IDFC FIRST Bank Credit Card
   Card Number
   XXXX XXXX XXXX 6677
   Statement Date
   20 Aug 2026
   Payment Due Date
   08 Sep 2026
   Total Amount Due
   Rs. 9,875.40
   Credit Limit
   Rs. 2,50,000.00`,
  { ok: true, bank: 'IDFC', last4: '6677', statementDay: 20, dueDay: 8, totalDue: 9875.40, creditLimit: 250000 });

console.log('--- labels that must not be confused ---');

mail('available limit is not the credit limit',
  'OneCard statement',
  `OneCard Credit Card ending 2244
   Statement Date 05 Aug 2026
   Payment Due Date 25 Aug 2026
   Available Credit Limit: Rs 40,000
   Credit Limit: Rs 2,00,000`,
  { ok: true, creditLimit: 200000, availableLimit: 40000 });

mail('minimum due is not the total due',
  'Kotak Card',
  `Kotak Credit Card XX3311
   Minimum Amount Due Rs 500.00
   Total Amount Due Rs 18,700.00
   Payment Due Date 10/09/2026`,
  { ok: true, minimumDue: 500, totalDue: 18700 });

mail('a footer of terms does not sink a real statement',
  'SBI Card Statement',
  `SBI Credit Card ending 8890
   Statement Date: 18 Aug 2026
   Payment Due Date: 07 Sep 2026
   Credit Limit: Rs 1,50,000
   Know more about our offers. T&C apply. Download the app today.`,
  { ok: true, creditLimit: 150000 });

console.log('--- SMS ---');

sms('bill reminder',
  'Your HDFC Bank Credit Card ending 4321 bill of Rs.24530.00 is due on 07/09/2026. Min Amt Due Rs.1230.00. Pay now to avoid charges.',
  { ok: true, bank: 'HDFC', last4: '4321', dueDay: 7, minimumDue: 1230 });

sms('statement generated, no year on the date',
  'Statement generated for your Axis Bank Credit Card XX9012. Total Due Rs 5,600. Due Date 05 Sep.',
  { ok: true, bank: 'Axis', last4: '9012', dueDate: '2026-09-05', totalDue: 5600 });

sms('limit increase carrying no dates at all',
  'Your ICICI Bank Credit Card XX1234 credit limit has been increased to Rs 2,00,000.',
  { ok: true, bank: 'ICICI', last4: '1234', creditLimit: 200000, dueDate: null });

console.log('--- what it must refuse ---');

sms('limit advert',
  'Congratulations! You are pre-approved for a credit card with a limit of up to Rs 5,00,000. Apply now!',
  { ok: false });

sms('ordinary purchase alert',
  'Rs.640.50 spent on HDFC Bank Credit Card xx4321 at SWIGGY on 19-08-26. Avl Limit Rs.45,000. Not you? Call 18002586161.',
  { ok: false });

sms('purchase alert that quotes the credit limit',
  'Rs 1,200 spent on your Axis Bank Credit Card XX9012 at AMAZON on 19-08-26. Credit Limit Rs 1,00,000, Available Limit Rs 88,000.',
  { ok: false });

sms('a savings account statement names no card',
  'Your savings account statement date is 18/08/2026. Balance Rs 45,000.',
  { ok: false });

sms('empty message', '', { ok: false });

console.log('--- describing where it came from ---');

{
  const st = C.read('HDFC statement',
    'HDFC Bank Credit Card ending 4321\nStatement Date 18/08/2026\nPayment Due Date 07/09/2026', TODAY);
  report('describe names the bank and the date',
    C.describe(st) === 'HDFC statement of 18 Aug 2026' ? [] : [`got ${JSON.stringify(C.describe(st))}`]);
}

{
  const full = C.read('HDFC statement',
    'HDFC Bank Credit Card ending 4321\nStatement Date 18/08/2026\nPayment Due Date 07/09/2026\nCredit Limit Rs 3,00,000', TODAY);
  const bare = C.readMessage('Your OneCard bill is due on 07 Sep 2026. Credit card account 5566.', TODAY);
  report('a full statement is more confident than a bare reminder',
    (full.ok && bare.ok && full.confidence > bare.confidence) ? []
      : [`full ${full.confidence} (${full.why}), bare ${bare.confidence} (${bare.why})`]);
}

console.log('--- a statement must be read whole ---');

{
  /* Parse.split() breaks a blob apart on money-bearing lines, so if the app
   * split first, a statement would arrive as fragments. Each line on its own
   * is correctly refused — which is exactly why the whole document has to be
   * offered to the reader before anything is split. */
  const lines = `HDFC Bank Credit Card ending 4321
Statement Date: 18/08/2026
Payment Due Date: 07/09/2026
Total Dues: Rs. 24,530.00
Credit Limit: Rs. 3,00,000.00`.split('\n');

  const wholeReadable = C.readMessage(lines.join('\n'), TODAY).ok;
  const moneyLinesAlone = lines
    .filter(l => /(?:rs|inr|₹)\.?\s*[\d,]/i.test(l))
    .filter(l => C.readMessage(l, TODAY).ok);

  report('the whole statement reads', wholeReadable ? [] : ['it did not']);
  report('but its money lines alone do not',
    moneyLinesAlone.length === 0 ? [] : [`these read on their own: ${JSON.stringify(moneyLinesAlone)}`]);
}

console.log('--- filing it against a card ---');

const HDFC = C.readMessage(
  `HDFC Bank Credit Card ending 4321
   Statement Date: 18/08/2026
   Payment Due Date: 07/09/2026
   Total Dues: Rs. 24,530.00
   Minimum Amount Due: Rs. 1,230.00
   Credit Limit: Rs. 3,00,000.00`, TODAY);

function eq(label, got, want) {
  report(label, String(got) === String(want) ? [] : [`want ${JSON.stringify(want)}, got ${JSON.stringify(got)}`]);
}

{
  const r = C.apply(HDFC, [{ id: 'a1', name: 'HDFC Card', type: 'card' }], {}, TODAY);
  report('fills in a card set up with nothing but a name', r.applied && !r.created ? [] : [r.reason]);
  eq('  routed to the right account', r.accountId, 'a1');
  eq('  limit', r.patch.creditLimit, 30000000);
  eq('  statement day', r.patch.statementDay, 18);
  eq('  due day', r.patch.dueDay, 7);
  eq('  digits', r.patch.last4, '4321');
  eq('  where it came from', r.patch.detailsFrom, 'HDFC statement of 18 Aug 2026');
}

{
  const r = C.apply(HDFC, [
    { id: 'a1', name: 'Blue Card', type: 'card', last4: '4321' },
    { id: 'a2', name: 'HDFC Card', type: 'card', last4: '9999' }
  ], {}, TODAY);
  eq('routes by the digits, not the name', r.accountId, 'a1');
}

{
  const vague = C.readMessage(
    'Your HDFC Bank Credit Card statement is ready. Payment Due Date 07/09/2026. Total Amount Due Rs 900.', TODAY);
  const r = C.apply(vague, [
    { id: 'a1', name: 'HDFC Regalia', type: 'card' },
    { id: 'a2', name: 'HDFC Millennia', type: 'card' }
  ], {}, TODAY);
  report('will not guess between two cards from the same bank', r.created ? [] : ['it picked one anyway']);
}

{
  const r = C.apply(HDFC, [], {}, TODAY);
  report('creates a card it has never seen', r.applied && r.created ? [] : [r.reason]);
  eq('  named after the issuer and digits', r.account.name, 'HDFC Card ••4321');
  eq('  as a card account', r.account.type, 'card');
  eq('  with the limit', r.account.creditLimit, 30000000);
}

{
  const card = { id: 'a1', name: 'HDFC Card', type: 'card', last4: '4321', creditLimit: 30000000,
    statementDay: 18, dueDay: 7, lastStatementDue: 2453000, lastMinimumDue: 123000 };
  const r = C.apply(HDFC, [card], {}, TODAY);
  eq('says nothing when it already knew everything', r.reason, 'Already knew all of that');
  report('  and applies nothing', r.applied ? ['it applied anyway'] : []);
}

{
  const card = { id: 'a1', name: 'HDFC Card', type: 'card', last4: '4321', creditLimit: 30000000,
    statementDay: 18, dueDay: 7, lastStatementDue: 2453000, lastMinimumDue: 123000 };
  const september = C.readMessage(
    `HDFC Bank Credit Card ending 4321
     Statement Date: 18/09/2026
     Payment Due Date: 07/10/2026
     Total Dues: Rs. 31,000.00
     Minimum Amount Due: Rs. 1,550.00
     Credit Limit: Rs. 3,00,000.00`, '2026-09-20');
  const r = C.apply(september, [card], {}, '2026-09-20');
  eq("next month's statement moves only what changed", r.changes.join(' | '), 'bill ₹31,000.00 | minimum ₹1,550.00');
  eq('  statement day is untouched', r.patch.statementDay, undefined);
  eq('  and the statement date advances', r.patch.lastStatementDate, '2026-09-18');
}

{
  const card = { id: 'a1', name: 'HDFC Card', type: 'card', last4: '4321', creditLimit: 30000000, statementDay: 18, dueDay: 7 };
  const increase = C.readMessage('Your HDFC Bank Credit Card XX4321 credit limit has been increased to Rs 4,50,000.', TODAY);
  const r = C.apply(increase, [card], {}, TODAY);
  eq('a limit increase updates the limit', r.patch.creditLimit, 45000000);
  eq('  and leaves the statement day alone', r.patch.statementDay, undefined);
  eq('  and the due day alone', r.patch.dueDay, undefined);
}

{
  const advert = C.readMessage('You are pre-approved for a credit card with a limit of up to Rs 5,00,000. Apply now!', TODAY);
  const r = C.apply(advert, [], {}, TODAY);
  report('refuses to act on something that is not a statement', r.applied ? ['it applied an advert'] : []);
}

{
  const handTyped = { id: 'a1', name: 'HDFC Card', type: 'card', last4: '4321',
    creditLimit: 10000000, statementDay: 1, dueDay: 20 };
  const r = C.apply(HDFC, [handTyped], {}, TODAY);
  eq('the bank overrides a limit typed by hand', r.patch.creditLimit, 30000000);
  eq('  and the statement day', r.patch.statementDay, 18);
  eq('  and the due day', r.patch.dueDay, 7);
}

{
  const r = C.apply(HDFC, [{ id: 'a1', name: 'Blue Card', type: 'card' }], { '4321': 'a1' }, TODAY);
  eq('a remembered tail routes the statement', r.accountId, 'a1');
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
