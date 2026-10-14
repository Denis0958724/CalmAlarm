import Foundation

enum Net {
    static let appUA = "CalmAlarm/1.0 (iOS)"
    static let browserUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    static func get(_ url: String, timeout: TimeInterval = 8, userAgent: String = appUA) async throws -> String {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return String(decoding: data, as: UTF8.self)
    }

    static func postJSON(_ url: URL, body: Data, timeout: TimeInterval = 10) async throws {
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(appUA, forHTTPHeaderField: "User-Agent")
        let (_, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
    }
}

/// Розбір дат у форматі ISO 8601 (з часовою зоною або без неї - тоді UTC).
enum ISO {
    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func parse(_ raw: String?) -> Date? {
        guard var s = raw?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        // Мікросекунди («.075992») скорочуємо до мілісекунд - так їх точно розбере форматер.
        s = s.replacingOccurrences(of: "(\\.\\d{3})\\d+", with: "$1", options: .regularExpression)
        if let d = plain.date(from: s) ?? fractional.date(from: s) { return d }
        // Без часової зони: вважаємо UTC.
        if !s.hasSuffix("Z") && !s.contains("+") {
            s += "Z"
            if let d = plain.date(from: s) ?? fractional.date(from: s) { return d }
        }
        return nil
    }
}
