import AlarmKit

/// Дані, які AlarmKit зберігає разом із будильником (спільні для застосунку і віджета).
struct CalmMeta: AlarmMetadata {
    var kind: Int = 0
}
