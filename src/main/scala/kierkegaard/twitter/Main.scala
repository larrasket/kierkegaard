package kierkegaard.twitter

import kierkegaard.twitter.auth._
import kierkegaard.twitter.client.TwitterClient
import kierkegaard.twitter.quotes.QuoteDatabase
import kierkegaard.twitter.daemon.DaemonServer
import scala.io.StdIn
import java.net.ServerSocket
import java.io.{BufferedReader, InputStreamReader, PrintWriter}

object Main extends App {
  
  val mode = args.headOption.getOrElse("--interactive")
  
  mode match {
    case "--build-quotes" => buildQuotes()
    case "--dry-run" => dryRun()
    case "--stats" => showStats()
    case "--daemon" => runDaemon()
    case "--interactive" | _ => interactive()
  }
  
  def runDaemon(): Unit = {
    val port = sys.env.getOrElse("PORT", "8080").toInt
    val password = sys.env.getOrElse("FORCE_POST_PASSWORD", "kierkegaard")
    DaemonServer.run(port, password)
  }
  
  def buildQuotes(): Unit = {
    val basePath = System.getProperty("user.dir")
    println(s"Base path: $basePath")
    
    QuoteDatabase.buildFromSources(basePath) match {
      case Left(error) =>
        System.exit(1)
      case Right(db) =>
        QuoteDatabase.save(db) match {
          case Left(error) =>
            System.exit(1)
          case Right(_) =>
            println(s"Quote database built")
            println(QuoteDatabase.stats(db))
            
            db.quotes.take(10).zipWithIndex.foreach { case (q, i) =>
              println(s"${i+1}. [${q.score}] ${q.text.take(80)}...")
            }
        }
    }
  }
  
  def dryRun(): Unit = {
    QuoteDatabase.load() match {
      case Left(error) =>
        println(s"Failed to load quote database: $error")
        System.exit(1)
      case Right(db) =>
        QuoteDatabase.getNextQuote(db) match {
          case None =>
            println("No unposted quotes remaining!")
          case Some(quote) =>
            println("Next quote to be posted:")
            println(s"  Text: ${quote.text}")
            println(s"  Length: ${quote.text.length} chars")
            println(s"  Score: ${quote.score}")
            println(s"  Source: ${quote.source}")
        }
    }
  }
  
  def showStats(): Unit = {
    QuoteDatabase.load() match {
      case Left(error) =>
        println(s"Failed to load quote database: $error")
        System.exit(1)
      case Right(db) =>
        println(QuoteDatabase.stats(db))
        val remaining = db.quotes.filter(!_.posted).sortBy(-_.score).take(5)
        if (remaining.nonEmpty) {
          println("\nNext 5 quotes to post:")
          remaining.zipWithIndex.foreach { case (q, i) =>
            println(s"  ${i+1}. [${q.score}] ${q.text.take(60)}...")
          }
        }
    }
  }
  
  def interactive(): Unit = {
    TwitterCredentials.load() match {
      case Left(error) =>
        println(s"Failed to load credentials: $error")
        System.exit(1)
      case Right(credentials) =>
        credentials.tokens match {
          case None =>
            println("No access tokens found. Starting OAuth 2.0 flow...\n")
            runOAuth2Flow(credentials.oauth2)
          case Some(tokens) =>
            println("Access tokens found. Authenticating...\n")
            val client = TwitterClient(tokens.accessToken)
            client.getAccountInfo match {
              case Right((username, name)) =>
                println(s"Authenticated as: @$username ($name)\n")
                showMenu(client)
              case Left(_) =>
                tokens.refreshToken match {
                  case Some(refresh) =>
                    println("Access token expired, attempting refresh...\n")
                    TwitterAuth.refreshAccessToken(credentials.oauth2, refresh) match {
                      case Right(tokenResp) =>
                        println("Token refreshed successfully!\n")
                        TwitterCredentials.saveAccessTokens(tokenResp.access_token, tokenResp.refresh_token)
                        runAuthenticated(tokenResp.access_token)
                      case Left(_) =>
                        println("Refresh failed. Starting OAuth 2.0 flow...\n")
                        runOAuth2Flow(credentials.oauth2)
                    }
                  case None =>
                    println("Access token expired and no refresh token. Starting OAuth 2.0 flow...\n")
                    runOAuth2Flow(credentials.oauth2)
                }
            }
        }
    }
  }
  
