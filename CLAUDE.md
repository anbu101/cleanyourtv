# CleanYourTV — notes for Claude

## QA Register (keep it current, every time)

The QA Register is the Sheets artifact "CleanYourTV — QA Register":
https://claude.ai/artifact/H6Nz9kXCkSodVuqqzpfNMQ (tabs: Test Cases, Defects, To-Do).

In the same piece of work, without being asked, update it whenever you:

- **add, change or run a test**: one row per test case on **Test Cases**, in plain English,
  with the expected result, how to run it (the exact ADB command or remote-control steps),
  and the last result with its date. IDs are `TC-<AREA>-<NNNN>` (areas so far: `YT` YouTube,
  `NET` network). A new case takes the next number in its area, and existing IDs never change.
- **find or fix a defect**: one row on **Defects** with columns Defect ID (`DEF-NNN`),
  description, date/time found, versions where it occurred (app, Play services, firmware),
  TV, root cause, fix applied (with the command that undoes it), fixed date, Test Case IDs
  added, Test Case IDs tested, status, severity, and notes (including what was ruled out).
  Status moves Open → In progress → Fixed – verifying → Fixed, or Closed – no change.
- **plan or make a change, a feature or a fix**: a row on **To-Do** (`TD-NNN`), with its
  status moved along as the work goes.

A defect on the TV is **Fixed** only once the fix has survived a TV restart and its test
cases pass again afterwards. DEF-001 looked fixed twice before it really was.

If the Sheet cannot be reached in a session (no Claude Docs connector), say so in that
session, and list the rows that still need adding.

## Start here

- `README.md`: how the repo works (back up, disable with `pm disable-user`, install the
  launcher) and the scripts in `tools/`.
- `PROMPT.md`: the brand-neutral prompt most people run. `examples/<tv>/debloat.txt`: the
  per-TV lists. `docs/TROUBLESHOOTING.md`: known traps. `CONTRIBUTING.md`: repo rules.
- This household's TV: `examples/oneplus-y1s-pro/` (`SETUP.md` is its setup guide).
- Tests: `python3 -m unittest discover -s tests -v` from the repo root (what CI runs).

## The TV: OnePlus 43Y1S Pro (model C00, Android TV 11)

- LAN address 192.168.1.6 (`Android.local`), ADB on port 5555 once USB debugging is on.
  The first connection from a new computer shows "Allow USB debugging?" on the TV.
- IPv4 port 5555 sometimes refuses while IPv6 works. Find the addresses with
  `dns-sd -G v4v6 Android.local.` and connect with `adb connect '[<IPv6 address>]:5555'`.
- ADB drops ("device offline") for the first few minutes after a boot and on long commands.
  Reconnect before each command. For anything longer than about 30 s, write a script to
  `/data/local/tmp`, start it with `nohup … &`, poll its output file, and delete the test
  files afterwards.
- The shell is toybox: no `curl` or `wget`, `ping` takes no `-4`/`-6` (use `ping6`), and
  `nc` has no `-z`.
- `screencap` works. A ~10 KB capture while YouTube is in front is its empty grey window;
  a loaded YouTube home screen is over 300 KB.
- A load average around 60 is normal on this chip (MStar driver threads that sleep in the
  D state). `gralloc mma_import_handle Failed` lines in logcat are firmware noise.
- The clock boots at a date in 2024 and corrects itself about 20 s later.
- Settings changed on purpose (do not revert without asking):
  - `private_dns_mode=off`: the fix for DEF-001 (YouTube blank for 1–2 minutes). Encrypted
    DNS (port 853) times out from this TV on Airtel AirFiber.
  - `captive_portal_mode=0`, `captive_portal_server=127.0.0.1`: from `SETUP.md` step 8.
- Home is the CleanYourTV launcher (`com.gathin.tvhome`); `pm list packages -d` lists what
  is disabled.

## Rules

- Nothing is uninstalled: disable with `pm disable-user` only. Before changing a package or a
  setting on the TV, note its old value, and record the change and its undo in the register.
- Never run `pm uninstall-system-updates` without a package name: with none it rolls back
  every system app.
- Ask before anything that signs the user out, clears an app's data, restarts the TV or
  changes Home.
- Measure before and after (for example, seconds from launch to the YouTube home screen),
  and re-test after a TV restart before calling a TV fix done.
- When TV work is finished, remind the user to turn off USB debugging: port 5555 accepts
  ADB from anything on the Wi-Fi.
- Repo changes follow `CONTRIBUTING.md`: one topic per pull request, say which TV it was
  tested on, brand-specific knowledge goes in `examples/`, brand-neutral fixes in `PROMPT.md`.
