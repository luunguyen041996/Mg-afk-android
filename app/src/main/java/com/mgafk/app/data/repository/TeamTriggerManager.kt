package com.mgafk.app.data.repository

import com.mgafk.app.data.AppLog
import com.mgafk.app.data.model.PetTeam
import com.mgafk.app.data.model.TeamTrigger
import com.mgafk.app.data.model.TeamTrigger.TriggerType
import com.mgafk.app.data.websocket.Constants

/**
 * Đánh giá triggers và tìm team cần kích hoạt dựa trên:
 * - Thời tiết hiện tại
 * - Độ đói của pets trong team đang active
 *
 * Priority: WEATHER > HUNGER > DEFAULT
 */
object TeamTriggerManager {

    private const val TAG = "TeamTriggerManager"

    /**
     * Tìm team phù hợp nhất dựa trên điều kiện hiện tại.
     *
     * @param teams         Danh sách tất cả pet teams
     * @param currentWeather Thời tiết hiện tại (display name, e.g. "Dawn", "Clear Skies")
     * @param activeTeamId  ID của team đang active (để kiểm tra hunger)
     * @param petHungerMap  Map petId → hunger% (0.0–100.0) của các pet đang active
     * @return Team cần switch sang, hoặc null nếu không cần đổi
     */
    fun evaluate(
        teams: List<PetTeam>,
        currentWeather: String,
        activeTeamId: String?,
        petHungerMap: Map<String, Double>,
    ): PetTeam? {
        // Chỉ xét team có triggers
        val triggeredTeams = teams.filter { it.triggers.isNotEmpty() }
        if (triggeredTeams.isEmpty()) return null

        val weatherLower = currentWeather.trim().lowercase()
        val currentActiveHungerMin = petHungerMap.values.minOrNull() ?: 100.0

        // Collect candidates với score
        data class Candidate(val team: PetTeam, val score: Int)
        val candidates = mutableListOf<Candidate>()

        for (team in triggeredTeams) {
            for (trigger in team.triggers.sortedByDescending { it.priority }) {
                when (trigger.type) {
                    TriggerType.WEATHER -> {
                        val matches = trigger.weathers.any { w ->
                            weatherLower.contains(w.lowercase()) ||
                                w.lowercase().contains(weatherLower)
                        }
                        if (matches) {
                            val score = 100 + trigger.priority
                            candidates.add(Candidate(team, score))
                            AppLog.d(TAG, "WEATHER match: ${team.name} (weather=$currentWeather)")
                        }
                    }

                    TriggerType.HUNGER -> {
                        // Chỉ check hunger của team đang active
                        if (team.id == activeTeamId) continue
                        if (currentActiveHungerMin <= trigger.hungerThresholdPercent) {
                            val score = 50 + trigger.priority
                            candidates.add(Candidate(team, score))
                            AppLog.d(TAG, "HUNGER match: ${team.name} (min hunger=$currentActiveHungerMin% <= ${trigger.hungerThresholdPercent}%)")
                        }
                    }

                    TriggerType.DEFAULT -> {
                        val score = 0 + trigger.priority
                        candidates.add(Candidate(team, score))
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null

        // Lấy candidate có score cao nhất
        val best = candidates.maxByOrNull { it.score } ?: return null

        // Nếu best team đã là active team → không cần switch
        if (best.team.id == activeTeamId) {
            AppLog.d(TAG, "Best team already active: ${best.team.name}")
            return null
        }

        AppLog.d(TAG, "Auto-switch to team: ${best.team.name} (score=${best.score})")
        return best.team
    }

    /** Tạo trigger thời tiết nhanh */
    fun weatherTrigger(vararg weathers: String, priority: Int = 0) = TeamTrigger(
        type = TriggerType.WEATHER,
        weathers = weathers.toList(),
        priority = priority,
    )

    /** Tạo trigger hunger nhanh */
    fun hungerTrigger(thresholdPercent: Int, priority: Int = 0) = TeamTrigger(
        type = TriggerType.HUNGER,
        hungerThresholdPercent = thresholdPercent,
        priority = priority,
    )

    /** Tạo trigger mặc định */
    fun defaultTrigger(priority: Int = 0) = TeamTrigger(
        type = TriggerType.DEFAULT,
        priority = priority,
    )
}
