import UserNotifications
import AlarmKit
import ActivityKit
import SwiftUI

/// Проба: чи доступний AlarmKit у розширенні сповіщень.
final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        let manager = AlarmManager.shared
        _ = manager.authorizationState
        _ = try? manager.alarms
        try? manager.cancel(id: UUID())
        let alert = AlarmPresentation.Alert(title: "Проба", stopButton: AlarmButton(text: "Стоп", textColor: .white, systemImageName: "stop.fill"))
        let attrs = AlarmAttributes<CalmMeta>(presentation: AlarmPresentation(alert: alert), metadata: CalmMeta(), tintColor: .blue)
        let config = AlarmManager.AlarmConfiguration<CalmMeta>.alarm(schedule: .fixed(Date().addingTimeInterval(60)), attributes: attrs, sound: .default)
        Task {
            _ = try? await manager.schedule(id: UUID(), configuration: config)
            contentHandler(request.content)
        }
    }
}
