import Cocoa
import UniformTypeIdentifiers

/// RD2Mac Share Extension principal class. Shown in the macOS Share menu
/// (Finder / QSpace). It reads the shared files' bytes and POSTs each to the main
/// app's loopback control endpoint (`/send-file`), which forwards to the phone —
/// so the extension needs no discovery or peer state of its own.
///
/// @objc name is stable ("ShareController") → matches NSExtensionPrincipalClass.
@objc(ShareController)
class ShareController: NSViewController {

    private let controlURL = URL(string: "http://127.0.0.1:53319/send-file")!
    private var statusLabel: NSTextField!

    override func loadView() {
        let container = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 90))
        statusLabel = NSTextField(labelWithString: "Sending to RD2Mac…")
        statusLabel.frame = NSRect(x: 20, y: 35, width: 300, height: 20)
        statusLabel.alignment = .center
        container.addSubview(statusLabel)
        view = container
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        Task { await sendAll() }
    }

    private func sendAll() async {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?
            .flatMap { $0.attachments ?? [] } ?? []
        var sent = 0
        var failed = 0
        for provider in providers {
            if let (name, data) = await loadFile(provider), await post(name: name, data: data) {
                sent += 1
            } else {
                failed += 1
            }
        }
        await MainActor.run {
            statusLabel.stringValue = failed == 0 ? "Sent ✓ (\(sent))" : "Failed: \(failed)"
        }
        try? await Task.sleep(nanoseconds: 800_000_000) // let the user see the result
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }

    /// Resolve a provider to (filename, bytes). Handles both a file URL and a raw
    /// data representation (covers Finder files and in-memory shares).
    private func loadFile(_ provider: NSItemProvider) async -> (String, Data)? {
        let fileURLType = UTType.fileURL.identifier
        if provider.hasItemConformingToTypeIdentifier(fileURLType) {
            let result: (String, Data)? = await withCheckedContinuation { cont in
                provider.loadItem(forTypeIdentifier: fileURLType, options: nil) { item, _ in
                    if let url = item as? URL, let data = self.readScoped(url) {
                        cont.resume(returning: (url.lastPathComponent, data))
                    } else if let urlData = item as? Data,
                              let url = URL(dataRepresentation: urlData, relativeTo: nil),
                              let data = self.readScoped(url) {
                        cont.resume(returning: (url.lastPathComponent, data))
                    } else {
                        cont.resume(returning: nil)
                    }
                }
            }
            if let result { return result }
        }
        // Fallback: a data-backed item (e.g. an image shared as data).
        if let type = provider.registeredTypeIdentifiers.first {
            return await withCheckedContinuation { cont in
                provider.loadDataRepresentation(forTypeIdentifier: type) { data, _ in
                    guard let data else { cont.resume(returning: nil); return }
                    let ext = UTType(type)?.preferredFilenameExtension ?? "bin"
                    cont.resume(returning: ("RD2Mac-share.\(ext)", data))
                }
            }
        }
        return nil
    }

    /// Read a shared file under the sandbox's temporary security scope.
    private func readScoped(_ url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try? Data(contentsOf: url)
    }

    private func post(name: String, data: Data) async -> Bool {
        var request = URLRequest(url: controlURL, timeoutInterval: 60)
        request.httpMethod = "POST"
        request.setValue(headerEncode(name), forHTTPHeaderField: "X-RD2Mac-Filename")
        request.httpBody = data
        guard let (_, response) = try? await URLSession.shared.data(for: request) else { return false }
        return (response as? HTTPURLResponse)?.statusCode == 200
    }

    /// Same encoding as the app's `String.rd2macHeaderEncoded` (ASCII-alnum only).
    /// Duplicated here to keep the extension self-contained (no RD2MacCore link).
    private func headerEncode(_ s: String) -> String {
        var allowed = CharacterSet()
        allowed.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}
