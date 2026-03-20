package com.spectalk.app.device

import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MetaWearablesPermissionBridge {
    private val requestMutex = Mutex()

    @Volatile
    private var requestHandler: (suspend (Permission) -> PermissionStatus)? = null

    fun install(handler: suspend (Permission) -> PermissionStatus) {
        requestHandler = handler
    }

    fun clear() {
        requestHandler = null
    }

    suspend fun requestPermission(permission: Permission): PermissionStatus {
        val handler = requestHandler ?: return PermissionStatus.Denied
        return requestMutex.withLock { handler(permission) }
    }
}
