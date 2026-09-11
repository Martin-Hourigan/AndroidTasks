# Household tasks

A shared to-do list for a house, built around the observation that lists are
the wrong shape for chores.

There are no lists here. Every card on the home screen is a **saved filter over
tags**, so "Kitchen", "This week" and "Waiting on someone" are the same kind of
object and a task can be in all three at once without being copied.

Nothing leaves the device. No account, no server, no network permission — the
whole diary is one JSON file in the app's private storage.

## What it does

- **Rotations.** Tick the bins and the task doesn't disappear: it moves to next
  week and the turn passes to the next person. Tick it twice in a day and
  nothing happens, deliberately — two people ticking the same chore should not
  skip somebody's turn.
- **Assignment is a request, not an order.** Asking someone to do something
  shows as an outlined chip until they accept. Taking a task on yourself is
  immediate, because that needs nobody's permission.
- **Recurrence that survives February.** Monthly on the 31st asks you what it
  should do in a short month, and shows you real dates rather than describing
  the rule. From the 31st you get two choices instead of three, because
  "closest day" and "last day" are then the same rule.
- **Reminders** via `AlarmManager`, with Done and Snooze on the notification
  itself and a boot receiver so a pending one survives a restart.
- **Undo on everything**, including repeating tasks — it puts the date *and*
  the roster position back.

## Building

```
./gradlew installDebug     # onto a connected phone
./gradlew test             # 102 unit tests
```

The tests are where the value is. Date arithmetic, rotations, filtering, the
storage round-trip and the sync merge are all pure Kotlin with no Android
dependency, which is what makes them testable at all.

See [TESTING.md](TESTING.md) for a tour of the parts worth trying by hand, and
a fixture in `testdata/` to import so you don't have to type a household in.

## Sharing between phones

Being built. The plan is deliberately unusual: **no cloud**.

Two phones pair once by QR code, which is a channel a network attacker cannot
reach, and afterwards sync directly over Bluetooth whenever they are near each
other. Everything on the wire is encrypted under the key the QR established.
There is no account, no server, and nothing to trust but the two handsets.

The hard half of that is not the transport but the merge: two people editing
the same household while offline, then meeting. That part is written and
tested — see `domain/sync/`, where the merge is a pure function pinned by three
properties (commutative, associative, idempotent) that between them are what
make convergence possible at all.

## Structure

| | |
|---|---|
| `domain/` | Pure Kotlin. No Android imports, so all of it unit-tests on the JVM. |
| `domain/sync/` | The hybrid logical clock, tombstones, and the merge. |
| `data/` | Serialisation and the one-file store, behind a `TaskRepository` interface. |
| `ui/` | Compose screens. |
| `notification/` | Alarms and the notification actions. |
