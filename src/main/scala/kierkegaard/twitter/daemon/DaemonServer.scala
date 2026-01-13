package kierkegaard.twitter.daemon

import kierkegaard.twitter.auth.TwitterCredentials
import kierkegaard.twitter.client.TwitterClient
import kierkegaard.twitter.quotes.QuoteDatabase
import java.net.{ServerSocket, InetSocketAddress}
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.time.{LocalDateTime, LocalTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.concurrent.{Future, ExecutionContext}
import scala.util.Try

object DaemonServer {
  
  implicit val ec: ExecutionContext = ExecutionContext.global
  
  private val POST_HOURS = Set(0, 1, 2, 4, 6, 8, 10, 12, 14, 16, 17, 18, 19, 20, 22)
  
  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  
  private def log(msg: String): Unit = {
    val now = LocalDateTime.now().format(dateFormatter)
    println(s"[$now] $msg")
  }
  
  def run(port: Int, password: String): Unit = {
    log(s"Starting Kierkegaard daemon on port $port")
    
    val credentials = TwitterCredentials.load() match {
      case Left(err) =>
        log(s"Failed to load credentials: $err")
        System.exit(1)
        return
      case Right(c) => c
    }
    
    val accessToken = credentials.tokens match {
      case Some(t) => t.accessToken
      case None =>
        log("No access tokens. Run interactive mode first.")
        System.exit(1)
        return
    }
    
    val client = TwitterClient(accessToken)
    log("Credentials loaded, bot authenticated")
    
    Future {
      runHttpServer(port, password, client)
    }
    
    var lastPostHour = -1
    var lastHealthHour = -1
    
    while (true) {
      val now = LocalDateTime.now()
      val currentHour = now.getHour
      val currentMinute = now.getMinute
      
      if (currentMinute == 0 && currentHour != lastHealthHour) {
        val db = QuoteDatabase.load().getOrElse(QuoteDatabase(List.empty, ""))
        val remaining = db.quotes.count(!_.posted)
        log(s"Health: OK | Remaining quotes: $remaining | Next post hour: ${POST_HOURS.filter(_ > currentHour).minOption.getOrElse(POST_HOURS.min)}")
        lastHealthHour = currentHour
      }
      
      if (currentMinute == 0 && POST_HOURS.contains(currentHour) && currentHour != lastPostHour) {
        log(s"Scheduled post triggered for hour $currentHour")
        postQuote(client)
        lastPostHour = currentHour
      }
      
      Thread.sleep(30000)
    }
  }
  
  private def postQuote(client: TwitterClient): Unit = {
    QuoteDatabase.load() match {
      case Left(err) =>
        log(s"Failed to load quotes: $err")
      case Right(db) =>
        QuoteDatabase.getNextQuote(db) match {
          case None =>
            log("No unposted quotes remaining!")
          case Some(quote) =>
            client.tweet(quote.text) match {
              case Left(err) =>
                log(s"Tweet failed: $err")
              case Right(resp) =>
                val updated = QuoteDatabase.markAsPosted(db, quote.text)
                QuoteDatabase.save(updated)
                log(s"Posted: ${quote.text.take(50)}... | Score: ${quote.score}")
            }
        }
    }
  }
  
  private def runHttpServer(port: Int, password: String, client: TwitterClient): Unit = {
    val serverSocket = new ServerSocket(port)
    log(s"HTTP server listening on port $port")
    
    while (true) {
      try {
        val socket = serverSocket.accept()
        Future {
          try {
            handleRequest(socket, password, client)
          } finally {
            socket.close()
          }
        }
      } catch {
        case e: Exception =>
          log(s"Server error: ${e.getMessage}")
      }
    }
  }
  
  private def handleRequest(socket: java.net.Socket, password: String, client: TwitterClient): Unit = {
    val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
    val out = new PrintWriter(socket.getOutputStream, true)
    
    val requestLine = in.readLine()
    if (requestLine == null) return
    
    var contentLength = 0
    var line = in.readLine()
    while (line != null && !line.isEmpty) {
      if (line.toLowerCase.startsWith("content-length:")) {
        contentLength = line.split(":")(1).trim.toInt
      }
      line = in.readLine()
    }
    
    val response = if (requestLine.startsWith("GET /health")) {
      val db = QuoteDatabase.load().getOrElse(QuoteDatabase(List.empty, ""))
      val remaining = db.quotes.count(!_.posted)
      val posted = db.quotes.count(_.posted)
      s"""HTTP/1.1 200 OK
         |Content-Type: application/json
         |
         |{"status":"ok","remaining":$remaining,"posted":$posted}""".stripMargin
         
    } else if (requestLine.startsWith("POST /post")) {
      val hasPassword = requestLine.contains(s"password=$password") || {
        if (contentLength > 0) {
          val body = new Array[Char](contentLength)
          in.read(body, 0, contentLength)
          new String(body).contains(s"password=$password")
        } else false
      }
      
      if (hasPassword) {
        log("Force post triggered via HTTP")
        QuoteDatabase.load() match {
          case Left(err) =>
            s"""HTTP/1.1 500 Internal Server Error
               |Content-Type: application/json
               |
               |{"error":"$err"}""".stripMargin
          case Right(db) =>
            QuoteDatabase.getNextQuote(db) match {
              case None =>
                s"""HTTP/1.1 404 Not Found
                   |Content-Type: application/json
                   |
                   |{"error":"No unposted quotes"}""".stripMargin
              case Some(quote) =>
                client.tweet(quote.text) match {
                  case Left(err) =>
                    s"""HTTP/1.1 500 Internal Server Error
                       |Content-Type: application/json
                       |
                       |{"error":"${err.toString.replace("\"", "'")}"}""".stripMargin
                  case Right(resp) =>
                    val updated = QuoteDatabase.markAsPosted(db, quote.text)
                    QuoteDatabase.save(updated)
                    log(s"Force posted: ${quote.text.take(50)}...")
                    s"""HTTP/1.1 200 OK
                       |Content-Type: application/json
                       |
                       |{"status":"posted","id":"${resp.id}","text":"${quote.text.take(100).replace("\"", "'")}..."}""".stripMargin
                }
            }
        }
      } else {
        s"""HTTP/1.1 401 Unauthorized
           |Content-Type: application/json
           |
           |{"error":"Invalid password"}""".stripMargin
      }
      
    } else {
      s"""HTTP/1.1 404 Not Found
         |Content-Type: application/json
         |
         |{"error":"Unknown endpoint","endpoints":["/health","/post?password=XXX"]}""".stripMargin
    }
    
    out.println(response)
    out.flush()
  }
}
