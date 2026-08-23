# Paisa for Android

A native app that reads your bank SMS and bank emails by itself, and gives
**debts**, **daily earning targets** and **credit card spending** each a screen of
their own — with every rupee accounted for underneath.

This is the companion to the web app in [`../finance-tracker`](../finance-tracker).
Backups move between the two: the file format is identical.

---

## Install it

**[Download paisa.apk](https://github.com/maahi-sada/top-gainers-trader/releases/download/latest/paisa.apk)** — open that link on your Android phone and tap the download. When it asks, allow your browser to install unknown apps.

Android 8.0 or newer. Every push to `main` rebuilds it and replaces that file, so the link always points at the current build.

## What is verified, and what is not

Being precise, because these are not the same thing:

| | |
|---|---|
| Compiles | Yes — built on CI, no errors, no warnings |
| APK produced | Yes — 18 MB, signed with the debug key |
| Core logic | 146 tests, all passing on every build |
| Cross-app backups | 9 tests read a real web-app export and assert identical totals |
| **Ever run on a device** | **No** |

That last row matters. No screen in this app has ever been rendered, no SMS has
ever actually been captured on a phone, and no mailbox has ever been connected.
The arithmetic underneath is thoroughly tested and the whole thing compiles, but
the first time it runs will be on your phone.

So expect rough edges on first launch — a layout that needs padding, a permission
prompt that appears at an awkward moment. Report them and they get fixed. What
should *not* be wrong is any number it shows you.

## Building it yourself


You need **Android Studio** (Ladybug or newer) or a command line with the Android
SDK installed.

```bash
git clone <this repo>
cd paisa-android
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Or open the `paisa-android` folder in Android Studio and press Run.

Install the APK on your phone (you will need "install unknown apps" enabled for
whatever you transfer it with). This app is **not for the Play Store** — Google
restricts SMS permissions to apps whose core function is messaging, and a personal
finance tracker does not qualify. Sideloading it for yourself is fine.

Minimum Android 8.0 (API 26).

### Checks worth running before you push

The build on CI is the real check, but these two catch hand-editing mistakes in
seconds without waiting for a full Android build:

```bash
python3 tools/check_calls.py    # every call matches its declaration
python3 tools/check_refs.py     # imports, resources, manifest, bracket balance
```

## Reading your messages

Bank SMS are read **as they arrive** by a broadcast receiver, and you can pull in
history from **Settings → Read the last 90 days of messages**.

Everything happens on the phone. Messages are never uploaded, and the app has no
server to upload them to.

What it refuses to turn into an entry, with the reason shown: OTPs, "will be
debited" notices, failed or reversed payments, collect requests, bill reminders,
promotional offers, and plain balance statements. It also never mistakes the
available balance quoted in the same message for the transaction amount.

Permissions asked for: `RECEIVE_SMS` and `READ_SMS` for the above,
`POST_NOTIFICATIONS` to tell you something was captured, and `INTERNET` for email.

## Reading your emails

Card statements and alerts usually arrive by email rather than SMS. **Settings →
Reading your bank emails** connects a mailbox over IMAP.

- Use an **app-specific password**, never your main one. For Gmail: Google Account
  → Security → 2-Step Verification → App passwords.
- The mailbox is opened **read-only**. Nothing is marked read, moved or deleted.
- Only messages that look like bank alerts are opened at all — the major Indian
  banks, card issuers and wallets are recognised, and you can add any other sender.
- HTML is flattened to text, then the body is read as a whole *and* line by line,
  keeping whichever reading is most confident. A table like
  `Amount | Rs. 2,499.00` alongside `Merchant | AMAZON` is read correctly.
- It runs every three hours in the background, and on demand from **Check now**.

### Card details read from your statements

Your card statement already states the limit, the day the bill closes and the day
it falls due. Paisa reads them rather than making you type them in.

- The labels Indian issuers actually write are matched — Total Amount Due, Minimum
  Amount Due, Payment Due Date, Statement Date, Credit Limit, Available Credit
  Limit, Cash Limit — and the value that follows is taken, whether it sits on the
  same line, in the next table cell, or on the line below.
- Dates read day-first for Indian banks and month-first for the international
  ones. A due date written without a year resolves to the nearest one.
- "Available Credit Limit" is never read as the credit limit, and "Minimum Amount
  Due" is never read as the total due: the longer label always wins over the one
  nested inside it.
- An advert selling a limit is not a statement. A real statement always carries a
  date, so marketing language is fatal only when no date is anywhere in sight.
- The statement is matched to a card by its last four digits, then by issuer when
  only one card could be meant. A card Paisa has never seen is created outright.
  The digits are remembered, so later purchases on that card route themselves.
- A statement never becomes a transaction. The purchases on it were each alerted
  already, and logging them again would double count.

Bill reminder SMS work the same way, so the due date lands even if the statement
email never arrives.

Credentials live in `EncryptedSharedPreferences` (a key held in the device
keystore) and are excluded from cloud backup.

## The four screens you asked for

**Today** — every rupee. The daily earning ring, what came in and went out today,
money in hand, net worth, card debt, what you are owed and what you owe, and the
month against your budget.

**Targets** — a dedicated screen for daily earning targets. Today against the
target, how many days in a row you have hit it, and whether the month is on pace,
including *what each remaining day has to bring* to finish on target. Plus the last
fourteen days at a glance.

**Debts** — who owes you and who you owe, per person, with due dates and overdue
flags, a progress bar per debt, and a full history behind each one. Repayments can
carry an interest portion, which counts as real income or expense while the rest
clears the principal.

**Cards** — per card: outstanding, credit limit and how much of it is used, what
was on the last statement, what has been spent since, when the bill is due and how
many days that is — plus what the bank itself billed and the minimum due, read
straight off the statement, with a line saying where those details came from.
Paying a bill is a transfer, so it reduces the card without ever being counted as
spending twice.

## How the money model works

Seven kinds of movement, so debt never pollutes your income and spending figures:

| Entry | Money | Counts as | Also does |
|---|---|---|---|
| Spent | out | spending | — |
| Earned | in | earnings | — |
| Transfer | between your own accounts | neither | pays a card bill |
| Lent out | out | neither | someone now owes you |
| Borrowed | in | neither | you now owe someone |
| Got back | in | neither* | clears what they owe |
| Repaid | out | neither* | clears what you owe |

\* except the interest portion, which is real income or a real expense.

A credit card is an account whose balance goes negative as you spend and climbs
back as you pay. So "money in hand" excludes cards, and net worth subtracts them.

Amounts are integer **paise** everywhere. ₹420.50 is 42050, and it stays exactly
that.

## Where captures go

Nothing reaches your ledger unseen. A captured message lands in the **Inbox** with
a suggested category and account, and you tap ✓ or open it to change something.

Each confirmation teaches it: file `swiggy@icici` under Food & Dining once and
later Swiggy messages arrive pre-filed. Confirm that a message ending `A/c XX1234`
is your HDFC account, and later ones route there — this is also how a message
mentioning a card is routed to the right credit card rather than your bank.

Once the suggestions look right, **Settings → Log known shops without asking**
lets recognised shops skip the Inbox. It is off until you turn it on.

Duplicates cannot get in: every capture is fingerprinted by its bank reference, so
an SMS and an email about the same transaction produce one entry, not two.

## Sharing data with the web app

**Settings → Export** writes the same JSON the web app reads, and **Import** reads
what the web app writes. Nine tests in `core` exist purely to keep that true.

## Layout

```
paisa-android/
├── core/                     pure Kotlin, no Android — and fully tested
│   ├── Money.kt              paise arithmetic and Indian formatting
│   ├── Model.kt              accounts, categories, entries, debts
│   ├── MessageParser.kt      reads bank / UPI messages
│   ├── EmailText.kt          HTML to text, bank-mail detection
│   ├── Ledger.kt             balances, debts, summaries, categories
│   ├── CardCycle.kt          credit card statement and due-date maths
│   ├── CardStatementParser.kt limits, statement dates and due dates from mail
│   ├── DailyTarget.kt        earning targets, streaks, month pace
│   ├── Schedule.kt           repeating entries
│   ├── Snapshot.kt           the storage format shared with the web app
│   ├── AppData.kt            every state change, as pure functions
│   └── src/test/             92 tests
└── app/                      Android: screens, storage, capture
    ├── data/                 the JSON file store, encrypted credentials
    ├── capture/              SMS receiver, inbox backfill, IMAP, notifications
    └── ui/screens/           Today, Targets, Debts, Cards, Inbox, Ledger, Settings
```

`core` is a separate Gradle build on purpose, so its tests run anywhere a JDK
exists — no SDK, no emulator, no device.

## Privacy

No analytics, no accounts, no servers. The only outbound connection the app makes
is to the IMAP server you configure yourself. Messages and emails are read on the
device and stay there.
