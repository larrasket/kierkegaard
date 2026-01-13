package kierkegaard.twitter.daemon

import kierkegaard.twitter.auth.{TwitterCredentials, TwitterAuth, OAuth2Credentials}
import kierkegaard.twitter.client.TwitterClient
import kierkegaard.twitter.quotes.QuoteDatabase
import java.net.{ServerSocket, InetSocketAddress}
import java.io.{BufferedReader, InputStreamReader}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.{Future, ExecutionContext}
import scala.util.Try

object DaemonServer {
  
  implicit val ec: ExecutionContext = ExecutionContext.global
  
  private val POST_HOURS = Set(0, 1, 2, 4, 6, 8, 10, 12, 14, 16, 17, 18, 19, 20, 22)
  
  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  
  // Mutable state for token management
  @volatile private var currentAccessToken: String = _
  @volatile private var currentRefreshToken: Option[String] = None
  @volatile private var oauth2Credentials: OAuth2Credentials = _
  
  private def log(msg: String): Unit = {
    val now = LocalDateTime.now().format(dateFormatter)
    println(s"[$now] $msg")
  }
  
  private def refreshToken(): Boolean = {
    currentRefreshToken match {
      case Some(refresh) =>
        log("Attempting to refresh access token...")
        TwitterAuth.refreshAccessToken(oauth2Credentials, refresh) match {
          case Right(tokenResp) =>
            currentAccessToken = tokenResp.access_token
            currentRefreshToken = tokenResp.refresh_token.orElse(currentRefreshToken)
            // Save the new tokens
            TwitterCredentials.saveAccessTokens(currentAccessToken, currentRefreshToken)
            log("Access token refreshed successfully")
            true
          case Left(err) =>
            log(s"Failed to refresh token: $err")
            false
        }
      case None =>
        log("No refresh token available, cannot refresh")
        false
    }
  }
  
  private def getClient(): TwitterClient = TwitterClient(currentAccessToken)
  
  def run(port: Int, password: String): Unit = {
    log(s"Starting Kierkegaard daemon on port $port")
    
    val credentials = TwitterCredentials.load() match {
      case Left(err) =>
        log(s"Failed to load credentials: $err")
        System.exit(1)
        return
      case Right(c) => c
    }
    
    oauth2Credentials = credentials.oauth2
    
    credentials.tokens match {
      case Some(t) => 
        currentAccessToken = t.accessToken
        currentRefreshToken = t.refreshToken
      case None =>
        log("No access tokens. Run interactive mode first.")
        System.exit(1)
        return
    }
    
    log("Credentials loaded, starting HTTP server...")
    
    // Start HTTP server in a separate thread  
    val httpThread = new Thread(() => runHttpServer(port, password))
    httpThread.setDaemon(true)
    httpThread.start()
    
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
        postQuote()
        lastPostHour = currentHour
      }
      
