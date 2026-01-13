package kierkegaard.twitter.quotes

import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import java.io.{File, PrintWriter}
import java.time.Instant
import scala.io.Source
import scala.util.Try

case class QuoteEntry(
  text: String,
  score: Int,
  source: String,
  posted: Boolean = false,
  postedAt: Option[String] = None
)

case class QuoteDatabase(
  quotes: List[QuoteEntry],
  lastUpdated: String
)

object QuoteDatabase {
  
  val DATA_DIR = "data"
  val QUOTES_FILE = s"$DATA_DIR/quotes.json"
  val HISTORY_FILE = s"$DATA_DIR/history.json"
  
  def ensureDataDir(): Unit = {
    val dir = new File(DATA_DIR)
    if (!dir.exists()) dir.mkdirs()
  }
  
  def load(): Either[String, QuoteDatabase] = {
    ensureDataDir()
    val file = new File(QUOTES_FILE)
    if (!file.exists()) {
      Right(QuoteDatabase(List.empty, Instant.now().toString))
    } else {
      Try {
        val content = Source.fromFile(file, "UTF-8").mkString
        decode[QuoteDatabase](content) match {
          case Right(db) => db
          case Left(err) => throw new RuntimeException(s"JSON parse error: ${err.getMessage}")
        }
      }.toEither.left.map(_.getMessage)
    }
  }
  
  def save(db: QuoteDatabase): Either[String, Unit] = {
    ensureDataDir()
    Try {
      val writer = new PrintWriter(new File(QUOTES_FILE), "UTF-8")
      writer.write(db.asJson.spaces2)
      writer.close()
    }.toEither.left.map(_.getMessage)
  }
  
  def buildFromSources(basePath: String): Either[String, QuoteDatabase] = {
    val scorer = QuoteScorer()
    
    TextExtractor.extractAllSources(basePath).map { sources =>
      val allQuotes = sources.flatMap { case (sourceName, content) =>
        println(s"Processing $sourceName...")
        val scored = scorer.extractAndScore(content)
        println(s"  Found ${scored.length} candidate quotes")
        scored.map(sq => QuoteEntry(
          text = sq.text,
          score = sq.score,
          source = sourceName,
          posted = false
        ))
      }.toList
      
      val uniqueQuotes = allQuotes
        .groupBy(_.text)
        .values
        .map(_.maxBy(_.score))
        .toList
        .sortBy(-_.score)
      
      println(s"Total unique quotes: ${uniqueQuotes.length}")
      println(s"Top 10 scores: ${uniqueQuotes.take(10).map(_.score).mkString(", ")}")
      
      QuoteDatabase(uniqueQuotes, Instant.now().toString)
    }
  }
  
  def getNextQuote(db: QuoteDatabase): Option[QuoteEntry] = {
    db.quotes
      .filter(!_.posted)
      .sortBy(-_.score)
      .headOption
  }
  
  def markAsPosted(db: QuoteDatabase, quoteText: String): QuoteDatabase = {
    val updatedQuotes = db.quotes.map { q =>
      if (q.text == quoteText) {
        q.copy(posted = true, postedAt = Some(Instant.now().toString))
      } else q
    }
    db.copy(quotes = updatedQuotes, lastUpdated = Instant.now().toString)
  }
  
  def stats(db: QuoteDatabase): String = {
    val total = db.quotes.length
    val posted = db.quotes.count(_.posted)
    val unposted = total - posted
    val avgScore = if (total > 0) db.quotes.map(_.score).sum / total else 0
    val bySource = db.quotes.groupBy(_.source).map { case (s, qs) => s"$s: ${qs.length}" }.mkString(", ")
    
    s"""Quote Database Stats:
       |  Total quotes: $total
       |  Posted: $posted
       |  Remaining: $unposted
       |  Average score: $avgScore
       |  By source: $bySource
       |  Last updated: ${db.lastUpdated}""".stripMargin
  }
}
