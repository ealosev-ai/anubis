package sgnv.anubis.app.data.repository

import sgnv.anubis.app.data.model.AppGroup

/**
 * Read-only контракт для оркестратора: «какие пакеты в этой группе».
 * Существует отдельно от [GroupsStore] (который про backup/restore),
 * чтобы StealthOrchestrator не видел `setAppGroup`/`removeApp` — ему их
 * никогда не надо вызывать, случайное использование ломало бы state-машину.
 */
interface PackageGroupsReader {
    suspend fun getPackagesByGroup(group: AppGroup): Set<String>
}
