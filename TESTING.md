# What to try

The app is fully usable on its own — no account, no backend, no internet. Data
lives in one JSON file on the device.

## Getting it on your phone

Plug the phone in with USB debugging on, then:

```
cd Projects/AndroidTasks
./gradlew installDebug
```

Or `./gradlew assembleDebug` and copy `app/build/outputs/apk/debug/app-debug.apk` across.

## Loading the test data

There's a fixture with three people, a rotation and a few tasks already set up —
much faster than typing it all in. Copy the contents of `testdata/sharehouse.json`,
then in the app: **Settings → Import → paste → Replace**.

It gives you three placeholder people — Person One (you), Person Two and Person
Three — a rotating weekly bin run, rent on the last day of the month, a request
from Person Two waiting on your answer, and an overdue job. Rename them in
Settings → People once you know who's actually in your household.

## The interesting bits

**Tags are the whole app.** There are no lists. Every card on the home screen is a
saved filter, and long-pressing one opens it for editing. "+ New card" makes your
own — that *is* making a list.

**Short months.** Add a task, tap **Repeating**, choose **month**. If the date is
the 29th, 30th or 31st you get asked what should happen in February, shown as
real dates rather than described. Worth trying on the 29th and on the 31st —
you get three options on the 29th and two on the 31st, because from the 31st
"closest day" and "last day" are the same rule and offering both would be
meaningless.

**Rotations.** Tick the bins. It doesn't disappear — it moves to next week and the
turn passes to Person Two. Tick it again the same day and nothing happens, which is
deliberate: two people ticking the same chore shouldn't skip somebody's turn.

**Assignment is a request.** Open a task, tap Person Two's chip under *Who*. It goes
outlined, not filled — they've been asked, not assigned. Your own chip fills in
immediately, because taking something on yourself needs nobody's permission.
Person Two's request to you shows under **Requests** with Accept/Decline.

**Reminders.** Set one a couple of minutes out, lock the phone. It should arrive
on the minute, with **Done** and **Snooze** on the notification itself. Ticking
Done from the shade completes it without opening the app. Reboot with one pending
and it should still arrive.

**Undo.** Tick anything and the snackbar offers Undo — including repeating tasks,
where it puts the date *and* the roster position back.

**Deleting a tag** tells you what it's holding up first, then strips it from
tasks and filters rather than leaving dead references behind.

## Not built yet

- **Sharing.** Everything is single-device so far. The store sits behind an
  interface specifically so a syncing one can replace it, and the merge layer
  underneath that is written and tested (`domain/sync/`). What is missing is the
  transport: pairing by QR code once, then syncing over Bluetooth whenever the
  two phones are near each other. Nothing leaves the phones and there is no
  account — an earlier plan to use Firebase was dropped in favour of this.
- Weather driving the home grid. The `indoor`/`outdoor`/`rainy day` tags work as
  ordinary tags; nothing reads a forecast yet.
- Home-screen widget.
- Household activity history ("who did the bins last three times").

## If something looks wrong

`./gradlew test` runs 102 unit tests over the date arithmetic, rotations,
filtering and storage — that's where the subtle bugs would be. The app's raw
state is readable with:

```
adb shell run-as dev.mahourigan.tasks cat files/tasks.json
```
