import AppIntents
import AlarmKit

/// Виконується, коли будильник вимикають (кнопкою, свайпом або бічною кнопкою).
/// Застосунок прокидається у фоні й повертає звичайний розклад, якщо будильник було перенесено.
struct CalmStopIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Вимкнути будильник"
    static let description = IntentDescription("Вимикає будильник CalmAlarm")
    static let isDiscoverable = false

    @Parameter(title: "alarmID")
    var alarmID: String

    init(alarmID: String) {
        self.alarmID = alarmID
    }

    init() {
        self.alarmID = ""
    }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmID) {
            try? AlarmManager.shared.stop(id: id)
        }
        await AppModel.shared.reconcile(reason: "stop")
        return .result()
    }
}