  def runOAuth2Flow(oauth2: OAuth2Credentials): Unit = {
    val (codeVerifier, codeChallenge) = TwitterAuth.generatePKCE()
    val authUrl = TwitterAuth.getAuthorizationUrl(oauth2, "http://127.0.0.1:8080/callback", codeChallenge)
    
    println(s"Open this URL in your browser:\n$authUrl\n")
    println("Waiting for callback...")
    
    waitForCallback() match {
      case Some(code) =>
        println("\nExchanging authorization code for access token...")
        TwitterAuth.getAccessToken(oauth2, code, codeVerifier, "http://127.0.0.1:8080/callback") match {
          case Left(error) =>
            println(s"\nOAuth failed: $error")
            System.exit(1)
          case Right(tokenResp) =>
            println(s"Access Token: ${tokenResp.access_token.take(10)}...")
            tokenResp.refresh_token.foreach(r => println(s"Refresh Token: ${r.take(10)}..."))
            println("\nSaving access tokens...")
            TwitterCredentials.saveAccessTokens(tokenResp.access_token, tokenResp.refresh_token) match {
              case Left(_) =>
              case Right(_) => println("Tokens saved successfully\n")
            }
            runAuthenticated(tokenResp.access_token)
        }
      case None =>
        println("\nFailed to receive authorization code")
        System.exit(1)
    }
  }
  
  def waitForCallback(): Option[String] = {
    try {
      val serverSocket = new ServerSocket(8080)
      serverSocket.setSoTimeout(120000) 
      
      val socket = serverSocket.accept()
      val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val out = new PrintWriter(socket.getOutputStream, true)
      
      val requestLine = in.readLine()
      
      val code = if (requestLine != null && requestLine.contains("code=")) {
        val query = requestLine.split(" ")(1).split("\\?")(1)
        val params = query.split("&").map { param =>
          val parts = param.split("=")
          parts(0) -> parts(1)
        }.toMap
        params.get("code")
      } else {
        None
      }
      
      val response = if (code.isDefined) {
        """HTTP/1.1 200 OK
          |Content-Type: text/html
          |
          |<!DOCTYPE html>
          |<html>
          |<head><title>Authorization Successful</title></head>
          |<body style="font-family: Arial; text-align: center; padding: 50px;">
          |  <h1>Authorization Successful!</h1>
          |  <p>You can close this window and return to the terminal.</p>
          |</body>
          |</html>""".stripMargin
      } else {
        """HTTP/1.1 400 Bad Request
          |Content-Type: text/html
          |
          |<!DOCTYPE html>
          |<html>
          |<head><title>Authorization Failed</title></head>
          |<body style="font-family: Arial; text-align: center; padding: 50px;">
          |  <h1>Authorization Failed</h1>
          |  <p>No authorization code received.</p>
          |</body>
          |</html>""".stripMargin
      }
      
      out.println(response)
      out.flush()
      
      socket.close()
      serverSocket.close()
      
      code
    } catch {
      case e: Exception =>
        println(s"Error waiting for callback: ${e.getMessage}")
        None
    }
  }
  
  def runAuthenticated(accessToken: String): Unit = {
    val client = TwitterClient(accessToken)
    client.getAccountInfo match {
      case Left(error) =>
        println(s"Failed to verify account: $error")
        System.exit(1)
      case Right((username, name)) =>
        println(s"Authenticated as: @$username ($name)\n")
        showMenu(client)
    }
  }
  
  def showMenu(client: TwitterClient): Unit = {
    var continue = true
    while (continue) {
      println("1. Post from quote database")
      println("2. View database stats")
      println("3. Exit")
      print("\nSelect option: ")
      StdIn.readLine().trim match {
        case "1" => postFromDatabase(client)
        case "2" => showStats()
        case "3" =>
          println("\nGoodbye!")
          continue = false
        case _ => println("Invalid option.")
      }
    }
  }
  
  def postFromDatabase(client: TwitterClient): Unit = {
    QuoteDatabase.load() match {
      case Left(error) =>
        println(s"Failed to load database: $error")
      case Right(db) =>
        QuoteDatabase.getNextQuote(db) match {
          case None =>
            println("No unposted quotes remaining!")
          case Some(quote) =>
            println(s"\nPreview: ${quote.text}")
            println(s"Score: ${quote.score}, Source: ${quote.source}")
            print("\nPost this quote? (y/n): ")
            if (StdIn.readLine().trim.toLowerCase == "y") {
              client.tweet(quote.text) match {
                case Left(error) =>
                  println(s"Failed to post: $error")
                case Right(response) =>
                  val updated = QuoteDatabase.markAsPosted(db, quote.text)
                  QuoteDatabase.save(updated)
                  println(s"Posted: https://twitter.com/i/web/status/${response.id}")
              }
            } else {
              println("Cancelled.")
            }
        }
    }
  }
}