      Thread.sleep(30000)
    }
  }
  
  private def postQuote(): Unit = {
    postQuoteWithRetry(retried = false, skipCount = 0)
  }
  
  private def postQuoteWithRetry(retried: Boolean, skipCount: Int): Unit = {
    if (skipCount > 10) {
      log("Skipped too many duplicates, stopping")
      return
    }
    QuoteDatabase.load() match {
      case Left(err) =>
        log(s"Failed to load quotes: $err")
      case Right(db) =>
        QuoteDatabase.getNextQuote(db) match {
          case None =>
            log("No unposted quotes remaining!")
          case Some(quote) =>
            getClient().tweet(quote.text) match {
              case Left(err) if err.toString.contains("401") && !retried =>
                log("Got 401, attempting token refresh...")
                if (refreshToken()) {
                  postQuoteWithRetry(retried = true, skipCount)
                } else {
                  log(s"Tweet failed after refresh attempt: $err")
                }
              case Left(err) if err.toString.contains("403") && err.toString.contains("duplicate") =>
                log(s"Quote already on Twitter, skipping: ${quote.text.take(50)}...")
                val updated = QuoteDatabase.markAsPosted(db, quote.text)
                QuoteDatabase.save(updated)
                postQuoteWithRetry(retried, skipCount + 1)
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
  
  private def runHttpServer(port: Int, password: String): Unit = {
    val serverSocket = new ServerSocket()
    serverSocket.bind(new InetSocketAddress("0.0.0.0", port))
    log(s"HTTP server listening on 0.0.0.0:$port")
    
    while (true) {
      try {
        val socket = serverSocket.accept()
        Future {
          try {
            handleRequest(socket, password)
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
  
  private def handleRequest(socket: java.net.Socket, password: String): Unit = {
    socket.setSoTimeout(10000) // 10 second timeout
    val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
    val out = socket.getOutputStream

    val requestLine = Try(in.readLine()).getOrElse(null)
    if (requestLine == null) return

    var contentLength = 0
    var line = Try(in.readLine()).getOrElse("")
    while (line != null && !line.isEmpty) {
      if (line.toLowerCase.startsWith("content-length:")) {
        contentLength = line.split(":")(1).trim.toInt
      }
      line = Try(in.readLine()).getOrElse("")
    }

    val response = if (requestLine.startsWith("GET /health") || requestLine.startsWith("GET / HTTP")) {
      val db = QuoteDatabase.load().getOrElse(QuoteDatabase(List.empty, ""))
      val remaining = db.quotes.count(!_.posted)
      val posted = db.quotes.count(_.posted)
      
      // Calculate next post time
      val now = LocalDateTime.now()
      val currentHour = now.getHour
      val currentMinute = now.getMinute
      val nextPostHour = POST_HOURS.filter(_ > currentHour).minOption.getOrElse(POST_HOURS.min)
      val nextPostToday = nextPostHour > currentHour
      
      // Calculate minutes until next post
      val minutesUntilNextPost = if (nextPostToday) {
        (nextPostHour - currentHour) * 60 - currentMinute
      } else {
        (24 - currentHour + nextPostHour) * 60 - currentMinute
      }
      val hoursLeft = minutesUntilNextPost / 60
      val minsLeft = minutesUntilNextPost % 60
      val timeLeft = if (hoursLeft > 0) s"${hoursLeft}h ${minsLeft}m" else s"${minsLeft}m"
      
      val body = s"""{"status":"ok","remaining":$remaining,"posted":$posted,"nextPostHour":$nextPostHour,"timeUntilNextPost":"$timeLeft"}"""
      s"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
    } else if (requestLine.contains("/post")) {
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
            val body = s"""{"error":"$err"}"""
            s"HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
          case Right(db) =>
            QuoteDatabase.getNextQuote(db) match {
              case None =>
                val body = """{"error":"No unposted quotes"}"""
                s"HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
              case Some(quote) =>
                getClient().tweet(quote.text) match {
                  case Left(err) if err.toString.contains("403") && err.toString.contains("duplicate") =>
                    // Mark as posted since Twitter already has this tweet
                    log(s"Quote already on Twitter, marking as posted: ${quote.text.take(50)}...")
                    val updated = QuoteDatabase.markAsPosted(db, quote.text)
                    QuoteDatabase.save(updated)
                    val body = s"""{"status":"skipped_duplicate","text":"${quote.text.take(100).replace("\"", "'")}..."}"""
                    s"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                  case Left(err) =>
                    val body = s"""{"error":"${err.toString.replace("\"", "'")}"}"""
                    s"HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                  case Right(resp) =>
                    val updated = QuoteDatabase.markAsPosted(db, quote.text)
                    QuoteDatabase.save(updated) match {
                      case Left(err) => log(s"Failed to save database: $err")
                      case Right(_) => log(s"Database saved successfully")
                    }
                    log(s"Force posted: ${quote.text.take(50)}...")
                    val body = s"""{"status":"posted","id":"${resp.id}","text":"${quote.text.take(100).replace("\"", "'")}..."}"""
                    s"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                }
            }
        }
      } else {
        val body = """{"error":"Invalid password"}"""
        s"HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
      }
      
    } else {
      val body = """{"error":"Unknown endpoint","endpoints":["/health","/post?password=XXX"]}"""
      s"HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
    }
    
    out.write(response.getBytes("UTF-8"))
    out.flush()
  }
}
