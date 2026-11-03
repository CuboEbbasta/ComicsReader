import Foundation

/// Metadati di un fumetto. Nella Fase 2 verranno letti da ComicInfo.xml quando presente
/// (lo standard usato da molti CBZ/CBR), oppure lasciati vuoti: in quel caso l'interfaccia
/// userà semplicemente il nome del file come titolo.
struct ComicMetadata: Codable, Hashable, Sendable {
    var title: String?
    var series: String?
    var number: String?
    var writer: String?
    var penciller: String?
    var summary: String?
    var year: Int?
    var pageCount: Int?
    var languageISO: String?

    static let empty = ComicMetadata()
}
