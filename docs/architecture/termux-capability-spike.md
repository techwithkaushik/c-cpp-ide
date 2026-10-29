# Termux integration capability spike

This is a validation artifact, not a claim that Termux integration already works. Run these checks on a real Android device before writing the production adapter.

## Device record

Record before testing:

- Android version / API level:
- Device ABI:
- Termux package source and version:
- Termux external-command configuration state:
- IDE version / signing identity:
- Date and test operator:

Do not record private project contents or secrets.

## Results

Use **PASS**, **FAIL**, or **NOT RUN**. A failed or unrun gate must not be described as validated.

| ID | Test | Expected evidence | Status |
|---|---|---|---|
| API-01 | Confirm current official Termux RUN_COMMAND docs and version requirements | Link/version notes recorded in issue or test report | NOT RUN |
| API-02 | Termux not installed | Actionable setup message; no crash | NOT RUN |
| API-03 | External command access disabled | Permission/configuration denial; command not run | NOT RUN |
| API-04 | Permission granted and access configured | Harmless version command returns output and exit code | NOT RUN |
| API-05 | Termux process unavailable / app updated | Recoverable error; no ANR | NOT RUN |
| WS-01 | Workspace opened using supported access route | Canonical workspace identity and access method displayed | NOT RUN |
| WS-02 | Edit a file from IDE and inspect from intended Termux view | Same content; no hidden duplicate workspace | NOT RUN |
| WS-03 | Spaces, Unicode, shell metacharacters in path/arguments | Literal paths preserved; no shell injection | NOT RUN |
| CMD-01 | Compile valid C and C++ sample | Correct executable and exit status | NOT RUN |
| CMD-02 | Deliberately invalid source | Compiler stderr and nonzero exit shown | NOT RUN |
| CMD-03 | Large stdout/stderr | Memory bounded; truncation explicit | NOT RUN |
| CMD-04 | Cancel long-running command | Process stops, or limitation explicitly documented | NOT RUN |
| STREAM-01 | Persistent bidirectional process | Input and output round-trip until process exit | NOT RUN |
| STREAM-02 | clangd LSP handshake | initialize/initialized and framed JSON-RPC messages work | NOT RUN |
| STREAM-03 | Backpressure and large messages | No unbounded memory growth; order preserved | NOT RUN |
| STREAM-04 | Process crash / client disconnect | Session transitions to terminal error and resources close | NOT RUN |
| STREAM-05 | Terminal PTY input, output, resize | Real PTY behavior; no simulated output | NOT RUN |
| SEC-01 | Inspect manifest/permissions and runtime traffic | No root, no SELinux weakening, no localhost TCP, no Internet permission needed for local bridge | NOT RUN |
| SEC-02 | Attempt path traversal and unauthorized caller | Request rejected; no private app data exposed | NOT RUN |
| COMPAT-01 | Android 10 / API 29 | All applicable supported gates pass | NOT RUN |
| COMPAT-02 | Current supported Android version | All applicable supported gates pass | NOT RUN |

## Go/no-go rules

- Gates API-01 through API-05 are required before committing to RUN_COMMAND behavior.
- Workspace support is not complete until the source-of-truth and permission model pass WS-01 through WS-03.
- Compiler/build support can be delivered independently after CMD-01 through CMD-04 pass.
- LSP, interactive terminal, and debugger must remain **unsupported / experimental** until STREAM tests prove a real persistent bidirectional session.
- If any proposed transport requires root, SELinux changes, a localhost TCP listener, undocumented Termux internals, or direct access to Termux private files, reject it.
- Do not mark any row PASS without device logs or a reproducible test report attached to the project issue/PR.

## Current spike implementation

A minimal implementation now exists in `:integrations:termux`:

- `TermuxCommandClient.inspect(context)` checks package visibility, RUN_COMMAND service resolution, and whether the app has the declared permission.
- `TermuxCommandClient.probeCommandApi(context)` dispatches a fixed `printf` marker command and awaits a bounded result.
- `TermuxBuildRunner.compileAndRun(context, request)` now constructs shell-free argv for Termux `clang`/`clang++`, stops before execution when compilation fails, and passes program arguments as separate argv entries. The executable output must be under Termux home or `usr/tmp`; shared storage may be mounted `noexec`.
- Command-result waiting has a timeout, but the RUN_COMMAND API does not provide a proven cancellation mechanism here. A timeout must not be treated as proof that the underlying process was terminated.
- `TermuxCommandResultService` is private to the IDE app.
- A temporary launch screen in `:app` exposes the checks for manual device testing.

### Device procedure

1. Install a supported Termux build and open it once.
2. In Termux, explicitly set `allow-external-apps = true` in `~/.termux/termux.properties`; restart Termux after changing it. Do not have the IDE edit this file.
3. Install the IDE build and grant its **Run commands in Termux environment** permission in Android App Info.
4. Open the IDE's diagnostic screen and check the detected package, service, permission, and version.
5. Tap **Test Termux command access**. Expected result is a PASS marker and exit code 0.
6. Repeat with permission denied and with external-app access disabled. The app must fail clearly and must not claim the API is ready.
7. Capture Android version/API, Termux source/version, result, and relevant sanitized logcat errors in the test report.

**Important:** This code has not been built or run on a device in this task. RUN_COMMAND extra/result-key compatibility must be confirmed against the actual Termux build. The implementation deliberately accepts documented/legacy result-bundle field variants, but that is compatibility handling—not proof of runtime compatibility.
