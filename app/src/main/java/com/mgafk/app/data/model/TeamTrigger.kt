package com.mgafk.app.data.model

import kotlinx.serialization.Serializable

/**
 * Điều kiện để tự động kích hoạt một Pet Team.
 * Mỗi team có thể có nhiều trigger — trigger nào khớp đầu tiên (theo priority) sẽ được dùng.
 */
@Serializable
data class TeamTrigger(
    val id: String = java.util.UUID.randomUUID().toString(),

    /** Loại trigger */
    val type: TriggerType = TriggerType.WEATHER,

    /** Dành cho WEATHER: danh sách weather names kích hoạt team này */
    val weathers: List<String> = emptyList(),

    /** Dành cho HUNGER: ngưỡng % đói tối thiểu (0-100) của bất kỳ pet nào trong team hiện tại */
    val hungerThresholdPercent: Int = 30,

    /** Độ ưu tiên — trigger có priority cao hơn được check trước */
    val priority: Int = 0,
) {
    enum class TriggerType {
        /** Kích hoạt khi thời tiết khớp với danh sách weathers */
        WEATHER,

        /** Kích hoạt khi pet trong team hiện tại có hunger <= threshold */
        HUNGER,

        /** Team mặc định — kích hoạt khi không có trigger nào khác khớp */
        DEFAULT,
    }
}
