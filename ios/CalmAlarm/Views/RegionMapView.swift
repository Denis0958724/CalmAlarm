import SwiftUI

/// Кольори карти: вона завжди «нічна», як радар, і в світлій, і в темній темі.
enum MapColors {
    static let bgTop = Color(hex: 0x0B1430)
    static let bgBottom = Color(hex: 0x060A18)
    static let focus = Color(hex: 0x8FB0FF)
    static let drone = Color(hex: 0xFF9F43)
    static let missile = Color(hex: 0xFF4D5E)
}

/// Що саме показуємо: центр і розмір видимої частини карти в її координатах.
struct MapFrame {
    let focus: GeoShape
    let center: CGPoint
    let spanX: CGFloat
    let spanY: CGFloat
}

func mapFrame(_ geo: UaGeoData, _ region: Region) -> MapFrame? {
    let focus = region.cityId.flatMap { geo.cities[$0] }
        ?? region.districtId.flatMap { geo.districts[$0] }
        ?? geo.oblasts[region.oblastId]
    guard let f = focus else { return nil }
    let local = region.cityId != nil || region.districtId != nil || region.oblastId == "31"
    let factor: CGFloat = local ? 2.6 : 1.6
    let minSpan: CGFloat = local ? 110 : 160 // ~50 і ~70 км
    return MapFrame(
        focus: f,
        center: CGPoint(x: f.bounds.midX, y: f.bounds.midY),
        spanX: max(f.bounds.width * factor, minSpan),
        spanY: max(f.bounds.height * factor, minSpan)
    )
}

/// Скільки загроз видно навколо вибраного місця: (шахеди, ракети).
func threatsNear(_ region: Region, _ threats: [Threat]) -> (Int, Int) {
    guard let geo = UaGeo.shared, let fr = mapFrame(geo, region) else { return (0, 0) }
    let rect = CGRect(x: fr.center.x - fr.spanX / 2, y: fr.center.y - fr.spanY / 2, width: fr.spanX, height: fr.spanY)
    var drones = 0
    var missiles = 0
    for t in threats {
        guard let s = t.districtId.flatMap({ geo.districts[$0] }) ?? geo.oblasts[t.oblastId] else { continue }
        if rect.contains(s.center) {
            if t.type == .drone { drones += 1 } else { missiles += 1 }
        }
    }
    return (drones, missiles)
}

/// Наближена карта вибраного місця: район (або область) чітко в центрі, сусіди навколо,
/// а до країв карта розмивається. Тривоги - з офіційної карти, загрози - з повідомлень Повітряних сил.
struct RegionMapView: View {
    let region: Region
    var alerts: [String: Level] = [:]
    var threats: [Threat] = []
    var aspect: CGFloat = 1.2

