# Security Policy

`react-native-persistent-background-location` handles **location data**, which
is sensitive personal information (PII). We take security and privacy reports
seriously and appreciate responsible disclosure.

---

## Supported versions

Security fixes are provided for the versions below. We follow semantic
versioning; fixes land on the latest minor of the current major and are
back-ported only when feasible.

| Version | Supported          | Notes                                              |
| ------- | ------------------ | -------------------------------------------------- |
| `0.1.x` | :white_check_mark: | Current release line — receives security fixes.    |
| `< 0.1` | :x:                | Pre-release / unpublished. Please upgrade.         |

Once a `0.2.x` line ships, the previous minor (`0.1.x`) will receive critical
security fixes for a transition period and this table will be updated. As a
pre-1.0 package, the public API may still change between minor versions; always
run the latest patch of your minor.

---

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
pull requests, or discussions.** Public disclosure before a fix is available puts
users' location data at risk.

Use either private channel:

1. **GitHub Security Advisories (preferred).** Go to the repository's
   **Security → Advisories → Report a vulnerability** page
   (<https://github.com/aashir-athar/react-native-persistent-background-location/security/advisories/new>).
   This opens a private advisory only you and the maintainers can see, and lets us
   collaborate on a fix and coordinate a CVE if warranted.
2. **Email.** Write to **subscriptions@hybriddot.com** with subject
   `SECURITY: react-native-persistent-background-location`. If you wish to
   encrypt, ask in a first contact email and we'll arrange a key.

### What to include

To help us triage quickly, please include as much of the following as you can:

- A clear description of the issue and its security impact (what an attacker can
  read, write, exfiltrate, or trigger).
- The affected version(s) and platform (Android / iOS), OS version, and device.
- Step-by-step reproduction, a proof-of-concept, or a failing test.
- Any relevant logs (`adb logcat` / Xcode console) with **your own** location
  coordinates redacted.
- Whether the issue is already public or known elsewhere.

Please make a good-faith effort to avoid privacy violations, data destruction,
and service disruption while researching. Only test against apps, backends, and
devices you own or are explicitly authorized to test.

---

## Response SLA

We are a small open-source project, but we aim to meet the following targets for
privately reported vulnerabilities:

| Stage                                   | Target                          |
| --------------------------------------- | ------------------------------- |
| Acknowledge receipt of your report      | within **48 hours**             |
| Initial assessment & severity triage    | within **5 business days**      |
| Status update cadence while we work     | at least **every 7 days**       |
| Fix or mitigation for confirmed issues  | typically within **30–90 days**, prioritized by severity |
| Public disclosure / advisory + credit   | coordinated with you, **after** a fix is released |

If a report is declined (e.g. out of scope, not a vulnerability), we'll explain
why. We're happy to credit reporters in the advisory and release notes unless you
prefer to remain anonymous. We do not currently run a paid bug-bounty program.

---

## A note on location data and privacy

Location data is among the most sensitive categories of PII. This library is
designed to keep that data under the **app developer's** control, not ours:

- **Storage is local and app-private.** Fixes are buffered in a **native SQLite
  database inside the app's private storage** (the OS-sandboxed app data
  directory). They are not written to shared/external storage and are not
  accessible to other apps on a non-compromised device.
- **Transmission goes only where you point it.** Buffered fixes are transmitted
  **only** to the `syncUrl` you configure in `buffer.syncUrl`, using the HTTP
  method and headers you supply. The library has **no telemetry, no analytics,
  and no "phone home"** — it never sends location data (or anything else) to the
  author, to HybridDot, or to any third party. If you set no `syncUrl`, nothing
  leaves the device over the network.
- **You own the transport and the backend.** A non-HTTPS `syncUrl` is **rejected
  by `start()`** unless you explicitly set `buffer.allowInsecureSync: true` (for
  local development), so location PII is never sent over cleartext by accident.
  The security of your backend, its TLS configuration, and the credentials you
  place in `buffer.headers` are your responsibility.
- **Auth headers are persisted, then wiped on stop.** So that headless resume
  (Android boot / sticky-restart, iOS significant-location-change relaunch) can
  keep syncing without the JS layer, the active config — **including
  `buffer.headers`** — is written to app-private `SharedPreferences` (Android) /
  `UserDefaults` (iOS). This storage is sandboxed but **not encrypted**, and it
  is cleared when you call `stop()`. Prefer **short-lived / rotatable tokens**,
  and avoid placing long-lived secrets in `buffer.headers` on devices you do not
  control. On a rooted/jailbroken or backed-up device, app-private storage can be
  read.
- **Retention is bounded but local.** `maxRecordsToPersist` caps the buffer and
  drops the oldest rows; `clearBuffer()` deletes all buffered fixes. There is no
  remote copy for us to expose because we never receive one.
- **Permissions are explicit.** Continuous background tracking requires the user
  to grant "Always"/background location (and a foreground-service notification on
  Android). The library cannot and does not track without OS-granted permission.

**Your obligations as an app developer:** you are the data controller for the
location data your app collects with this library. Make sure your privacy policy,
in-app disclosures, and store listings accurately describe background location
collection, that you have a lawful basis to collect it, and that your `syncUrl`
backend stores it securely and honors deletion requests. App Store and Google
Play both require clear disclosure of background location use.

If you believe the library itself leaks location data anywhere other than your
configured `syncUrl`, that is a security vulnerability — please report it through
the private channels above.
