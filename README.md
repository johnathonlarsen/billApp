# Family Bank App

Personal **family bill tracker** for up to **3 banks** — track what's due, from which account, and mark bills paid by month.

**One APK** for everyone. Each phone keeps its own bills (yours on your device, mom's on hers).

## Features

- **Home** — due soon, overdue, this month's paid status, pay-from labels
- **Banks** — up to 3 banks with accounts (labels for linking bills, no balances)
- **Bills** — manual entry, **CSV import**, month timeline, mark paid / undo
- **Reminders** — background bill notifications (WorkManager)
- **On-device storage** — Room database, no cloud required

## Two-phone setup

| Your phone | Mom's phone |
|------------|-------------|
| Capital One (linked) | Keesler + Hancock Whitney |
| Your bills (manual or CSV) | Her bills (manual or CSV) |
| Same APK | Same APK |

Add banks first, then add bills manually or import a CSV on the **Bills** tab.

## CSV import

Create a spreadsheet or text file with these columns:

```csv
name,amount,due_day,category,recurrence,pay_from,reminder_days,notes
Mortgage,1850.00,1,Housing,Monthly,Capital One · 360 Checking,5,
Electric,145.00,12,Utilities,Monthly,Keesler FCU · Primary Checking,3,
```

- **amount** — dollars (e.g. `145.00` or `145`)
- **due_day** — day of month (1–28)
- **category** — Housing, Utilities, Subscription, Insurance, Loan, Other
- **recurrence** — Monthly, Weekly, Yearly, One-time
- **pay_from** — optional; must match `Bank name · Account name` in the app

See `bills_import_template.csv` in this folder for an example.

## Run / build

```bash
cd bankapp
gradlew.bat assembleDebug
```

APK copies to `%USERPROFILE%\OneDrive\FamilyBank.apk` when OneDrive is available.

## Focus

This app tracks **bills and due dates**, not bank balances. Banks and accounts exist so each bill can show **pay from Keesler · Checking** (etc.).
