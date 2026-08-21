# top-gainers-trader
15 min BO 

## Paisa — personal money tracker

This repo also contains **[`finance-tracker/`](finance-tracker/)** — a standalone app for
tracking personal spending, earnings and debts down to the paisa. It runs on a phone
and a laptop from the same codebase, works offline, and stores data on the device.

See [finance-tracker/README.md](finance-tracker/README.md) for how to run and install it.

## Paisa for Android

**[`paisa-android/`](paisa-android/)** is the native Android companion. It reads bank
SMS and bank emails on its own, and gives debts, daily earning targets and credit
card spending each a dedicated screen. Its calculation module is plain Kotlin with
92 passing tests; the two apps share a backup format.

**[Download the APK](https://github.com/maahi-sada/top-gainers-trader/releases/download/latest/paisa.apk)** — rebuilt on every push to `main`.

See [paisa-android/README.md](paisa-android/README.md).
