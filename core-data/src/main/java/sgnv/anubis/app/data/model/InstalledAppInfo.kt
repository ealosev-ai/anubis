package sgnv.anubis.app.data.model

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val group: AppGroup? = null,
    val isDisabled: Boolean = false
)
