package kierkegaard.twitter.auth

import com.typesafe.config.{Config, ConfigFactory}
import scala.util.Try

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

object TwitterCredentials {
  
  def load(): Either[String, TwitterCredentials] = {
    Try {
      val config: Config = ConfigFactory.load()
      
      val clientId = config.getString("twitter.oauth2.clientId")
      val clientSecret = config.getString("twitter.oauth2.clientSecret")
      
      val accessToken = Try(config.getString("twitter.tokens.accessToken")).toOption
        .filter(_.nonEmpty)
      val refreshToken = Try(config.getString("twitter.tokens.refreshToken")).toOption
        .filter(_.nonEmpty)
      
      val oauth2 = OAuth2Credentials(clientId, clientSecret)
      val tokens = accessToken.map(token => AccessTokens(token, refreshToken))
      
      TwitterCredentials(oauth2, tokens)
    }.toEither.left.map(_.getMessage)
  }
  
  def saveAccessTokens(accessToken: String, refreshToken: Option[String]): Either[String, Unit] = {
    import java.nio.file.{Files, Paths}
    import java.nio.charset.StandardCharsets
    
    Try {
      val configPath = Paths.get("src/main/resources/application.conf")
      val content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8)
      
      val withAccessToken = content.replaceFirst(
        """accessToken = ""[^"]*"""",
        s"""accessToken = "$accessToken""""
      )
      
      val updated = refreshToken match {
        case Some(refresh) => withAccessToken.replaceFirst(
          """refreshToken = ""[^"]*"""",
          s"""refreshToken = "$refresh""""
        )
        case None => withAccessToken
      }
      
      Files.write(configPath, updated.getBytes(StandardCharsets.UTF_8))
      ()
    }.toEither.left.map(_.getMessage)
  }
}
