import Foundation
import UIKit

/// Legge gli EPUB in due modi diversi a seconda del tipo di contenuto:
/// - **a layout fisso** (fixed-layout, lo standard per i fumetti): estrae copertina, metadati
///   e le singole pagine come immagini, seguendo tre file in cascata dentro l'archivio ZIP —
///   META-INF/container.xml (dice dove sta l'indice del libro) → il file .opf (l'indice: elenco
///   pagine in ordine, metadati) → il file .xhtml di ogni pagina (che contiene il riferimento
///   all'immagine vera e propria).
/// - **a testo scorrevole** ("classico"): non ha immagini pagina per pagina, quindi qui questo
///   file si limita a fornire l'elenco dei capitoli in ordine (`chapterPaths`) e a estrarre
///   l'intero archivio su disco (`extractAll`); la resa vera e propria del testo è affidata a
///   una WKWebView altrove (vedi EPUBFlowReaderView), non a questo parser.
///
/// `isFixedLayout(at:)` decide quale dei due casi si applica.
enum EPUBParser {

    // MARK: - API pubblica

    static func pages(at url: URL) throws -> [ComicPageInfo] {
        let archive = try Archive(url: url, accessMode: .read)
        let opf = try loadOPF(from: archive)
        let opfDirectory = directoryPath(of: opf.path)

        var result: [ComicPageInfo] = []
        for (offset, idref) in opf.delegate.spineIDRefs.enumerated() {
            guard let spineItem = opf.delegate.manifestItems[idref] else { continue }
            let spinePath = resolvePath(href: spineItem.href, relativeToDirectory: opfDirectory)
            guard let imgPath = try? imagePath(forSpineItemAt: spinePath, in: archive) else { continue }
            result.append(ComicPageInfo(id: spinePath, path: imgPath, index: offset))
        }
        guard !result.isEmpty else {
            throw ReaderDocumentError.unsupportedFormat
        }
        return result
    }

    static func imageData(at page: ComicPageInfo, archiveURL: URL) throws -> Data {
        let archive = try Archive(url: archiveURL, accessMode: .read)
        guard let entry = archive[page.path] else { throw CocoaError(.fileNoSuchFile) }
        return try CBZParser.extractData(for: entry, from: archive)
    }

    static func extractCoverImage(at url: URL) throws -> UIImage? {
        let archive = try Archive(url: url, accessMode: .read)
        let opf = try loadOPF(from: archive)
        let opfDirectory = directoryPath(of: opf.path)

        if let coverItem = opf.delegate.coverItem {
            let coverPath = resolvePath(href: coverItem.href, relativeToDirectory: opfDirectory)
            if let entry = archive[coverPath] {
                let data = try CBZParser.extractData(for: entry, from: archive)
                if let image = UIImage(data: data) { return image }
            }
        }
        // Nessuna copertina dichiarata esplicitamente nell'OPF: usa l'immagine della prima pagina.
        if let firstPage = try? pages(at: url).first {
            let data = try imageData(at: firstPage, archiveURL: url)
            return UIImage(data: data)
        }
        return nil
    }

    static func readMetadata(at url: URL) throws -> ComicMetadata {
        let archive = try Archive(url: url, accessMode: .read)
        let opf = try loadOPF(from: archive)
        var metadata = ComicMetadata()
        metadata.title = opf.delegate.title
        metadata.writer = opf.delegate.creator
        metadata.languageISO = opf.delegate.language
        metadata.pageCount = opf.delegate.spineIDRefs.count
        return metadata
    }

    /// Un EPUB è "a layout fisso" (quindi trattabile come un fumetto a immagini, pagina per
    /// pagina) se lo dichiara esplicitamente nell'OPF, oppure — se non lo dichiara — se la
    /// maggior parte dei suoi capitoli contiene comunque una singola immagine a piena pagina.
    /// Altrimenti è un EPUB "classico" (testo che scorre) e va letto in modo diverso.
    static func isFixedLayout(at url: URL) throws -> Bool {
        let archive = try Archive(url: url, accessMode: .read)
        let opf = try loadOPF(from: archive)
        if let declared = opf.delegate.isFixedLayoutDeclared {
            return declared
        }
        let opfDirectory = directoryPath(of: opf.path)
        let spineRefs = opf.delegate.spineIDRefs
        guard !spineRefs.isEmpty else { return false }
        var withImage = 0
        for idref in spineRefs {
            guard let item = opf.delegate.manifestItems[idref] else { continue }
            let path = resolvePath(href: item.href, relativeToDirectory: opfDirectory)
            if (try? imagePath(forSpineItemAt: path, in: archive)) != nil {
                withImage += 1
            }
        }
        return Double(withImage) / Double(spineRefs.count) >= 0.6
    }

