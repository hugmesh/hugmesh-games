# How to add a new SSE channel

Adding a new typed channel to the merged `/api/game/stream` so subscribers can `eventSource.addEventListener('foo', cb)`.

## 1. Add the sink to `GameStateService`

In [`backend/src/main/java/games/snapdragon/bff/service/GameStateService.java`](../../../backend/src/main/java/games/snapdragon/bff/service/GameStateService.java):

```java
private final Sinks.Many<FooEvent> fooSink = Sinks.many()
    .multicast()
    .onBackpressureBuffer(BUFFER_SIZE, /* autoCancel */ false);
```

> ⚠ **`autoCancel=false` is mandatory.** See [decision 003](../decisions/003-autocancel-false.md). Default-`true` sinks die on the first reconnect.

Add a public `emitFoo(FooEvent e)` method that calls `fooSink.tryEmitNext(e)`.

## 2. Build the SSE flux wrapper

```java
public Flux<ServerSentEvent<FooEvent>> fooFlux() {
  return fooSink.asFlux()
      .map(e -> ServerSentEvent.<FooEvent>builder(e)
          .event("foo")
          .build());
}
```

## 3. Merge it in `GameStreamController`

In [`backend/src/main/java/games/snapdragon/bff/controller/GameStreamController.java`](../../../backend/src/main/java/games/snapdragon/bff/controller/GameStreamController.java)'s `Flux.merge(...)` call, add `gameState.fooFlux()` alongside the existing channels and the keepalive.

## 4. Add a DTO if needed

If `FooEvent` is a new shape, add the record to `model/` and register it for native-image binding:

```java
@RegisterReflectionForBinding(FooEvent.class)
public record FooEvent(...) {}
```

Add it to [`R2dbcConvertersConfig`](../../../backend/src/main/java/games/snapdragon/bff/config/R2dbcConvertersConfig.java) only if it's a *persistence* shape (an R2DBC entity); SSE-only shapes don't need that.

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
- For events scoped to one user — consider a dedicated SSE endpoint (e.g. `/api/admin/actions/stream` is per-user) rather than emitting on the global stream and filtering client-side.

## See also

- [02 — Runtime architecture](../architecture/02-runtime.md#server-sent-events--eight-channels-on-one-stream)
- [Decision 003 — `autoCancel=false`](../decisions/003-autocancel-false.md)
- [Full-stack developer guide](../guides/fullstack-developer.md)
