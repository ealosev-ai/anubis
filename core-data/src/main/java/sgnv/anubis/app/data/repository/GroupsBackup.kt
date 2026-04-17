package sgnv.anubis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.ManagedApp

/**
 * Бекап/рестор списков managed_apps в JSON.
 *
 * Формат:
 * {
 *   "version": 1,
 *   "exportedAtMs": 1700000000000,
 *   "apps": [ { "packageName": "ru.rshb.dbo", "group": "LOCAL" }, ... ]
 * }
 *
 * Rest import — **merge**: существующие записи перезаписываются group'ой из
 * бекапа, отсутствующие добавляются. Приложения которые были в БД, но
 * отсутствуют в бекапе — не трогаются (это не wipe-and-restore).
 */
object GroupsBackup {

    private const val VERSION = 1

    suspend fun export(repo: GroupsStore): String {
        val all = AppGroup.entries.flatMap { g ->
            repo.getAppsByGroup(g).map { g to it.packageName }
        }
        val arr = JSONArray()
        for ((group, pkg) in all) {
            arr.put(
                JSONObject().apply {
                    put("packageName", pkg)
                    put("group", group.name)
                }
            )
        }
        return JSONObject().apply {
            put("version", VERSION)
            put("exportedAtMs", System.currentTimeMillis())
            put("count", arr.length())
            put("apps", arr)
        }.toString(2)
    }

    /**
     * @return количество фактически импортированных (merged) записей, либо -1 если
     *         формат JSON невалиден.
     */
    suspend fun import(repo: GroupsStore, json: String): Int {
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("apps") ?: return -1
            var imported = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("packageName").takeIf { it.isNotBlank() } ?: continue
                val groupName = obj.optString("group").takeIf { it.isNotBlank() } ?: continue
                val group = try { AppGroup.valueOf(groupName) } catch (_: Exception) { continue }
                repo.setAppGroup(pkg, group)
                imported++
            }
            imported
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Альтернатива import — сначала очищает все managed_apps, потом применяет бекап.
     * Полезно при полном переезде с другого устройства.
     */
    suspend fun replaceAll(repo: GroupsStore, json: String): Int {
        val snapshot = try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("apps") ?: return -1
            val items = mutableListOf<Pair<String, AppGroup>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("packageName").takeIf { it.isNotBlank() } ?: continue
                val groupName = obj.optString("group").takeIf { it.isNotBlank() } ?: continue
                val group = try { AppGroup.valueOf(groupName) } catch (_: Exception) { continue }
                items += pkg to group
            }
            items
        } catch (_: Exception) {
            return -1
        }
        // Чистим все существующие group-записи.
        for (g in AppGroup.entries) {
            for (app in repo.getAppsByGroup(g)) {
                repo.removeApp(app.packageName)
            }
        }
        for ((pkg, group) in snapshot) {
            repo.setAppGroup(pkg, group)
        }
        return snapshot.size
    }
}
