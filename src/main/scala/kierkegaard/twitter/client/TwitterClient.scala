package kierkegaard.twitter.client

import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import scala.util.Try

class TwitterClient(accessToken: String) {
  
  private val backend = HttpURLConnectionBackend()
  
  sealed trait TwitterError
  case class PostError(message: String) extends TwitterError
  case class HttpError(statusCode: Int, body: String) extends TwitterError
  
  def tweet(message: String): Either[TwitterError, TweetResponse] = {
    Try {
      val escapedMessage = message.replace("\"", "\\\"").replace("\n", "\\n")
      val jsonBody = s"""{"text":"$escapedMessage"}"""
      
      val request = basicRequest
        .post(uri"https://api.twitter.com/2/tweets")
        .header("Authorization", s"Bearer $accessToken")
        .header("Content-Type", "application/json")
        .body(jsonBody)
        .response(asJson[TweetResponseWrapper])
      
      val response = request.send(backend)
      
      response.body match {
        case Right(wrapper) =>
          Right(wrapper.data)
        case Left(error) =>
          Left(HttpError(response.code.code, error.getMessage))
      }
    }.toEither.left.map {
      case e: TwitterError => e
      case e => PostError(s"Failed to post tweet: ${e.getMessage}")
    }.flatten
  }
  
  def verifyCredentials(): Either[TwitterError, UserInfo] = {
    Try {
      val request = basicRequest
        .get(uri"https://api.twitter.com/2/users/me")
        .header("Authorization", s"Bearer $accessToken")
        .response(asJson[UserResponseWrapper])
      
      val response = request.send(backend)
      
      response.body match {
        case Right(wrapper) =>
          Right(wrapper.data)
        case Left(error) =>
          Left(HttpError(response.code.code, error.getMessage))
      }
    }.toEither.left.map {
      case e: TwitterError => e
      case e => PostError(s"Failed to verify credentials: ${e.getMessage}")
    }.flatten
  }
  
  def getAccountInfo: Either[TwitterError, (String, String)] = {
    verifyCredentials().map { user =>
      (user.username, user.name)
    }
  }
}

object TwitterClient {
  def apply(accessToken: String): TwitterClient = new TwitterClient(accessToken)
}

case class TweetResponse(id: String, text: String)
case class TweetResponseWrapper(data: TweetResponse)

case class UserInfo(id: String, name: String, username: String)
case class UserResponseWrapper(data: UserInfo)
