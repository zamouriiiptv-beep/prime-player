package com.castivio.data.entitlement

import com.castivio.core.common.AppDispatchers
import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.EntitlementStore
import kotlinx.coroutines.withContext

/**
 * The entitlement record, sealed on disk.
 *
 * Remembers and nothing more: no expiry is computed here, no trial is granted here, and
 * a record that will not decode comes back as null rather than as a guess. Null is a
 * state the app already handles — it is a device with no entitlement, which goes to the
 * licence screen and, with a licence server bound, is one round trip from being right
 * again.
 */
internal class SealedEntitlementStore(
    private val store: SealedStore,
    private val dispatchers: AppDispatchers,
) : EntitlementStore {

    override suspend fun read(): EntitlementRecord? = withContext(dispatchers.io) {
        store.read(SealedStore.KEY_ENTITLEMENT)?.let(EntitlementCodec::decodeRecord)
    }

    override suspend fun write(record: EntitlementRecord) = withContext(dispatchers.io) {
        store.write(SealedStore.KEY_ENTITLEMENT, EntitlementCodec.encode(record))
    }

    override suspend fun clear() = withContext(dispatchers.io) {
        store.remove(SealedStore.KEY_ENTITLEMENT)
    }
}
