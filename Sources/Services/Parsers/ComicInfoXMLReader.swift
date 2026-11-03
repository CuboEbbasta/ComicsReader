import Foundation

/// Legge i metadati da un file ComicInfo.xml (lo standard usato da molti CBZ/CBR), quando presente
/// nell'archivio. Usato sia dal parser CBZ (Fase 2a) sia, più avanti, da quello CBR.
enum ComicInfoXMLReader {
    static func parse(data: Data) -> ComicMetadata {
        let delegate = ComicInfoParserDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.metadata
    }

    private final class ComicInfoParserDelegate: NSObject, XMLParserDelegate {
        var metadata = ComicMetadata()
        private var currentText = ""

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String]) {
            currentText = ""
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            // foundCharacters può essere chiamato più volte per lo stesso nodo di testo:
            // accumuliamo finché non arriva la chiusura del tag.
            currentText += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            let value = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !value.isEmpty else { return }
            switch elementName {
            case "Title": metadata.title = value
            case "Series": metadata.series = value
            case "Number": metadata.number = value
            case "Writer": metadata.writer = value
            case "Penciller": metadata.penciller = value
            case "Summary": metadata.summary = value
            case "Year": metadata.year = Int(value)
            case "PageCount": metadata.pageCount = Int(value)
            case "LanguageISO": metadata.languageISO = value
            default: break
            }
        }
    }
}
