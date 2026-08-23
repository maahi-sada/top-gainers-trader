# Tests

Plain Node, no dependencies, no install step:

```bash
cd finance-tracker/tests
node parse.test.mjs           # bank / UPI message reading
node statement.test.mjs       # bank statement CSV reading
node cardstatement.test.mjs   # card limits, statement dates, due dates
```

All three exit non-zero if anything fails.

`cardstatement.test.mjs` mirrors the Android core's own suite case for case. The
same statement has to produce the same card on both sides, so if you add a case
to one, add it to `paisa-android/core/src/test/.../CardStatementParserTest.kt`
too.

## When your bank's message is not read correctly

1. Add the message to `parse.test.mjs` as a new `check(...)` with what you
   expect to come out of it.
2. Run the file — it will fail and show exactly which field is wrong.
3. Adjust the pattern in `../js/parse.js` until it passes, and make sure the
   other cases still pass.

Formats vary by bank and change over time, so this is the intended way to teach
it a new one. The same applies to `statement.test.mjs` for CSV exports.
