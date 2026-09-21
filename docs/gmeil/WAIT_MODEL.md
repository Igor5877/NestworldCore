# Synchronous wait model

Current player interaction dispatchers use `CountDownLatch`, bounded waits, and a claimed-CAS
fallback to prevent executing the same action both on main and on a region. This is useful
evidence, but it is not a generic scheduler-aware `SYNC_REQUEST` facility.

A GMEIL request has one immutable request ID, caller task/context, target owner, declared phase,
result type, deadline, and one terminal outcome: success, failure, cancellation, or timeout with
proven non-execution. A timeout after the receiver has claimed the request must not replay it on
main. Unknown opaque callbacks are not retry-safe by default.

The scheduler records a wait edge before suspension and removes it on every exit. It must detect
cycles (including A→B→A) before blocking and support bounded, observable failure. Suspending a
region task must release or hand off its compatibility token while preserving the owner lease
contract; a token may have at most one active executor in a causal chain. A Java global lock
around mod callbacks cannot provide this handoff and can deadlock.

`MAIN_COMPAT` request/reply is synchronous only if the main bridge can preserve the caller's
tick, phase, ordering, result, exception, and owner exclusion. Queuing with `server.execute()`
without those guarantees is not sufficient. Implement the bridge after Phase 1 observation,
then validate with synthetic reentrancy and cycle tests before using it for mod callbacks.
