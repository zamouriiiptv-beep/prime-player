package com.castivio.data.entitlement

import com.castivio.core.common.AppDispatchers
import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.EntitlementStore
import com.castivio.domain.entitlement.StorageFault
import com.castivio.domain.entitlement.StoredEntitlement
import kotlinx.coroutines.withContext

/**
 * The entitlement record, sealed on disk.
 *
 * Remembers and nothing more: no expiry is computed here and no trial is granted here.
 * What it does insist on is telling the truth about a failure — "there is nothing
 * stored" and "there is something stored that will not open" leave by different doors,
 * because a device in the second state was licensed and a device in the first was not.
 */
internal class SealedEntitlementStore(
    private val store: SealedStore,
    private val dispatchers: AppDispatchers,
) : EntitlementStore {

    override suspend fun read(): StoredEntitlement = withContext(dispatchers.io) {
        when (val raw = store.read(SealedStore.KEY_ENTITLEMENT)) {
            is SealedRead.Absent -> StoredEntitlement.None

            // The blob would not open: edited, or sealed with a key that is gone.
            is SealedRead.Unsealable -> StoredEntitlement.Unreadable(StorageFault.UNSEALABLE)

            // It opened, so the key is right and nobody has edited it — what came out is
            // simply not a record this build understands. A different fault with a
            // different cause, and worth telling apart in a diagnostic.
            is SealedRead.Opened -> EntitlementCodec.decodeRecord(raw.bytes)
                ?.let(StoredEntitlement::Present)
                ?: StoredEntitlement.Unreadable(StorageFault.UNDECODABLE)
        }
    }

    override suspend fun write(record: EntitlementRecord) = withContext(dispatchers.io) {
        store.write(SealedStore.KEY_ENTITLEMENT, EntitlementCodec.encode(record))
    }

    override suspend fun clear() = withContext(dispatchers.io) {
        store.remove(SealedStore.KEY_ENTITLEMENT)
    }
}
