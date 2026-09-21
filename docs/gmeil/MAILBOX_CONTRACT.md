# Mailbox and ownership contract

`RegionMessage` and `WorldRegion.nestworldSendOrQueue` remain the canonical cross-region
transport. GMEIL adds policy/context metadata to that transport; it does not add a second
dispatcher. The existing `EntityMutationDispatcher` handles a closed set of entity operations,
not arbitrary mod requests.

The message envelope needs stable task/parent IDs, execution context, source/destination owner,
logical tick/phase, causal sequence, delivery mode, and request/reply ID where applicable. The
receiver must resolve ownership again immediately before apply. A stale destination can reroute
only within an explicit bounded, lossless contract; delivery must not be silently dropped,
duplicated, or marked successful before apply.

| Operation | Delivery |
| --- | --- |
| `DEFERRABLE_COMMAND` | Async owner mailbox, with explicit ordering and completion accounting |
| `SYNC_REQUEST` | Request/reply over owner mailbox; caller suspends through scheduler-aware wait model |
| `COMPOUND_COMMAND` | One logical operation with one declared transaction boundary; do not split blindly |
| Local owner operation | Direct fast path when policy and lease permit it |

`pendingFreeRunningWork` in `WorldRegion` is an `AtomicReference<List<Runnable>>` whose
`getAndSet` can overwrite an undrained batch. Its own comments restrict use to once-per-round
work. It is not a model for per-call lossless mod execution messages. Player action dispatchers
already use real `RegionMessage` queues because packets can arrive more than once per tick.

Split/merge must transfer pending messages and context without loss or duplicate apply. Preserve
the existing scheduled-tick rules for count, order, enqueue visibility, and owner revalidation.
Mailbox location, compatibility token, and execution lease are separate concepts.