    var body: some View {
        Color.clear
            .aspectRatio(aspect, contentMode: .fit)
            .overlay {
                GeometryReader { g in
                    if let geo = UaGeo.shared, let fr = mapFrame(geo, region) {
                        let r = max(g.size.width, g.size.height)
                        ZStack {
                            LinearGradient(colors: [MapColors.bgTop, MapColors.bgBottom], startPoint: .top, endPoint: .bottom)
                            Canvas { ctx, size in
                                MapPainter.drawMap(ctx, size, geo, fr, region, alerts, sharp: false)
                            }
                            .blur(radius: 7)
                            Canvas { ctx, size in
                                MapPainter.drawMap(ctx, size, geo, fr, region, alerts, sharp: true)
                            }
                            .mask {
                                RadialGradient(
                                    stops: [
                                        .init(color: .black, location: 0),
                                        .init(color: .black, location: 0.42),
                                        .init(color: .clear, location: 1),
                                    ],
                                    center: .center, startRadius: 0, endRadius: r * 0.62
                                )
                            }
                            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                                let phase = tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.8) / 1.8
                                Canvas { ctx, size in
                                    MapPainter.drawLabels(ctx, size, geo, fr, region)
                                    MapPainter.drawThreats(ctx, size, geo, fr, threats, phase: phase)
                                }
                            }
                            RadialGradient(
                                stops: [
                                    .init(color: .clear, location: 0.72),
                                    .init(color: MapColors.bgBottom.opacity(0.85), location: 1),
                                ],
                                center: .center, startRadius: 0, endRadius: r * 0.72
                            )
                            .allowsHitTesting(false)
                        }
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

enum MapPainter {
    static func scale(_ size: CGSize, _ fr: MapFrame) -> CGFloat {
        min(size.width / fr.spanX, size.height / fr.spanY)
    }

    static func toScreen(_ p: CGPoint, _ size: CGSize, _ fr: MapFrame) -> CGPoint {
        let s = scale(size, fr)
        return CGPoint(x: size.width / 2 + (p.x - fr.center.x) * s, y: size.height / 2 + (p.y - fr.center.y) * s)
    }

    static func visible(_ size: CGSize, _ fr: MapFrame) -> CGRect {
        let s = scale(size, fr)
        let hw = size.width / 2 / s
        let hh = size.height / 2 / s
        return CGRect(x: fr.center.x - hw, y: fr.center.y - hh, width: hw * 2, height: hh * 2)
    }

    static func drawMap(
        _ context: GraphicsContext,
        _ size: CGSize,
        _ geo: UaGeoData,
        _ fr: MapFrame,
        _ region: Region,
        _ alerts: [String: Level],
        sharp: Bool
    ) {
        var c = context
        let s = scale(size, fr)
        let px = 1 / s
        let vis = visible(size, fr).insetBy(dx: -20, dy: -20)
        c.translateBy(x: size.width / 2, y: size.height / 2)
        c.scaleBy(x: s, y: s)
        c.translateBy(x: -fr.center.x, y: -fr.center.y)

        let oblasts = geo.oblasts.values.filter { $0.bounds.intersects(vis) }
        let districts = geo.districts.values.filter { $0.bounds.intersects(vis) }
        let round = StrokeStyle(lineWidth: px, lineCap: .round, lineJoin: .round)
        func stroke(_ w: CGFloat) -> StrokeStyle { StrokeStyle(lineWidth: w * px, lineCap: .round, lineJoin: .round) }

        for o in oblasts { c.fill(Path(o.path), with: .color(.white.opacity(0.035))) }

        for (rid, level) in alerts {
            guard let shape = geo.shape(for: rid), shape.bounds.intersects(vis) else { continue }
            let col = levelColor(level)
            let isOblast = geo.oblasts[rid] != nil
            c.fill(Path(shape.path), with: .color(col.opacity(isOblast ? 0.16 : 0.30)))
            if !isOblast { c.stroke(Path(shape.path), with: .color(col.opacity(0.75)), style: stroke(1.3)) }
        }

        let f = fr.focus
        c.fill(
            Path(f.path),
            with: .radialGradient(
                Gradient(colors: [MapColors.focus.opacity(0.34), MapColors.focus.opacity(0.12)]),
                center: f.center, startRadius: 0, endRadius: max(f.bounds.width, f.bounds.height) * 0.7
            )
        )

        for d in districts { c.stroke(Path(d.path), with: .color(.white.opacity(0.22)), style: round) }
        for o in oblasts { c.stroke(Path(o.path), with: .color(.white.opacity(0.55)), style: stroke(1.7)) }

        if sharp {
            c.stroke(Path(f.path), with: .color(MapColors.focus.opacity(0.22)), style: stroke(10))
            c.stroke(Path(f.path), with: .color(MapColors.focus.opacity(0.35)), style: stroke(5))
        }
        c.stroke(Path(f.path), with: .color(MapColors.focus), style: stroke(2.6))
        if f.id != region.oblastId, let ob = geo.oblasts[region.oblastId] {
            c.stroke(Path(ob.path), with: .color(MapColors.focus.opacity(0.55)), style: stroke(1.6))
        }
    }

    static func shortName(_ name: String) -> String {
        var n = name
        for suffix in [" район", " область"] where n.hasSuffix(suffix) { n = String(n.dropLast(suffix.count)) }
        if n.hasPrefix("м. ") { n = String(n.dropFirst(3)) }
        return n
    }

    static func drawLabels(_ context: GraphicsContext, _ size: CGSize, _ geo: UaGeoData, _ fr: MapFrame, _ region: Region) {
        let vis = visible(size, fr)
        let radius = max(size.width, size.height) * 0.5
        var placed: [CGPoint] = []

        func label(_ text: String, _ at: CGPoint, big: Bool, alpha: Double) {
            guard alpha > 0.05, !text.isEmpty else { return }
            if placed.contains(where: { hypot($0.x - at.x, $0.y - at.y) < 40 }) { return }
            if at.x < 30 || at.y < 12 || at.x > size.width - 30 || at.y > size.height - 12 { return }
            var c = context
            c.opacity = alpha
            c.draw(
                Text(text).font(.system(size: big ? 13 : 10, weight: big ? .bold : .medium)).foregroundColor(.white),
                at: at
            )
            placed.append(at)
        }

        let f = fr.focus
        let focusName = f.name.isEmpty ? (Regions.byId[region.oblastId]?.short ?? "") : f.name
        label(shortName(focusName), toScreen(f.center, size, fr), big: true, alpha: 1)

        let local = f.id != region.oblastId || region.oblastId == "31"
        let pool = local ? Array(geo.districts.values) : Array(geo.oblasts.values)
        let others = pool
            .filter { $0.id != f.id && vis.contains($0.center) }
            .sorted { hypot($0.center.x - f.center.x, $0.center.y - f.center.y) < hypot($1.center.x - f.center.x, $1.center.y - f.center.y) }
            .prefix(10)
        for o in others {
            let at = toScreen(o.center, size, fr)
            let d = hypot(at.x - size.width / 2, at.y - size.height / 2)
            let a = min(0.6, max(0, 1 - (d - radius * 0.35) / (radius * 0.6)))
            label(shortName(o.name), at, big: false, alpha: Double(a))
        }
    }

    /// Силует шахеда (дельтаподібне крило) в одиничних координатах.
    static let dronePath: Path = {
        var p = Path()
        p.move(to: CGPoint(x: 0, y: -1))
        p.addLine(to: CGPoint(x: 0.95, y: 0.55))
        p.addLine(to: CGPoint(x: 0.32, y: 0.42))
        p.addLine(to: CGPoint(x: 0.14, y: 0.82))
        p.addLine(to: CGPoint(x: -0.14, y: 0.82))
        p.addLine(to: CGPoint(x: -0.32, y: 0.42))
        p.addLine(to: CGPoint(x: -0.95, y: 0.55))
        p.closeSubpath()
        return p
    }()

    /// Силует ракети в одиничних координатах.
    static let missilePath: Path = {
        var p = Path()
        let pts: [(CGFloat, CGFloat)] = [
            (0, -1), (0.17, -0.62), (0.17, 0.52), (0.46, 0.92), (0.17, 0.82), (0.12, 1),
            (-0.12, 1), (-0.17, 0.82), (-0.46, 0.92), (-0.17, 0.52), (-0.17, -0.62),
        ]
        p.move(to: CGPoint(x: pts[0].0, y: pts[0].1))
        for q in pts.dropFirst() { p.addLine(to: CGPoint(x: q.0, y: q.1)) }
        p.closeSubpath()
        return p
    }()

    static func icon(_ type: ThreatType, at: CGPoint, size: CGFloat) -> Path {
        let base = type == .drone ? dronePath : missilePath
        var t = CGAffineTransform(translationX: at.x, y: at.y)
        if type == .missile { t = t.rotated(by: .pi * 35 / 180) }
        t = t.scaledBy(x: size / 2, y: size / 2)
        return base.applying(t)
    }

    static func drawThreats(_ context: GraphicsContext, _ size: CGSize, _ geo: UaGeoData, _ fr: MapFrame, _ threats: [Threat], phase: Double) {
        guard !threats.isEmpty else { return }
        let radius = max(size.width, size.height) * 0.5
        var groups: [String: [Threat]] = [:]
        for t in threats {
            let key = t.districtId.flatMap { geo.districts[$0] != nil ? $0 : nil } ?? t.oblastId
            groups[key, default: []].append(t)
        }
        for (place, list) in groups {
            guard let shape = geo.shape(for: place) else { continue }
            let base = toScreen(shape.center, size, fr)
            if base.x < -20 || base.y < -20 || base.x > size.width + 20 || base.y > size.height + 20 { continue }
            let d = hypot(base.x - size.width / 2, base.y - size.height / 2)
            let alpha = Double(min(1, max(0.25, 1 - (d - radius * 0.55) / (radius * 0.55))))
            var types: [ThreatType] = []
            for t in list where !types.contains(t.type) { types.append(t.type) }
            let r: CGFloat = 15
            for (i, type) in types.enumerated() {
                let at = CGPoint(x: base.x + (CGFloat(i) - CGFloat(types.count - 1) / 2) * r * 1.6, y: base.y - 14)
                let col = type == .drone ? MapColors.drone : MapColors.missile
                let p = (phase + Double(i) * 0.3).truncatingRemainder(dividingBy: 1)
                let pr = r * (0.8 + CGFloat(p) * 1.3)
                context.fill(Path(ellipseIn: CGRect(x: at.x - pr, y: at.y - pr, width: pr * 2, height: pr * 2)),
                             with: .color(col.opacity((1 - p) * 0.45 * alpha)))
                let cr = r * 0.85
                let circle = Path(ellipseIn: CGRect(x: at.x - cr, y: at.y - cr, width: cr * 2, height: cr * 2))
                context.fill(circle, with: .color(Color(hex: 0x0A0F22).opacity(0.78 * alpha)))
                context.stroke(circle, with: .color(col.opacity(alpha)), lineWidth: 1.5)
                context.fill(icon(type, at: at, size: r * 1.1), with: .color(col.opacity(alpha)))
            }
        }
    }
}

/// Позначки під картою.
struct MapLegend: View {
    var body: some View {
        HStack(spacing: 12) {
            swatch(MapColors.focus, "Ви тут")
            swatch(Palette.red, "Тривога")
            swatch(Palette.yellow, "Жовта")
            iconItem(.drone, "Шахеди")
            iconItem(.missile, "Ракети")
        }
        .font(.caption.weight(.medium))
        .lineLimit(1)
        .minimumScaleFactor(0.8)
    }

    private func swatch(_ c: Color, _ t: String) -> some View {
        HStack(spacing: 5) {
            RoundedRectangle(cornerRadius: 3).fill(c.opacity(0.85)).frame(width: 12, height: 12)
            Text(t)
        }
    }

    private func iconItem(_ type: ThreatType, _ t: String) -> some View {
        HStack(spacing: 5) {
            Canvas { ctx, size in
                ctx.fill(MapPainter.icon(type, at: CGPoint(x: size.width / 2, y: size.height / 2), size: size.width * 0.9),
                         with: .color(type == .drone ? MapColors.drone : MapColors.missile))
            }
            .frame(width: 14, height: 14)
            Text(t)
        }
    }
}
