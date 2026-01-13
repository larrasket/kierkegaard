name := "kierkegaard-twitter-bot"

version := "0.1.0"

scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  // HTTP client for Twitter API
  "com.softwaremill.sttp.client3" %% "core" % "3.9.1",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.1",
  
  // JSON processing
  "io.circe" %% "circe-core" % "0.14.5",
  "io.circe" %% "circe-generic" % "0.14.5",
  "io.circe" %% "circe-parser" % "0.14.5",
  
  // Functional programming
  "org.typelevel" %% "cats-core" % "2.10.0",
  "org.typelevel" %% "cats-effect" % "3.5.2",
  
  // Configuration
  "com.typesafe" % "config" % "1.4.3",
  
  // NLP for sentence detection and quality scoring
  "org.apache.opennlp" % "opennlp-tools" % "2.3.1",
  
  // XML/HTML parsing for EPUB
  "org.jsoup" % "jsoup" % "1.17.2"
)

scalacOptions ++= Seq(
  "-encoding", "UTF-8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard"
)

// Assembly settings for fat JAR
assembly / mainClass := Some("kierkegaard.twitter.Main")
assembly / assemblyJarName := "kierkegaard-bot.jar"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  case x => MergeStrategy.first
}
