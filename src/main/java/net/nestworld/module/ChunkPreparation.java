package net.nestworld.module;

import java.util.concurrent.CompletionStage;

/** Core-owned preparation. Request/revoke calls require the owning server thread.
 * State may be read anywhere. A completed ready() is historical: ALWAYS recheck state()==READY
 * before attaching, since expiry/quiesce revokes even an already-ready lease. */
public interface ChunkPreparation extends ResourceHandle {
    enum State { PREPARING, READY, EXPIRED, CANCELLED, FAILED }
    ChunkPreparationRequest request();
    ModuleIdentity owner();
    State state();
    CompletionStage<Void> ready();
}
