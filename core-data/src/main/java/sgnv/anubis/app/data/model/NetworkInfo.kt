package sgnv.anubis.app.data.model

data class NetworkInfo(
    val ip: String,
    val country: String = "",
    val city: String = "",
    val org: String = "",
    val pingMs: Long = -1
)
