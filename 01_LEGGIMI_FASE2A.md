# FumettiReader — Fase 2a: copertine e metadati (CBZ + PDF)

**Nota (aggiornata dopo la Fase attuale):** il passaggio "aggiungi la libreria ZIPFoundation"
descritto originariamente in questo file **non serve più**. Dopo aver avuto tre problemi
diversi in sequenza legati a quella dipendenza esterna su Swift Playgrounds (manifest del
pacchetto, modalità di concorrenza, infine la firma del bundle di risorse), è stata sostituita
con un lettore ZIP scritto apposta usando solo Foundation e il framework Compression di Apple
(già incluso in iOS). Vedi `05_LEGGIMI_NIENTE_PIU_ZIPFOUNDATION.md` per i dettagli. Il resto di
questo file (cosa fanno CBZParser/PDFParser) resta valido.

## Nuovi file da aggiungere (di questa fase)

```
Sources/Services/
  ComicParsingService.swift     → smista copertina/metadati al parser giusto in base al formato
  CoverCache.swift              → cache su disco delle copertine estratte
  Parsers/
    ComicInfoXMLReader.swift    → legge ComicInfo.xml (metadati standard di molti CBZ/CBR)
    CBZParser.swift             → copertina + metadati per CBZ
    PDFParser.swift             → copertina + metadati per PDF (usa PDFKit, già incluso in iOS)
```

## Cosa provare

- Apri l'app: i fumetti CBZ e PDF dovrebbero mostrare una piccola copertina reale invece della
  sola icona generica.
- Le copertine restano visibili anche offline dopo il primo caricamento (sono salvate in cache).
