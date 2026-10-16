package com.andrerinas.openheadunit.geely.cluster

/**
 * The dependency gate of the cluster card (F013): our toggle does not replace the native
 * "AA now playing on media session" switch, it rides on top of it — the native switch stays
 * the master switch and the data source, ours only redirects the same stream into the
 * cluster. Nothing is published unless BOTH are on; with the native one off our toggle is
 * disabled in settings, so this state is only reachable mid-session when it flips.
 */
object ClusterMediaGate {

    fun isOpen(nativeSync: Boolean, ours: Boolean): Boolean = nativeSync && ours
}
