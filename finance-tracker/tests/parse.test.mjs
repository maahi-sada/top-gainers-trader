import P from '../js/parse.js';

const R = p => (p / 100).toFixed(2);
let pass = 0, fail = 0;

function check(label, text, want) {
  const got = P.parse(text);
  const problems = [];
  for (const [k, v] of Object.entries(want)) {
    const actual = k === 'amount' ? (got.amount === null ? null : R(got.amount)) : got[k];
    if (String(actual) !== String(v)) problems.push(`${k}: want ${JSON.stringify(v)}, got ${JSON.stringify(actual)}`);
  }
  if (problems.length) { fail++; console.log(`✗ ${label}\n    ${text.slice(0, 96)}\n    ` + problems.join('\n    ')); }
  else { pass++; console.log(`✓ ${label}  →  ${got.type} ${got.amount !== null ? '₹' + R(got.amount) : ''} ${got.counterparty || ''} ${got.date || ''}`.trimEnd()); }
}

console.log('--- UPI debits ---');
check('HDFC UPI to VPA',
  'Rs.1,234.56 debited from A/c XX1234 on 19-08-26 to VPA ramesh.kumar@okhdfcbank (UPI Ref 123456789012). Not you? Call 18002586161',
  { ok: true, type: 'expense', amount: '1234.56', date: '2026-08-19', accountTail: '1234', ref: '123456789012', method: 'upi' });

check('Kotak sent',
  'Sent Rs.100.00 From Kotak Bank AC X1234 To ramesh@ybl On 19-08-26 Ref 502312345678',
  { ok: true, type: 'expense', amount: '100.00', date: '2026-08-19', bank: 'Kotak', accountTail: '1234' });

check('SBI UPI debit',
  'Dear UPI user A/C X1234 debited by 240.0 on date 12Aug26 trf to BLINKIT Refno 522398765432. If not u? call 1800111109 -SBI',
  { ok: true, type: 'expense', amount: '240.00', bank: 'SBI' });

check('PhonePe payment',
  'You have paid Rs 450 to Swiggy via PhonePe UPI on 11-08-2026. UPI Ref No 123412341234',
  { ok: true, type: 'expense', amount: '450.00', date: '2026-08-11', bank: 'PhonePe' });

check('merchant followed by a bracket',
  'Rs.2,310.75 debited from A/c XX1234 on 15-08-26 to RELIANCE FRESH (UPI Ref 512345678904)',
  { ok: true, type: 'expense', amount: '2310.75', counterparty: 'Reliance Fresh', date: '2026-08-15' });

console.log('\n--- card / ATM ---');
check('Card spend at merchant',
  'HDFC Bank: Rs 640.50 spent on Card XX5678 at SWIGGY on 11-08-2026. Avl Lmt Rs 84,359.50',
  { ok: true, type: 'expense', amount: '640.50', counterparty: 'Swiggy', date: '2026-08-11', accountTail: '5678' });

check('ATM withdrawal',
  'Rs.5000.00 withdrawn from A/c XX9012 at ATM on 09/08/26. Avl Bal Rs.23,410.00',
  { ok: true, type: 'expense', amount: '5000.00', date: '2026-08-09', accountTail: '9012', balance: 2341000, counterparty: null, method: 'atm' });

console.log('\n--- credits ---');
check('Salary credit',
  'Your a/c no. XXXXXXXX4321 is credited by Rs.82,000.00 on 01/08/26 - SALARY AUG. Avl Bal Rs.1,04,556.20',
  { ok: true, type: 'income', amount: '82000.00', date: '2026-08-01', accountTail: '4321' });

check('UPI money received',
  'INR 2,500.00 credited to your A/c XX1234 on 05-08-2026 from priya@okaxis. Ref 445566778899',
  { ok: true, type: 'income', amount: '2500.00', date: '2026-08-05', counterparty: 'Priya' });

check('Refund',
  'Rs 1,890.00 has been refunded to your Kotak Bank Card XX5678 on 14-08-26 by AMAZON',
  { ok: true, type: 'income', amount: '1890.00', date: '2026-08-14', counterparty: 'Amazon' });

console.log('\n--- wallets ---');
check('Paytm',
  'Paytm: Rs.240 paid to Blinkit from your Paytm Wallet on 12-08-2026',
  { ok: true, type: 'expense', amount: '240.00', counterparty: 'Blinkit', bank: 'Paytm' });

check('Google Pay received',
  "You've received Rs 500 from Priya Sharma via Google Pay on 08-08-2026",
  { ok: true, type: 'income', amount: '500.00', counterparty: 'Priya Sharma', bank: 'Google Pay' });

console.log('\n--- must be ignored ---');
check('OTP', 'Your OTP for txn of Rs.4,500 at AMAZON is 483920. Do not share it with anyone.', { ok: false, why: 'OTP message' });
check('Upcoming autopay', 'Rs.499 will be debited from your A/c XX1234 on 25-08-26 towards NETFLIX autopay', { ok: false });
check('Failed txn', 'Your transaction of Rs.2,000 to ramesh@ybl has failed. Amount will be refunded.', { ok: false });
check('Bill reminder', 'Your credit card bill of Rs 12,450 is due on 27-08-2026. Pay now to avoid charges.', { ok: false });
check('Promo', 'Pre-approved loan offer of Rs 5,00,000! Apply now. T&C apply.', { ok: false });
check('Collect request', 'ramesh@ybl has requested Rs.300 via UPI. Approve in your app.', { ok: false });
check('Balance only', 'Your A/c XX1234 balance is Rs.45,000.00 as on 19-08-26', { ok: false });

console.log('\n--- balance must not be mistaken for the amount ---');
check('amount vs balance order',
  'Rs.320.00 debited from A/c XX1234 on 19-08-26. Avl Bal Rs.98,765.43',
  { amount: '320.00', balance: 9876543 });

console.log('\n--- splitting a pasted blob ---');
const blob = `Rs.100 debited from A/c XX1234 on 01-08-26 to VPA a@ybl
Rs.200 debited from A/c XX1234 on 02-08-26 to VPA b@ybl
Rs.300 credited to A/c XX1234 on 03-08-26 from c@ybl`;
const parts = P.split(blob);
console.log(parts.length === 3 ? '✓ splits 3 one-line messages' : `✗ split gave ${parts.length}`);
parts.length === 3 ? pass++ : fail++;

const blob2 = 'Rs.100 debited from A/c XX1234 on 01-08-26\n\nRs.200 credited to A/c XX1234 on 02-08-26';
P.split(blob2).length === 2 ? (pass++, console.log('✓ splits on blank lines')) : (fail++, console.log('✗ blank-line split'));

console.log('\n--- fingerprint dedupe ---');
const a = P.parse('Rs.100 debited from A/c XX1234 on 01-08-26 to VPA a@ybl Ref 999888777666');
const b = P.parse('Rs.100 debited from A/c XX1234 on 01-08-26 to VPA a@ybl Ref 999888777666');
P.fingerprint(a) === P.fingerprint(b) ? (pass++, console.log('✓ same message → same fingerprint')) : (fail++, console.log('✗ fingerprint mismatch'));
const c = P.parse('Rs.150 debited from A/c XX1234 on 01-08-26 to VPA a@ybl Ref 111222333444');
P.fingerprint(a) !== P.fingerprint(c) ? (pass++, console.log('✓ different message → different fingerprint')) : (fail++, console.log('✗ fingerprint collision'));

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
