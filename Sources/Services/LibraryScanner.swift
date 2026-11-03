import Foundation

/// Rappresentazione "leggera" di un nodo del filesystem, prodotta durante la scansione in background.
/// Contiene solo tipi semplici (Sendable) in modo da poter attraversare il confine di concorrenza
/// senza problemi; viene poi convertita nell'albero definitivo (ComicFolder/ComicFile) più in basso.
struct ScannedNode: Sendable {
    let name: String
    let relativePath: String
    let isDirectory: Bool
    let fileExtension: String
    let fileSizeBytes: Int64
    let modificationDate: Date?
    var children: [ScannedNode] = []
}

enum LibraryScanner {

    private static let resourceKeys: [URLResourceKey] = [
        .isDirectoryKey, .nameKey, .fileSizeKey, .contentModificationDateKey
    ]

    /// Legge il contenuto di una cartella tramite NSFileCoordinator invece che direttamente:
    /// per cartelle esterne o su iCloud Drive una lettura non coordinata può restituire un
    /// elenco non ancora aggiornato (serve poi un secondo tentativo perché il sistema "si
    /// accorga" dei file nuovi). La lettura coordinata evita il problema.
    private static func coordinatedContents(of url: URL) throws -> [URL] {
        var coordinatorError: NSError?
        var result: [URL] = []
        var accessorError: Error?
        let coordinator = NSFileCoordinator()
        coordinator.coordinate(readingItemAt: url, options: [], error: &coordinatorError) { coordinatedURL in
            do {
                result = try FileManager.default.contentsOfDirectory(
                    at: coordinatedURL,
                    includingPropertiesForKeys: resourceKeys,
                    options: [.skipsHiddenFiles]
                )
            } catch {
                accessorError = error
            }
        }
        if let coordinatorError { throw coordinatorError }
        if let accessorError { throw accessorError }
        return result
    }

    /// Avvia la scansione ricorsiva della cartella principale in un task separato, per non bloccare
    /// l'interfaccia. `rootURL` deve già avere l'accesso security-scoped attivo
    /// (se ne occupa FileSystemManager, che lo mantiene aperto per tutta la sessione).
    static func scan(rootURL: URL, maxDepth: Int) async throws -> ScannedNode {
        try await Task.detached(priority: .userInitiated) {
            try scanNode(
                url: rootURL,
                name: rootURL.lastPathComponent,
                relativePath: "",
                depth: 0,
                maxDepth: max(0, maxDepth)
            )
        }.value
    }

    private static func scanNode(url: URL, name: String, relativePath: String, depth: Int, maxDepth: Int) throws -> ScannedNode {
        var children: [ScannedNode] = []

        let contents = try coordinatedContents(of: url)

        for childURL in contents {
            let values = try? childURL.resourceValues(forKeys: Set(resourceKeys))
            let childName = values?.name ?? childURL.lastPathComponent
            let childRelativePath = relativePath.isEmpty ? childName : "\(relativePath)/\(childName)"
            let isDirectory = values?.isDirectory ?? false

            if isDirectory {
                // Scende in profondità solo se non abbiamo ancora raggiunto il limite impostato.
                // I file DENTRO l'ultimo livello consentito vengono comunque letti: è solo la
                // discesa in un livello ULTERIORE a essere bloccata.
                guard depth < maxDepth else { continue }
                let subNode = try scanNode(
                    url: childURL,
                    name: childName,
                    relativePath: childRelativePath,
                    depth: depth + 1,
                    maxDepth: maxDepth
                )
                // Evita di aggiungere rami completamente vuoti (nessun fumetto trovato dentro).
                if !subNode.children.isEmpty {
                    children.append(subNode)
                }
            } else {
                let ext = childURL.pathExtension.lowercased()
                guard ComicFormat.from(fileExtension: ext) != nil else { continue }
                children.append(ScannedNode(
                    name: childName,
                    relativePath: childRelativePath,
                    isDirectory: false,
                    fileExtension: ext,
                    fileSizeBytes: Int64(values?.fileSize ?? 0),
                    modificationDate: values?.contentModificationDate
                ))
            }
        }

        // Cartelle prima dei file, poi ordine alfabetico "naturale" (es. "Capitolo 2" prima di "Capitolo 10").
        children.sort { lhs, rhs in
            if lhs.isDirectory != rhs.isDirectory { return lhs.isDirectory && !rhs.isDirectory }
            return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
        }

        return ScannedNode(
            name: name,
            relativePath: relativePath,
            isDirectory: true,
            fileExtension: "",
            fileSizeBytes: 0,
            modificationDate: nil,
            children: children
        )
    }

    /// Converte l'albero "grezzo" (ScannedNode) nell'albero definitivo di modelli usato dall'interfaccia.
    static func buildTree(from node: ScannedNode, depth: Int = 0, parent: ComicFolder? = nil) -> ComicFolder {
        let folder = ComicFolder(
            relativePath: node.relativePath,
            name: node.name,
            depth: depth,
            parent: parent
        )
        for child in node.children {
            if child.isDirectory {
                let subfolder = buildTree(from: child, depth: depth + 1, parent: folder)
                folder.subfolders.append(subfolder)
            } else if let format = ComicFormat.from(fileExtension: child.fileExtension) {
                let comic = ComicFile(
                    relativePath: child.relativePath,
                    fileName: child.name,
                    format: format,
                    fileSizeBytes: child.fileSizeBytes,
                    modificationDate: child.modificationDate
                )
                folder.comics.append(comic)
            }
        }
        return folder
    }
}
