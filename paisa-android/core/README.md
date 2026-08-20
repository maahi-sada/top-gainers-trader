# core

Every calculation in Paisa, as plain Kotlin with no Android dependencies.

```bash
./gradlew test          # 92 tests, no SDK or emulator needed
```

Kept as a separate Gradle build so the tests run on any machine with a JDK. The
Android app depends on it through `includeBuild("core")` in the root
`settings.gradle.kts`.

## What lives here, and why

The parts of a money tracker that must not be wrong are the arithmetic and the
message reading — not the screens. Putting them here means they can be tested
properly:

| File | Covers |
|---|---|
| `Money.kt` | integer paise, Indian digit grouping |
| `MessageParser.kt` | reading bank / UPI SMS |
| `EmailText.kt` | HTML email to text, bank-mail detection |
| `Ledger.kt` | balances, debts, month summaries, category breakdowns |
| `CardCycle.kt` | credit card statement periods and due dates |
| `DailyTarget.kt` | daily earning targets, streaks, month pace |
| `Schedule.kt` | repeating entries and catch-up |
| `Snapshot.kt` | the JSON format, shared with the web app |
| `AppData.kt` | capture, de-duplication, learning, confirmation |
| `EntryInput.kt` | turning a filled-in form into stored records |

`SnapshotTest` reads `src/test/resources/web-backup.json` — a real export from the
web app — and asserts this code produces the same totals the browser did.

## When your bank's message is not read correctly

Add it to `MessageParserTest.kt` with what you expect, run the suite, and adjust
the patterns in `MessageParser.kt` until it passes. Formats vary by bank and change
over time; this is the intended way to teach it a new one.
