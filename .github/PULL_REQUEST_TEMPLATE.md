# Pull request

## What this changes

<!-- A short description. What is different after this change? -->

## Why

<!-- The problem this solves. Link the issue if there is one. -->

## How it was verified

<!--
The command you ran, and its output. A claim without a command is not a
verification.

    ./scripts/g.sh :app:testProdDebugUnitTest
    ./scripts/g.sh :app:connectedDevtestDebugAndroidTest

If something was NOT tested, say so explicitly and say why.
-->

- [ ] The unit tests pass
- [ ] The isolated instrumentation tests pass
- [ ] `bash scripts/audit.sh` passes
- [ ] `python3 scripts/audit-test-isolation.py` passes
- [ ] `bash scripts/audit-secrets.sh` passes

## The hard rules

Please confirm that this change does not break any of these. They are not
negotiable, and the CI will check most of them.

- [ ] No `android.permission.INTERNET` was added
- [ ] No tracking, analytics, advertising, Firebase, or network SDK was added
- [ ] No Root, Accessibility Service, or sandbox bypass was introduced
- [ ] No message content is logged, and `FLAG_SECURE` is not weakened
- [ ] No key material is hard-coded
- [ ] No user data is deleted by a migration
- [ ] The notification listener still cannot be crashed by a malformed notification
- [ ] Background work still runs off the main thread
- [ ] The UI still uses the framework's own views

## Data and migrations

- [ ] Any schema change is **additive** and does not delete or rewrite a row
- [ ] The encrypted storage format is unchanged, or the change is described in the PR
- [ ] Existing messages still decrypt

## Destructive tests

If the change touches the tests, please confirm:

- [ ] Any test that destroys the database goes through `TestDataIsolation`
- [ ] The test runs against the isolated `devtest` variant, not the production one

## Privacy

- [ ] No test fixture, screenshot, or log line contains real message content
- [ ] The diagnostics export contains no content, only codes and types

## Documentation

- [ ] The matching document was updated in the same commit
- [ ] Any changed behaviour is reflected in `docs/TEST-REPORT.md`
