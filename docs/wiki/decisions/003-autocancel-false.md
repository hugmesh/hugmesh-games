# 003 — `Sinks.many(autoCancel=false)` everywhere

**Status:** Adopted; load-bearing.

## Decision

Every `Sinks.Many` in the BFF passes `autoCancel=false` explicitly. **No exceptions.**

```java
Sinks.many()
    .multicast()
    .onBackpressureBuffer(BUFFER_SIZE, /* autoCancel */ false);
```

## Why

`Sinks.many().multicast().onBackpressureBuffer(N)` defaults to `autoCancel=true`. With that default, when every subscriber disconnects (iframe remount, EventSource reconnect, network blip), the sink terminates **permanently** and all subsequent `tryEmitNext` calls silently drop with `EmitResult.FAIL_TERMINATED`.

In a portal where a player reloading their tab is a normal event, this manifests as: "the SSE worked when I opened the page, then I refreshed, and now nothing is updating." The producer side has no way to recover except a process restart.

## How to apply

- Anywhere a sink is created in the BFF, the third argument MUST be `false`.
- Code review checklist item — see [the full-stack developer guide](../guides/fullstack-developer.md).
- When adding a new SSE channel: [howto/add-sse-channel.md](../howto/add-sse-channel.md) reminds you in step 1.

## Diagnosis if you suspect the rule was broken

1. The symptom is "first reconnect, then silence" on a single channel.
2. `grep -n 'Sinks.many' backend/src/main/java -r` and audit each call site.
3. If a sink is missing `autoCancel=false`, restart the BFF (kills sinks, forces re-subscribe) as a workaround. The fix lives in the source.

## Trade-offs accepted

- **Sinks live forever once created.** If a service is ever rewritten to dynamically allocate sinks per session, those sinks must be explicitly `tryEmitComplete`'d on session end. Today every sink in the project is field-level on a singleton service, so this isn't an issue.
- **`autoCancel=false` is unfamiliar to new contributors.** Documented prominently in [02 — Runtime architecture](../architecture/02-runtime.md#-the-autocancel-trap), [the architecture PDF chapter 2](../../publishing/architecture/chapters/02-runtime-architecture.tex), [the full-stack guide](../guides/fullstack-developer.md), and [the maintainer runbook](../guides/maintainer-runbook.md) — the rule shows up four places on purpose.

## Reference

- [02 — Runtime architecture](../architecture/02-runtime.md#-the-autocancel-trap)
- [Maintainer runbook 2 — SSE silently drops](../guides/maintainer-runbook.md)
