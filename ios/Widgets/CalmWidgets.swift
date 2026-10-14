import WidgetKit
import SwiftUI
import ActivityKit
import AlarmKit

/// Розширення потрібне AlarmKit для показу відліку кнопки «Ще N хв» на екрані блокування.
@main
struct CalmWidgets: WidgetBundle {
    var body: some Widget {
        SnoozeActivity()
    }
}

struct SnoozeActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AlarmAttributes<CalmMeta>.self) { context in
            HStack(spacing: 14) {
                Image(systemName: "alarm.fill")
                    .font(.title2)
                    .foregroundStyle(Color(red: 1.0, green: 0.79, blue: 0.24))
                VStack(alignment: .leading, spacing: 2) {
                    Text("CalmAlarm").font(.headline)
                    Text(subtitle(context.state)).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                CountdownText(state: context.state)
                    .font(.title2.monospacedDigit().weight(.semibold))
            }
            .padding(16)
            .activityBackgroundTint(Color(red: 0.08, green: 0.15, blue: 0.31))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "alarm.fill").font(.title2)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    CountdownText(state: context.state).font(.title3.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(subtitle(context.state)).font(.headline)
                }
            } compactLeading: {
                Image(systemName: "zzz")
            } compactTrailing: {
                CountdownText(state: context.state)
                    .monospacedDigit()
                    .frame(width: 48)
            } minimal: {
                Image(systemName: "alarm.fill")
            }
        }
    }

    private func subtitle(_ state: AlarmPresentationState) -> String {
        switch state.mode {
        case .countdown:
            return "Ще трохи поспати…"
        case .paused:
            return "Пауза"
        case .alert:
            return "Будильник"
        @unknown default:
            return "Будильник"
        }
    }
}

struct CountdownText: View {
    let state: AlarmPresentationState

    var body: some View {
        switch state.mode {
        case .countdown(let info):
            Text(info.fireDate, style: .timer)
        case .paused:
            Text("--:--")
        case .alert(let info):
            Text(String(format: "%02d:%02d", info.time.hour, info.time.minute))
        @unknown default:
            Text("--:--")
        }
    }
}