    /// Percorsi (in ordine di lettura) dei file XHTML dei singoli capitoli, per la lettura
    /// "classica" a testo scorrevole. A differenza di `pages(at:)`, qui interessa il capitolo
    /// XHTML intero, non l'immagine che eventualmente contiene.
    static func chapterPaths(at url: URL) throws -> [String] {
        let archive = try Archive(url: url, accessMode: .read)
        let opf = try loadOPF(from: archive)
        let opfDirectory = directoryPath(of: opf.path)
        return opf.delegate.spineIDRefs.compactMap { idref in
            guard let item = opf.delegate.manifestItems[idref] else { return nil }
            return resolvePath(href: item.href, relativeToDirectory: opfDirectory)
        }
    }

    /// Estrae l'intero archivio su disco, in una cartella temporanea: serve al lettore a testo
    /// scorrevole, che mostra i capitoli tramite una WKWebView e ha bisogno di file veri e propri
    /// (con le immagini e i fogli di stile accanto) invece di dati in memoria.
    static func extractAll(from url: URL, to destinationDirectory: URL) throws {
        let archive = try Archive(url: url, accessMode: .read)
        try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
        for entry in archive {
            let destinationURL = destinationDirectory.appendingPathComponent(entry.path)
            if entry.type == .directory {
                try? FileManager.default.createDirectory(at: destinationURL, withIntermediateDirectories: true)
                continue
            }
            try FileManager.default.createDirectory(
                at: destinationURL.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            // Riuso lo stesso metodo di estrazione (extract con consumer) già usato altrove in
            // questo file, invece di un'altra firma di ZIPFoundation che estrae direttamente su
            // disco: così l'unica sintassi da cui dipendiamo è quella già verificata.
            let data = try CBZParser.extractData(for: entry, from: archive)
            try data.write(to: destinationURL)
        }
    }

    // MARK: - container.xml → percorso e contenuto dell'OPF

    static func loadOPF(from archive: Archive) throws -> (path: String, delegate: OPFDelegate) {
        guard let containerEntry = archive["META-INF/container.xml"] else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let containerData = try CBZParser.extractData(for: containerEntry, from: archive)
        let containerDelegate = ContainerXMLDelegate()
        let containerParser = XMLParser(data: containerData)
        containerParser.delegate = containerDelegate
        containerParser.parse()
        guard let opfPath = containerDelegate.opfPath, let opfEntry = archive[opfPath] else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let opfData = try CBZParser.extractData(for: opfEntry, from: archive)
        let opfDelegate = OPFDelegate()
        let opfParser = XMLParser(data: opfData)
        opfParser.delegate = opfDelegate
        opfParser.parse()
        return (opfPath, opfDelegate)
    }

    /// Dato il percorso di un file XHTML di una pagina, trova l'immagine a piena pagina che
    /// referenzia (<img src="…"> oppure <image xlink:href="…"> per le pagine basate su SVG,
    /// entrambe molto comuni negli EPUB a layout fisso) e ne restituisce il percorso risolto
    /// dentro l'archivio.
    private static func imagePath(forSpineItemAt spinePath: String, in archive: Archive) throws -> String {
        guard let entry = archive[spinePath] else { throw CocoaError(.fileNoSuchFile) }
        let data = try CBZParser.extractData(for: entry, from: archive)
        let delegate = XHTMLImageDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        guard let href = delegate.imageHref else { throw CocoaError(.fileReadCorruptFile) }
        return resolvePath(href: href, relativeToDirectory: directoryPath(of: spinePath))
    }

    // MARK: - Risoluzione percorsi relativi dentro l'archivio

    private static func directoryPath(of filePath: String) -> String {
        guard let lastSlash = filePath.lastIndex(of: "/") else { return "" }
        return String(filePath[..<lastSlash])
    }

    private static func resolvePath(href: String, relativeToDirectory directory: String) -> String {
        var cleanHref = href
        if let hashIndex = cleanHref.firstIndex(of: "#") {
            cleanHref = String(cleanHref[..<hashIndex])
        }
        cleanHref = cleanHref.removingPercentEncoding ?? cleanHref
        guard !cleanHref.isEmpty else { return directory }

        if cleanHref.hasPrefix("/") {
            return String(cleanHref.dropFirst())
        }

        var resultComponents = directory.isEmpty ? [] : directory.split(separator: "/").map(String.init)
        for component in cleanHref.split(separator: "/") {
            if component == ".." {
                if !resultComponents.isEmpty { resultComponents.removeLast() }
            } else if component == "." {
                continue
            } else {
                resultComponents.append(String(component))
            }
        }
        return resultComponents.joined(separator: "/")
    }

    // MARK: - Delegati XML (SAX): estraggono solo i pochi campi che ci servono

    final class ContainerXMLDelegate: NSObject, XMLParserDelegate {
        private(set) var opfPath: String?
        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String]) {
            let localName = elementName.split(separator: ":").last.map(String.init) ?? elementName
            if localName == "rootfile", let fullPath = attributeDict["full-path"] {
                opfPath = fullPath
            }
        }
    }

    final class OPFDelegate: NSObject, XMLParserDelegate {
        struct ManifestItem {
            let href: String
            let mediaType: String
            let properties: String?
        }

        private(set) var manifestItems: [String: ManifestItem] = [:]
        private(set) var spineIDRefs: [String] = []
        private(set) var coverMetaContentID: String?
        private(set) var title: String?
        private(set) var creator: String?
        private(set) var language: String?
        /// nil = non dichiarato esplicitamente nell'OPF; true/false = dichiarato tramite
        /// <meta property="rendition:layout">pre-paginated / reflowable</meta> (standard EPUB3).
        private(set) var isFixedLayoutDeclared: Bool?

        private var currentText = ""
        private var currentMetaProperty: String?

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String]) {
            let localName = elementName.split(separator: ":").last.map(String.init) ?? elementName
            currentText = ""
            currentMetaProperty = attributeDict["property"]

            switch localName {
            case "item":
                guard let id = attributeDict["id"], let href = attributeDict["href"] else { return }
                manifestItems[id] = ManifestItem(
                    href: href,
                    mediaType: attributeDict["media-type"] ?? "",
                    properties: attributeDict["properties"]
                )
            case "itemref":
                if let idref = attributeDict["idref"] { spineIDRefs.append(idref) }
            case "meta":
                if attributeDict["name"] == "cover", let content = attributeDict["content"] {
                    coverMetaContentID = content
                }
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            currentText += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            let localName = elementName.split(separator: ":").last.map(String.init) ?? elementName
            let value = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
            if localName == "meta", currentMetaProperty == "rendition:layout" {
                isFixedLayoutDeclared = (value == "pre-paginated")
            }
            guard !value.isEmpty else { return }
            switch localName {
            case "title": if title == nil { title = value }
            case "creator": if creator == nil { creator = value }
            case "language": if language == nil { language = value }
            default: break
            }
        }

        /// Copertina secondo lo standard EPUB3 (properties="cover-image"), poi EPUB2
        /// (<meta name="cover">), infine la prima immagine disponibile nel manifest.
        var coverItem: ManifestItem? {
            if let epub3Cover = manifestItems.values.first(where: { $0.properties?.contains("cover-image") == true }) {
                return epub3Cover
            }
            if let coverID = coverMetaContentID, let epub2Cover = manifestItems[coverID] {
                return epub2Cover
            }
            return manifestItems.values.first(where: { $0.mediaType.hasPrefix("image/") })
        }
    }

    final class XHTMLImageDelegate: NSObject, XMLParserDelegate {
        private(set) var imageHref: String?
        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String]) {
            guard imageHref == nil else { return }
            let localName = elementName.split(separator: ":").last.map(String.init) ?? elementName
            if localName == "img", let src = attributeDict["src"] {
                imageHref = src
            } else if localName == "image" {
                imageHref = attributeDict["xlink:href"] ?? attributeDict["href"]
            }
        }
    }
}
