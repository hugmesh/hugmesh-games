# How to add a new SSE channel

Adding a new typed channel to the merged `/api/game/stream` so subscribers can `eventSource.addEventListener('foo', cb)`.

## 1. Add the sink to `GameStateService`

In [`backend/src/main/java/games/hugmesh/bff/service/GameStateService.java`](../../../backend/src/main/java/games/hugmesh/bff/service/GameStateService.java):

```java
private final Sinks.Many<FooEvent> fooSink = Sinks.many()
    .multicast()
    .onBackpressureBuffer(64, /* autoCancel */ false);
```

The buffer size is a literal; the existing sinks use 16 to 128 (`GameStateService.java:188-203`).

> ⚠ **`autoCancel=false` is mandatory.** See [decision 003](../decisions/003-autocancel-false.md). Default-`true` sinks die on the first reconnect.

Add a public `emitFoo(FooEvent e)` method that calls `fooSink.tryEmitNext(e)`, and a public `Flux<FooEvent> fooStream()` that returns `fooSink.asFlux()`.

If the channel must be filtered by session, also add a sink to `SessionSinks` and a `fooStream(String sessionId)` overload, as `movementStream(sessionId)` does, and emit to both sinks.

## 2. Build the SSE flux wrapper

The wrapping to `ServerSentEvent` happens in the controller, not the service. In `stream(...)` of `GameStreamController`, next to the other channel definitions:

```java
Flux<ServerSentEvent<Object>> foo = gameStateService.fooStream()
        .map(f -> sse(sseId, "foo", f));
```

## 3. Merge it in `GameStreamController`

In the same method's `Flux.merge(...)` call, add `foo` alongside the existing channels and the keepalive.

## 4. Add a DTO if needed

If `FooEvent` is a new shape, add the record to `model/`. For the GraalVM native image, add the class to `JSON_BOUNDARY_DTOS` in [`HugMeshRuntimeHints`](../../../backend/src/main/java/games/hugmesh/bff/aot/HugMeshRuntimeHints.java) so Jackson can read its record components by reflection:

```java
private static final Class<?>[] JSON_BOUNDARY_DTOS = {
        // …existing DTOs…
        FooEvent.class,
};
```

[`R2dbcConvertersConfig`](../../../backend/src/main/java/games/hugmesh/bff/config/R2dbcConvertersConfig.java) only registers two timestamp converters (`OffsetDateTime`/`LocalDateTime` to `Instant`). A new SSE shape or entity does not touch it.

## 5. Wire the frontend listener

Wherever you consume the stream:

```ts
this.es.addEventListener('foo', (e) => {
  const payload: FooEvent = JSON.parse((e as MessageEvent).data);
  // ...
});
```

The browser's EventSource model multiplexes — listening to `foo` doesn't open a second connection. The merged stream is one TCP connection per tab.

## 6. Test

```bash
curl -N /api/game/stream
# In another shell, trigger something that calls gameState.emitFoo(...)
# Verify `event: foo\ndata: {...}` shows up in the curl output.
```

## When to NOT add a channel

- For request-response — keep it as a normal `@GetMapping` controller method, not an SSE channel.
- For events scoped to one user — consider a dedicated SSE endpoint (e.g. `/api/me/admin-actions/stream`, which is per-user) rather than emitting on the global stream and filtering client-side.

## See also

- [02 — Runtime architecture](../architecture/02-runtime.md#server-sent-events--nine-channels-on-one-stream)
- [Decision 003 — `autoCancel=false`](../decisions/003-autocancel-false.md)
- [Full-stack developer guide](../guides/fullstack-developer.md)
