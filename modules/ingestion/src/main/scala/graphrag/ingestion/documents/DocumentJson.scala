package graphrag.ingestion.documents

case class DocumentJson(
                         docId: String,
                         contentHash: String,
                         text: String,
                         uri: String,
                         year: Option[Int] = None
                       )

case class Document(
                     docId: String,
                     text: String,
                     uri: String,
                     year: Option[Int] = None
                   )
