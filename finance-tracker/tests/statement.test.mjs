import St from '../js/statement.js';
let pass = 0, fail = 0;
const ok = (c, l, extra = '') => { c ? (pass++, console.log('✓ ' + l + (extra ? '  →  ' + extra : ''))) : (fail++, console.log('✗ ' + l + '  ' + extra)); };
const R = p => (p / 100).toFixed(2);

// HDFC style: junk preamble, separate debit/credit columns, dd/mm/yy
const hdfc = `Statement of account
Account No: XXXXXXXX1234
,,,,,,
Date,Narration,Chq/Ref No,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
01/08/26,UPI/DR/412345678901/SWIGGY/HDFC/swiggy@icici/Payment,412345678901,01/08/26,640.50,,98765.43
02/08/26,SALARY AUG 2026,NEFT001,02/08/26,,82000.00,180765.43
03/08/26,"ATM CASH WDL, MG ROAD",ATM998,03/08/26,"5,000.00",,175765.43`;

let r = St.read(hdfc);
ok(!r.error, 'HDFC: parses', r.error || '');
ok(r.entries.length === 3, 'HDFC: 3 rows', 'got ' + r.entries.length);
ok(r.entries[0].type === 'expense' && R(r.entries[0].amount) === '640.50', 'HDFC: debit row', r.entries[0].type + ' ' + R(r.entries[0].amount));
ok(r.entries[0].date === '2026-08-01', 'HDFC: dd/mm/yy date', r.entries[0].date);
ok(r.entries[0].counterparty === 'Swiggy', 'HDFC: narration -> merchant', String(r.entries[0].counterparty));
ok(r.entries[1].type === 'income' && R(r.entries[1].amount) === '82000.00', 'HDFC: credit row', r.entries[1].type);
ok(R(r.entries[2].amount) === '5000.00', 'HDFC: quoted "5,000.00"', R(r.entries[2].amount));
ok(r.entries[2].counterparty === 'Atm Cash Wdl, Mg Road'.replace('Atm ', ''), 'HDFC: quoted comma field kept whole', String(r.entries[2].counterparty));

// ICICI style: single signed amount column, dd-Mon-yyyy
const icici = `Transaction Date,Transaction Remarks,Amount,Balance
01-Aug-2026,BLINKIT GROCERY,-1240.00,45000.00
05-Aug-2026,INTEREST CREDIT,320.50,45320.50`;
r = St.read(icici);
ok(!r.error, 'ICICI: parses', r.error || '');
ok(r.entries[0].type === 'expense' && R(r.entries[0].amount) === '1240.00', 'ICICI: negative = expense', r.entries[0].type);
ok(r.entries[1].type === 'income', 'ICICI: positive = income', r.entries[1].type);
ok(r.entries[0].date === '2026-08-01', 'ICICI: dd-Mon-yyyy', r.entries[0].date);

// SBI style: Dr/Cr marker column
const sbi = `Txn Date\tDescription\tRef No\tDebit/Credit\tAmount\tBalance
19-08-2026\tTO TRANSFER-UPI/RAMESH\t512345\tDr\t1500.00\t22000.00
20-08-2026\tBY TRANSFER-SALARY\t512346\tCr\t50000.00\t72000.00`;
r = St.read(sbi);
ok(!r.error, 'SBI: tab separated parses', r.error || '');
ok(r.entries[0].type === 'expense', 'SBI: Dr marker -> expense', r.entries[0].type);
ok(r.entries[1].type === 'income', 'SBI: Cr marker -> income', r.entries[1].type);

// rows that must be skipped, not silently mangled
const messy = `Date,Narration,Withdrawal Amt.,Deposit Amt.
01/08/26,VALID ROW,100.00,
,OPENING BALANCE,,
not-a-date,GARBAGE,50.00,
02/08/26,ZERO ROW,,`;
r = St.read(messy);
ok(r.entries.length === 1, 'messy: only the valid row survives', r.entries.length + ' kept, ' + r.skipped + ' skipped');
ok(r.skipped === 3, 'messy: skipped counted', String(r.skipped));

// no header at all
r = St.read('just,some,random\n1,2,3');
ok(!!r.error, 'no header -> clear error', r.error || '');

// narration tidying
ok(St.tidy('UPI/DR/412345678901/SWIGGY/HDFC/swiggy@icici/Payment') === 'Swiggy', 'tidy: UPI narration', String(St.tidy('UPI/DR/412345678901/SWIGGY/HDFC/swiggy@icici/Payment')));
ok(St.tidy('POS 1234567890 AMAZON PAY INDIA') === 'Amazon Pay India', 'tidy: POS narration', String(St.tidy('POS 1234567890 AMAZON PAY INDIA')));
ok(St.tidy('') === null, 'tidy: empty -> null');

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
