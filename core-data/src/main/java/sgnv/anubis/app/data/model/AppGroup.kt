package sgnv.anubis.app.data.model

/**
 * Groups for managed apps:
 *
 * LOCAL — "No VPN" group. Frozen when VPN is ON.
 *         Only work on direct connection.
 *
 * VPN_ONLY — "VPN only" group. Frozen when VPN is OFF.
 *            Only work through VPN.
 *
 * LAUNCH_VPN — "With VPN" group. Never frozen, but launching one
 *              triggers: freeze LOCAL → start VPN → open app.
 */
enum class AppGroup {
    LOCAL,
    VPN_ONLY,
    LAUNCH_VPN
}
