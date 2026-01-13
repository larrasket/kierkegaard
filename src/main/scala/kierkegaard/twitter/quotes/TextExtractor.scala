package kierkegaard.twitter.quotes

import org.jsoup.Jsoup
import java.io.{File, FileInputStream}
import java.util.zip.ZipInputStream
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.{Try, Using}

object TextExtractor {
  
  def extractFromEpub(epubPath: String): Either[String, String] = {
    Try {
      val zipFile = new java.util.zip.ZipFile(new File(epubPath))
      val entries = zipFile.entries()
      val textBuilder = new StringBuilder
      
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        if (entry.getName.endsWith(".html") || entry.getName.endsWith(".xhtml")) {
          val inputStream = zipFile.getInputStream(entry)
          val content = Source.fromInputStream(inputStream, "UTF-8").mkString
          inputStream.close()
          
          // Parse HTML and extract text
          val doc = Jsoup.parse(content)
          doc.select("script, style, head").remove()
          val text = doc.body().text()
          textBuilder.append(text).append("\n\n")
        }
      }
      zipFile.close()
      textBuilder.toString()
    }.toEither.left.map(e => s"Failed to extract EPUB: ${e.getMessage}")
  }
  
  def extractFromText(textPath: String): Either[String, String] = {
    Try {
      // Handle BOM and different line endings
      val content = Source.fromFile(textPath, "UTF-8").mkString
      // Remove BOM if present
      val cleanContent = if (content.startsWith("\uFEFF")) content.drop(1) else content
      // Normalize line endings
      cleanContent.replace("\r\n", "\n").replace("\r", "\n")
    }.toEither.left.map(e => s"Failed to read text file: ${e.getMessage}")
  }
  
  def extractAllSources(basePath: String): Either[String, Map[String, String]] = {
    val sources = Map(
      "selections" -> s"$basePath/selections.txt",
      "eitheror" -> s"$basePath/eitheror.epub",
      "journals" -> s"$basePath/papers_and_journals"
    )
    
    val results = sources.map { case (name, path) =>
      val content = if (path.endsWith(".txt")) {
        extractFromText(path)
      } else {
        extractFromEpub(path)
      }
      name -> content
    }
    
    // Check for any errors
    val errors = results.collect { case (name, Left(err)) => s"$name: $err" }
    if (errors.nonEmpty) {
      Left(errors.mkString("; "))
    } else {
      Right(results.collect { case (name, Right(content)) => name -> content })
    }
  }
}
