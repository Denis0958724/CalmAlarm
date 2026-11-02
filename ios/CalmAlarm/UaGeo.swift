import Foundation
import CoreGraphics

/// Межі областей, районів і міст з окремими тривогами (ua_geo.json, будує tools/geo/build_geo.py).
/// Координати вже спроєктовані: x = довгота·cos(48,5°), y = -широта, у кроках квантування.
/// Ключі збігаються з ідентифікаторами регіонів в API тривог.
struct GeoShape {
    let id: String
    let oblastId: String
    let name: String
    let center: CGPoint
    let path: CGPath
    let bounds: CGRect
}

final class UaGeoData {
    let oblasts: [String: GeoShape]
    let districts: [String: GeoShape]
    let cities: [String: GeoShape]
    let attribution: String
    let districtsByOblast: [String: [GeoShape]]

    init(oblasts: [String: GeoShape], districts: [String: GeoShape], cities: [String: GeoShape], attribution: String) {
        self.oblasts = oblasts
        self.districts = districts
        self.cities = cities
        self.attribution = attribution
        self.districtsByOblast = Dictionary(grouping: districts.values, by: { $0.oblastId })
    }

    /// Форма для будь-якого регіону з API тривог: область, район або місто.
    func shape(for regionId: String) -> GeoShape? {
        oblasts[regionId] ?? districts[regionId] ?? cities[regionId]
    }
}

enum UaGeo {
    static let shared: UaGeoData? = load()

    private static func load() -> UaGeoData? {
        guard let url = Bundle.main.url(forResource: "ua_geo", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        func parse(_ any: Any?, isOblast: Bool) -> [String: GeoShape] {
            var out: [String: GeoShape] = [:]
            guard let obj = any as? [String: Any] else { return out }
            for (id, v) in obj {
                guard let e = v as? [String: Any],
                      let c = e["c"] as? [NSNumber], c.count >= 2,
                      let rings = e["r"] as? [[NSNumber]]
                else { continue }
                let path = CGMutablePath()
                var minX = CGFloat.greatestFiniteMagnitude
                var minY = CGFloat.greatestFiniteMagnitude
                var maxX = -CGFloat.greatestFiniteMagnitude
                var maxY = -CGFloat.greatestFiniteMagnitude
                for r in rings {
                    var x: CGFloat = 0
                    var y: CGFloat = 0
                    var k = 0
                    while k + 1 < r.count {
                        if k == 0 {
                            x = CGFloat(r[0].intValue)
                            y = CGFloat(r[1].intValue)
                            path.move(to: CGPoint(x: x, y: y))
                        } else {
                            x += CGFloat(r[k].intValue)
                            y += CGFloat(r[k + 1].intValue)
                            path.addLine(to: CGPoint(x: x, y: y))
                        }
                        minX = min(minX, x)
                        minY = min(minY, y)
                        maxX = max(maxX, x)
                        maxY = max(maxY, y)
                        k += 2
                    }
                    path.closeSubpath()
                }
                let name = (e["n"] as? String) ?? (Regions.byId[id]?.short ?? "")
                out[id] = GeoShape(
                    id: id,
                    oblastId: isOblast ? id : ((e["o"] as? String) ?? ""),
                    name: name,
                    center: CGPoint(x: CGFloat(c[0].intValue), y: CGFloat(c[1].intValue)),
                    path: path,
                    bounds: CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
                )
            }
            return out
        }

        return UaGeoData(
            oblasts: parse(root["oblasts"], isOblast: true),
            districts: parse(root["districts"], isOblast: false),
            cities: parse(root["cities"], isOblast: false),
            attribution: (root["attribution"] as? String) ?? ""
        )
    }
}
