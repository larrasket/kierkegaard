package kierkegaard.twitter.auth

import io.circe._
import io.circe.parser._
import io.circe.generic.auto._
import sttp.client3._
import sttp.client3.circe._
import scala.util.Try
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

object TwitterAuth {
  
  sealed trait AuthError
  case class ConfigError(message: String) extends AuthError
  case class OAuthError(message: String) extends AuthError
  case class HttpError(statusCode: Int, body: String) extends AuthError
  
  private val backend = HttpURLConnectionBackend()
  
  private val AUTHORIZE_URL = "https://twitter.com/i/oauth2/authorize"
  private val TOKEN_URL = "https://api.twitter.com/2/oauth2/token"
  
  def generatePKCE(): (String, String) = {
    val random = new SecureRandom()
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    
    val codeVerifier = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8))
    val codeChallenge = Base64.getUrlEncoder.withoutPadding.encodeToString(hash)
    
    (codeVerifier, codeChallenge)
  }
  
  def getAuthorizationUrl(
    credentials: OAuth2Credentials,
    redirectUri: String = "http://127.0.0.1:8080/callback",
    codeChallenge: String
  ): String = {
    val scopes = List("tweet.read", "tweet.write", "users.read", "offline.access").mkString(" ")
    
    val params = Map(
      "response_type" -> "code",
      "client_id" -> credentials.clientId,
      "redirect_uri" -> redirectUri,
      "scope" -> scopes,
      "state" -> "state",
      "code_challenge" -> codeChallenge,
      "code_challenge_method" -> "S256"
    )
    
    val queryString = params.map { case (k, v) =>
      s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }.mkString("&")
    
    s"$AUTHORIZE_URL?$queryString"
  }
  
  def getAccessToken(
    credentials: OAuth2Credentials,
    code: String,
    codeVerifier: String,
    redirectUri: String = "http://127.0.0.1:8080/callback"
  ): Either[AuthError, TokenResponse] = {
    Try {
      val authString = s"${credentials.clientId}:${credentials.clientSecret}"
      val encodedAuth = Base64.getEncoder.encodeToString(authString.getBytes(StandardCharsets.UTF_8))
      
      val request = basicRequest
        .post(uri"$TOKEN_URL")
        .header("Authorization", s"Basic $encodedAuth")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(Map(
          "grant_type" -> "authorization_code",
          "code" -> code,
          "redirect_uri" -> redirectUri,
          "code_verifier" -> codeVerifier
        ))
      
      val response = request.send(backend)
      
      response.body match {
        case Right(body) =>
          decode[TokenResponse](body) match {
            case Right(tokenResp) => Right(tokenResp)
            case Left(e) => Left(OAuthError(s"Failed to parse token response: ${e.getMessage}"))
          }
        case Left(error) =>
          Left(HttpError(response.code.code, error))
      }
    }.toEither.left.map {
      case e: AuthError => e
      case e => OAuthError(s"Failed to get access token: ${e.getMessage}")
    }.flatten
  }
  
  def refreshAccessToken(
    credentials: OAuth2Credentials,
    refreshToken: String
  ): Either[AuthError, TokenResponse] = {
    Try {
      val authString = s"${credentials.clientId}:${credentials.clientSecret}"
      val encodedAuth = Base64.getEncoder.encodeToString(authString.getBytes(StandardCharsets.UTF_8))
      
      val request = basicRequest
        .post(uri"$TOKEN_URL")
        .header("Authorization", s"Basic $encodedAuth")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(Map(
          "grant_type" -> "refresh_token",
          "refresh_token" -> refreshToken
        ))
      
      val response = request.send(backend)
      
      response.body match {
        case Right(body) =>
          decode[TokenResponse](body) match {
            case Right(tokenResp) => Right(tokenResp)
            case Left(e) => Left(OAuthError(s"Failed to parse token response: ${e.getMessage}"))
          }
        case Left(error) =>
          Left(HttpError(response.code.code, error))
      }
    }.toEither.left.map {
      case e: AuthError => e
      case e => OAuthError(s"Failed to refresh token: ${e.getMessage}")
    }.flatten
  }
}

case class TokenResponse(
  access_token: String,
  token_type: String,
  expires_in: Option[Int],
  refresh_token: Option[String],
  scope: Option[String]
)
