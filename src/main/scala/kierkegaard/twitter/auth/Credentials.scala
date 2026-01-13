package kierkegaard.twitter.auth

import com.typesafe.config.{Config, ConfigFactory}
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import scala.util.Try
import java.io.{File, PrintWriter}
import scala.io.Source

case class OAuth2Credentials(
  clientId: String,
  clientSecret: String
)

case class AccessTokens(
  accessToken: String,
  refreshToken: Option[String] = None
)

case class TwitterCredentials(
  oauth2: OAuth2Credentials,
  tokens: Option[AccessTokens]
)

case class PersistedTokens(
  accessToken: String,
  refreshToken: Option[String]
)

object TwitterCredentials {
  
  private val TOKENS_FILE = "data/tokens.json"
  
  def load(): Either[String, TwitterCredentials] = {
    Try {
      val config: Config = ConfigFactory.load()
      
      val clientId = config.getString("twitter.oauth2.clientId")
      val clientSecret = config.getString("twitter.oauth2.clientSecret")
      
      val tokens = loadPersistedTokens().orElse {
        val accessToken = Try(config.getString("twitter.tokens.accessToken")).toOption
          .filter(_.nonEmpty)
        val refreshToken = Try(config.getString("twitter.tokens.refreshToken")).toOption
          .filter(_.nonEmpty)
        accessToken.map(token => AccessTokens(token, refreshToken))
      }
      
      val oauth2 = OAuth2Credentials(clientId, clientSecret)
      TwitterCredentials(oauth2, tokens)
    }.toEither.left.map(_.getMessage)
  }
  
  private def loadPersistedTokens(): Option[AccessTokens] = {
    val file = new File(TOKENS_FILE)
    if (!file.exists()) return None
    
    Try {
      val content = Source.fromFile(file, "UTF-8").mkString
      decode[PersistedTokens](content).toOption.map { pt =>
        AccessTokens(pt.accessToken, pt.refreshToken)
      }
    }.toOption.flatten
  }
  
  def saveAccessTokens(accessToken: String, refreshToken: Option[String]): Either[String, Unit] = {
    Try {
      val dataDir = new File("data")
      if (!dataDir.exists()) dataDir.mkdirs()
      
      val tokens = PersistedTokens(accessToken, refreshToken)
      val writer = new PrintWriter(new File(TOKENS_FILE), "UTF-8")
      try {
        writer.write(tokens.asJson.spaces2)
      } finally {
        writer.close()
      }
      println(s"Tokens saved to $TOKENS_FILE")
      ()
    }.toEither.left.map(_.getMessage)
  }
}
