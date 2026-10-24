# Bridge contract service (WP-06 seam)

`src/` contains the production-shaped contract boundary used by the MVP
tests: single-use pairing tickets, capability-filtered notification queries,
subscription-only events, Task-7-shaped operation idempotency, and the
zero-retention assistant gate.

The service implementations in this package are intentionally **in-memory**.
They open no sockets, do not dial Tailscale, and do not provide a durable
database, crash-safe ledger, or signed-ticket cryptographic verifier.
`restart()` methods and process-local stores are deterministic seams for
contract tests only.

`src/durable-store.ts` now defines the database- and network-neutral
`DurableBridgeStore` transaction port. It is a composition boundary for the
future Bridge runtime: a production adapter must expose the explicit durable
marker, make a successful transaction return only after an atomic
crash-recoverable commit, and supply migrations/health checks outside this
package. `runDurableBridgeTransaction` and `assertDurableBridgeStore` fail
closed when given a process-local store. `OperationDispatcherPort` is the
service-facing idempotency seam; the in-memory `OperationDispatcher` remains
the test fixture, while a runtime may inject a durable implementation. The
port itself does not make the current service durable, and no database
implementation is included here.

A real Bridge runtime must still provide authenticated ingress, signed ticket
verification, a concrete durable adapter wired around all accepted P0a stores,
and the locked deployment dependencies before this package can be used as a
production transport.

`src/persistence.ts` adds the explicit external SQLite adapter port. It closes
the persistence namespaces for pairing, authorization, notification cursors,
subscriptions, operation claims and assistant metadata, and carries atomic
transaction, migration, backup, restore and crash-recovery methods. No SQLite
implementation is included in this repository: a port with
`status: "external-driver-required"` is source-contract evidence only and
must not be reported as a production database.

The service does not accept model-supplied endpoints or routing identity. The
authenticated session and paired binding are supplied by the caller, while
notification package/field filters are closed and fail closed.

Notification egress must use `publishAuthorized`; the compatibility `publish`
method rejects calls without a current policy revision. `AssistantChatService`
requires a server-minted `boundSession` (and, for production, the
`PairingService`) so an injected authorizer cannot turn an arbitrary caller
session into an accepted command.
