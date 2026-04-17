package sgnv.anubis.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "managed_apps")
data class ManagedApp(
    @PrimaryKey val packageName: String,
    val group: AppGroup
)
