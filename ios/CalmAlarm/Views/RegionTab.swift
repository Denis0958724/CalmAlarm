import SwiftUI

struct RegionTab: View {
    @EnvironmentObject private var model: AppModel
    var onSaved: () -> Void

    /// ["pick"] - вибір області; ["pick", id] - вибір району цієї області.
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ScreenHeader(title: "Регіон", subtitle: "Будильник зважає на тривогу саме тут")
                    if let r = model.region {
                        HStack(spacing: 14) {
                            Image(systemName: "mappin.circle.fill")
                                .font(.system(size: 34))
                                .foregroundStyle(Palette.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.oblastName).font(.headline)
                                Text(r.subtitle).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        .padding(18)
                        .card()
                        if r.wholeOblast && (Regions.byId[r.oblastId]?.hasDistricts ?? false) {
                            Text("Зараз будильник чекає, якщо тривога хоча б в одному районі області. Виберіть свій район або місто - тоді він зважатиме лише на тривогу саме у вас.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 4)
                        }
                    } else {
                        Text("Виберіть область, а потім свій район або місто. Будильник перевірятиме тривогу саме там.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Button {
                        path = ["pick"]
                    } label: {
                        Text(model.region == nil ? "Вибрати регіон" : "Змінити регіон")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 4)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Palette.background)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { key in
                if key == "pick" {
                    OblastPicker(selectedId: model.region?.oblastId) { ob in
                        if ob.hasDistricts {
                            path.append(ob.id)
                        } else {
                            save(Region(oblastId: ob.id, oblastName: ob.name))
                        }
                    }
                } else if let ob = Regions.byId[key] {
                    DistrictPicker(oblast: ob, selected: model.region) { r in save(r) }
                }
            }
        }
    }

    private func save(_ r: Region) {
        model.setRegion(r)
        model.toast = "Регіон збережено: \(r.label)"
        path = []
        onSaved()
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if model.toast == "Регіон збережено: \(r.label)" { model.toast = nil }
        }
    }
}

private struct OblastPicker: View {
    let selectedId: String?
    var onPick: (Oblast) -> Void

    var body: some View {
        List(Regions.sorted) { ob in
            Button {
                onPick(ob)
            } label: {
                HStack {
                    Text(ob.name).foregroundStyle(Color.primary)
                    Spacer()
                    if ob.id == selectedId {
                        Image(systemName: "checkmark").foregroundStyle(Palette.accent)
                    } else if ob.hasDistricts {
                        Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                    }
                }
            }
        }
        .navigationTitle("Виберіть область")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DistrictPicker: View {
    let oblast: Oblast
    let selected: Region?
    var onPick: (Region) -> Void

    @State private var districts: [District]?
    @State private var failed = false
    @State private var attempt = 0

    var body: some View {
        List {
            Section {
                row("Вся область", "Будильник чекатиме, якщо тривога хоча б в одному районі області",
                    on: selected?.oblastId == oblast.id && selected?.wholeOblast == true) {
                    onPick(Region(oblastId: oblast.id, oblastName: oblast.name))
                }
            }
            if let list = districts {
                Section("Райони") {
                    ForEach(list) { d in
                        row(d.name, nil, on: selected?.districtId == d.id && selected?.cityId == nil) {
                            onPick(Region(oblastId: oblast.id, oblastName: oblast.name, districtId: d.id, districtName: d.name))
                        }
                        ForEach(d.cities) { c in
                            row(c.short, "Місто з окремими тривогами · \(d.name)", on: selected?.cityId == c.id) {
                                onPick(Region(
                                    oblastId: oblast.id, oblastName: oblast.name,
                                    districtId: d.id, districtName: d.name,
                                    cityId: c.id, cityName: c.short
                                ))
                            }
                        }
                    }
                }
            } else if failed {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Не вдалося завантажити список районів. Перевірте інтернет.")
                        Button("Спробувати ще") { attempt += 1 }.buttonStyle(.bordered)
                    }
                    .padding(.vertical, 6)
                }
            } else {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                    .padding(.vertical, 20)
                }
            }
        }
        .navigationTitle(oblast.short)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: attempt) {
            failed = false
            if let all = try? await Regions.loadDistricts() {
                districts = all[oblast.id] ?? []
            } else {
                failed = true
            }
        }
    }

    private func row(_ title: String, _ sub: String?, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(Color.primary)
                    if let s = sub {
                        Text(s).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if on {
                    Image(systemName: "checkmark").foregroundStyle(Palette.accent)
                }
            }
        }
    }
}
