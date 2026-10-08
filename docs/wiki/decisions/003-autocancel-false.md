# 003 — `Sinks.many(autoCancel=false)` everywhere

**Status:** Adopted; load-bearing.

## Decision

Every `Sinks.Many` in the BFF passes `autoCancel=false` explicitly. **No exceptions are intended.** Two sinks break the rule today; see [Known violations](#known-violations).

```java
Sinks.many()
    .multicast()
    .onBackpressureBuffer(BUFFER_SIZE, /* autoCancel */ false);
```

## Why

`Sinks.many().multicast().onBackpressureBuffer(N)` defaults to `autoCancel=true`. With that default, when every subscriber disconnects (iframe remount, EventSource reconnect, network blip), the sink terminates **permanently**. Every later `tryEmitNext` call drops silently with `EmitResult.FAIL_TERMINATED`.

In a portal where players reload tabs all day, this reads as: "the SSE worked when I opened the page, then I refreshed, and now nothing updates." The producer cannot recover. Only a process restart helps.

## How to apply

- Wherever the BFF creates a sink, the third argument MUST be `false`.
- Make it a code-review checklist item. See [the full-stack developer guide](../guides/fullstack-developer.md).
- When you add an SSE channel, [howto/add-sse-channel.md](../howto/add-sse-channel.md) repeats the rule in step 1.

## Known violations

Two multicast sinks on `origin/main` still use the default `autoCancel=true`:

- `GameTimeService.timeSink` (`onBackpressureBuffer(16)`). `GameStreamController` serves it as the `time` SSE channel. Its Javadoc states the `autoCancel = true` default.
- `PgNotificationService.sink` (`onBackpressureBuffer(256)`).

Both break this rule. `AuthoritativeTickService` also creates a per-match `Sinks.many().replay().latest()`. That is a replay sink, not a multicast one, and this rule does not cover it.

## Diagnosis if you suspect the rule was broken

1. The symptom is "first reconnect, then silence" on a single channel.
2. Run `grep -n 'Sinks.many' backend/src/main/java -r` and audit each call site.
3. If a sink lacks `autoCancel=false`, restart the BFF as a workaround. That kills the sinks and forces a re-subscribe. The real fix is in the source.

## Trade-offs accepted

- **Sinks live forever once created.** A service that allocates sinks dynamically must call `tryEmitComplete` on each one when its session ends. Most sinks are fields on a singleton service. Two are not: `SessionSinks` allocates seven per session ([decision 010](010-per-session-sse-sinks.md)), and `AuthoritativeTickService` allocates a replay sink per match. The session sinks are never completed or removed. The match sink is completed at match end (`ms.snapshots.tryEmitComplete()` in `AuthoritativeTickService`).
- **`autoCancel=false` is unfamiliar to new contributors.** The rule appears in four places on purpose: [02 — Runtime architecture](../architecture/02-runtime.md#-the-autocancel-trap), [the architecture PDF chapter 2](../../publishing/architecture/chapters/02-runtime-architecture.tex), [the full-stack guide](../guides/fullstack-developer.md), and [the maintainer runbook](../guides/maintainer-runbook.md).

## Reference

- [02 — Runtime architecture](../architecture/02-runtime.md#-the-autocancel-trap)
- [Maintainer runbook 2 — SSE silently drops](../guides/maintainer-runbook.md)
