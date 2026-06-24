// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

import Foundation

// Minimal Nextcloud WebDAV client. URLSession only — no third-party deps,
// matches the rest of the suite. We use HTTP Basic with the user's Nextcloud
// APP PASSWORD (not their login). NSURLSession honours System Trust by
// default, so a self-signed cert raises an error — we never override the
// challenge handler to "accept anything" and you should never do that here
// either; the user fixes their cert server-side.

public struct DavEntry: Hashable, Sendable {
    public let href: String        // URL-decoded server path
    public let name: String
    public let isDirectory: Bool
    public let size: Int64?
    public let modifiedAt: Date?
}

public enum WebDavError: LocalizedError {
    case notConfigured, http(Int, String?), transport(String), decoding(String)
    public var errorDescription: String? {
        switch self {
        case .notConfigured:        return "Nextcloud credentials not set."
        case .http(let s, _):       return "Server returned HTTP \(s)."
        case .transport(let m):     return "Network error: \(m)."
        case .decoding(let m):      return "Couldn't parse response: \(m)."
        }
    }
}

public actor WebDavClient {
    let creds: WebDavCreds
    private let session: URLSession
    public init(_ c: WebDavCreds, session: URLSession = .shared) {
        self.creds = c; self.session = session
    }

    private func userRoot(_ path: String = "") -> URL? {
        let base = creds.baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        let cleaned = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var s = "\(base)/remote.php/dav/files/\(creds.username)"
        if !cleaned.isEmpty { s += "/\(cleaned)" }
        return URL(string: s)
    }

    private func basicAuth() -> String {
        let token = Data("\(creds.username):\(creds.appPassword)".utf8).base64EncodedString()
        return "Basic \(token)"
    }

    public func testConnection() async throws -> Int {
        try await list("").count
    }

    public func list(_ path: String) async throws -> [DavEntry] {
        guard let url = userRoot(path) else { throw WebDavError.notConfigured }
        var req = URLRequest(url: url)
        req.httpMethod = "PROPFIND"
        req.setValue(basicAuth(), forHTTPHeaderField: "Authorization")
        req.setValue("1", forHTTPHeaderField: "Depth")
        req.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = """
        <?xml version="1.0"?>
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:displayname/>
            <d:getcontentlength/>
            <d:getlastmodified/>
            <d:resourcetype/>
          </d:prop>
        </d:propfind>
        """.data(using: .utf8)

        let (data, resp) = try await send(req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 207 || code == 200 else {
            throw WebDavError.http(code, String(data: data, encoding: .utf8))
        }
        return try parseMultiStatus(data, selfPath: url.path)
    }

    public func get(_ path: String) async throws -> Data {
        guard let url = userRoot(path) else { throw WebDavError.notConfigured }
        var req = URLRequest(url: url)
        req.setValue(basicAuth(), forHTTPHeaderField: "Authorization")
        let (data, resp) = try await send(req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw WebDavError.http(code, String(data: data, encoding: .utf8)) }
        return data
    }

    public func put(directory dir: String, file name: String, data: Data,
                    contentType: String = "application/octet-stream") async throws {
        let cleaned = dir.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !cleaned.isEmpty {
            let parts = cleaned.split(separator: "/")
            var acc = ""
            for p in parts {
                acc = acc.isEmpty ? String(p) : "\(acc)/\(p)"
                try await mkcol(acc)
            }
        }
        let dest = cleaned.isEmpty ? name : "\(cleaned)/\(name)"
        guard let url = userRoot(dest) else { throw WebDavError.notConfigured }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue(basicAuth(), forHTTPHeaderField: "Authorization")
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        req.httpBody = data
        let (body, resp) = try await send(req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if !(200..<300).contains(code) {
            throw WebDavError.http(code, String(data: body, encoding: .utf8))
        }
    }

    private func mkcol(_ path: String) async throws {
        guard let url = userRoot(path) else { throw WebDavError.notConfigured }
        var req = URLRequest(url: url)
        req.httpMethod = "MKCOL"
        req.setValue(basicAuth(), forHTTPHeaderField: "Authorization")
        let (body, resp) = try await send(req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 405 || code == 409 { return }   // already exists
        if !(200..<300).contains(code) {
            throw WebDavError.http(code, String(data: body, encoding: .utf8))
        }
    }

    private func send(_ req: URLRequest) async throws -> (Data, URLResponse) {
        do { return try await session.data(for: req) }
        catch { throw WebDavError.transport(error.localizedDescription) }
    }

    // MARK: - PROPFIND parsing
    private func parseMultiStatus(_ data: Data, selfPath: String) throws -> [DavEntry] {
        let parser = DavParser(selfPath: selfPath)
        let x = XMLParser(data: data)
        x.delegate = parser
        x.shouldProcessNamespaces = true
        guard x.parse() else {
            throw WebDavError.decoding(x.parserError?.localizedDescription ?? "?")
        }
        return parser.entries.sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
            return $0.name.lowercased() < $1.name.lowercased()
        }
    }
}

// XMLParser delegate is reference-only, so we keep it out of the actor.
private final class DavParser: NSObject, XMLParserDelegate {
    private let httpDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f
    }()

    let selfPath: String
    var entries: [DavEntry] = []
    private var inResponse = false
    private var current = ""
    private var href: String?
    private var displayname: String?
    private var size: Int64?
    private var lastModified: String?
    private var isDir = false
    init(selfPath: String) { self.selfPath = selfPath }

    func parser(_ parser: XMLParser, didStartElement name: String,
                namespaceURI: String?, qualifiedName qName: String?, attributes: [String:String] = [:]) {
        switch name.lowercased() {
        case "response":
            inResponse = true
            href = nil; displayname = nil; size = nil; lastModified = nil; isDir = false
        case "collection":
            if inResponse { isDir = true }
        default: break
        }
        current = ""
    }
    func parser(_ parser: XMLParser, foundCharacters text: String) {
        current += text
    }
    func parser(_ parser: XMLParser, didEndElement name: String,
                namespaceURI: String?, qualifiedName qName: String?) {
        let trimmed = current.trimmingCharacters(in: .whitespacesAndNewlines)
        switch name.lowercased() {
        case "href":               if inResponse { href = trimmed }
        case "displayname":        if inResponse { displayname = trimmed }
        case "getcontentlength":   if inResponse { size = Int64(trimmed) }
        case "getlastmodified":    if inResponse { lastModified = trimmed }
        case "response":
            guard let raw = href else { inResponse = false; return }
            let decoded = raw.removingPercentEncoding ?? raw
            let pathOnly = URL(string: raw)?.path ?? raw
            let self0 = selfPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let cand0 = pathOnly.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if cand0 != self0 {
                let trimmedTail = decoded.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                let displayName = (displayname?.isEmpty == false ? displayname : nil) ??
                    (trimmedTail.split(separator: "/").last.map(String.init) ?? trimmedTail)
                entries.append(DavEntry(
                    href: decoded,
                    name: displayName,
                    isDirectory: isDir,
                    size: size,
                    modifiedAt: lastModified.flatMap { httpDate.date(from: $0) }
                ))
            }
            inResponse = false
        default: break
        }
    }
}
