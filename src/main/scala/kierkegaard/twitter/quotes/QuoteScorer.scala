package kierkegaard.twitter.quotes

import opennlp.tools.sentdetect.{SentenceDetectorME, SentenceModel}
import opennlp.tools.tokenize.{TokenizerME, TokenizerModel}
import opennlp.tools.postag.{POSTaggerME, POSModel}
import scala.util.Try
import java.io.InputStream

class QuoteScorer {
  
  private lazy val sentenceDetector: Option[SentenceDetectorME] = loadSentenceModel()
  private lazy val tokenizer: Option[TokenizerME] = loadTokenizerModel()
  private lazy val posTagger: Option[POSTaggerME] = loadPOSModel()
  
  private def loadSentenceModel(): Option[SentenceDetectorME] = {
    Try {
      val modelIn: InputStream = getClass.getResourceAsStream("/en-sent.bin")
      if (modelIn != null) {
        val model = new SentenceModel(modelIn)
        modelIn.close()
        Some(new SentenceDetectorME(model))
      } else None
    }.getOrElse(None)
  }
  
  private def loadTokenizerModel(): Option[TokenizerME] = {
    Try {
      val modelIn: InputStream = getClass.getResourceAsStream("/en-token.bin")
      if (modelIn != null) {
        val model = new TokenizerModel(modelIn)
        modelIn.close()
        Some(new TokenizerME(model))
      } else None
    }.getOrElse(None)
  }
  
  private def loadPOSModel(): Option[POSTaggerME] = {
    Try {
      val modelIn: InputStream = getClass.getResourceAsStream("/en-pos-maxent.bin")
      if (modelIn != null) {
        val model = new POSModel(modelIn)
        modelIn.close()
        Some(new POSTaggerME(model))
      } else None
    }.getOrElse(None)
  }
  
  private val philosophicalKeywords = Set(
    "soul", "heart", "love", "despair", "anxiety", "freedom", "faith",
    "existence", "truth", "self", "dread", "passion", "eternity", "infinite",
    "spirit", "silence", "god", "death", "suffering", "joy", "melancholy",
    "being", "life", "world", "time", "moment", "choice", "decision",
    
    "individual", "paradox", "absurd", "leap", "fear", "trembling",
    "knight", "aesthetic", "ethical", "religious", "sickness",
    "consciousness", "inwardness", "subjectivity", "irony", "repetition",
    "angst", "existential", "authentic", "inauthenticity", "finitude",
    
    "longing", "yearning", "solitude", "loneliness", "sorrow", "grief",
    "hope", "hopelessness", "anguish", "torment", "agony", "ecstasy",
    "happiness", "unhappiness", "contentment", "restlessness", "unrest",
    "peace", "serenity", "tranquility", "turmoil", "confusion", "clarity",
    
    "prayer", "salvation", "sin", "grace", "mercy", "redemption", "guilt",
    "forgiveness", "repentance", "confession", "humility", "pride", "vanity",
    "devotion", "worship", "sacred", "holy", "divine", "transcendent",
    "eternal", "immortal", "mortal", "finite", "infinite", "absolute",
    
    "meaning", "purpose", "destiny", "fate", "necessity", "possibility",
    "actuality", "reality", "illusion", "appearance", "essence", "substance",
    "becoming", "nothing", "nothingness", "void", "abyss", "depth",
    "height", "ground", "foundation", "origin", "beginning", "end",
    
    "weakness", "strength", "courage", "cowardice", "resolve", "determination",
    "temptation", "seduction", "corruption", "purity", "innocence", "experience",
    "wisdom", "folly", "madness", "sanity", "reason", "unreason",
    "certainty", "uncertainty", "doubt", "belief", "unbelief", "conviction",
    
    "beloved", "lover", "friendship", "enmity", "hatred", "jealousy",
    "devotion", "betrayal", "loyalty", "abandonment", "attachment", "detachment",
    "intimacy", "distance", "closeness", "separation", "union", "communion",
    
    "fleeting", "transient", "ephemeral", "permanent", "lasting", "enduring",
    "memory", "forgetfulness", "remembrance", "anticipation", "expectation",
    "present", "past", "future", "instantaneous", "sudden", "gradual",
    
    "thought", "thinking", "reflection", "contemplation", "meditation",
    "dream", "imagination", "fantasy", "vision", "insight", "intuition",
    "feeling", "emotion", "sensation", "perception", "awareness",
    "secret", "mystery", "hidden", "revealed", "concealed", "manifest"
  )
  
  private val poeticMarkers = Set(
    "like", "as if", "as though", "becomes", "transforms", "awakens",
    "emerges", "reveals", "conceals", "infinite", "eternal", "abyss",
    "wound", "flower", "path", "journey", "darkness", "light", "shadow",
    "mirror", "reflection", "echo", "whisper", "cry", "voice",
    "blood", "tears", "breath", "fire", "water", "earth", "wind",
    "night", "day", "dawn", "dusk", "twilight", "midnight",
    "spring", "winter", "storm", "calm", "wave", "ocean", "sea",
    "mountain", "valley", "forest", "desert", "wilderness", "garden",
    "bird", "flight", "fall", "rise", "soar", "descend", "ascend",
    "chain", "prison", "cage", "liberation", "escape", "return"
  )
  
  // Rejection patterns 
  private val rejectionPatterns = Seq(
    "\\[\\d+\\]".r,           // Footnote markers [1], [2]
    "^\\d+\\.".r,             // Numbered lists
    "^chapter".r,             // Chapter headings (case insensitive handled below)
    "^page \\d+".r,           // Page numbers
    "^[ivxlc]+\\.".r,         // Roman numeral lists
    "copyright".r,
    "project gutenberg".r,
    "translator".r,
    "introduction".r,
    "footnote".r,
    "\\bpp\\.".r,             // Page references
    "\\bvol\\.".r,            // Volume references
    "\\bsee\\b".r,            // Cross-references
    "\\bcf\\.".r              // Cross-references
  )
  
  def shouldReject(text: String): Boolean = {
    val lower = text.toLowerCase.trim
    
    // Too short or too long
    if (text.length < 10 || text.length > 280) return true
    
    // Contains obvious metadata markers
    if (rejectionPatterns.exists(_.findFirstIn(lower).isDefined)) return true
    
    // Starts with lowercase (likely fragment)
    if (text.trim.headOption.exists(c => c.isLetter && c.isLower)) return true
    
    // Contains too many numbers (likely dates/references)
    val digitRatio = text.count(_.isDigit).toDouble / text.length
    if (digitRatio > 0.1) return true
    
    // Doesn't end properly
    if (!text.trim.endsWith(".") && !text.trim.endsWith("!") && 
        !text.trim.endsWith("?") && !text.trim.endsWith("—")) return true
    
    false
  }
  
  def score(text: String): Int = {
    if (shouldReject(text)) return 0
    
    val lower = text.toLowerCase
    var totalScore = 0.0
    
    // 1. Length score (25 points) - FAVOR SHORTER quotes (60-150 chars is ideal)
    val lengthScore = text.length match {
      case l if l < 50 => 8
      case l if l < 80 => 20        // Short and punchy
      case l if l <= 120 => 25      // Ideal tweet length
      case l if l <= 150 => 22      // Still good
      case l if l <= 200 => 15      // Medium
      case l if l <= 240 => 10      // Getting long
      case _ => 5                   // Too long
    }
    totalScore += lengthScore
    
    // 2. Philosophical keywords (25 points)
    val keywordCount = philosophicalKeywords.count(kw => lower.contains(kw))
    val keywordScore = math.min(25, keywordCount * 4)  
    totalScore += keywordScore
    
    // 3. Poetic markers (15 points)
    val poeticCount = poeticMarkers.count(m => lower.contains(m))
    val poeticScore = math.min(15, poeticCount * 4)
    totalScore += poeticScore
    
    // 4. Rhetorical patterns (15 points)
    var rhetoricalScore = 0
    if (text.contains(";")) rhetoricalScore += 5          // Semicolons (balanced sentences)
    if (text.contains("—")) rhetoricalScore += 5          // Em-dashes (dramatic pauses)
    if (text.contains(":")) rhetoricalScore += 3          // Colons (explanations)
    if (text.startsWith("What ") || text.startsWith("Why ")) rhetoricalScore += 4  // Questions
    if (text.contains("...")) rhetoricalScore += 3        // Ellipsis (contemplative)
    totalScore += math.min(15, rhetoricalScore)
    
    // 5. Sentence structure (15 points) - using POS if available, else heuristics
    val structureScore = scoreStructure(text)
    totalScore += structureScore
    
    // Bonus: First-person statements (often more personal/profound) +5
    if (text.startsWith("I ") || lower.contains(" i am ") || lower.contains(" i have ")) {
      totalScore += 5
    }
    
    // Bonus: Universal statements +5
    if (text.startsWith("One ") || text.startsWith("The ") || text.startsWith("Life ")) {
      totalScore += 5
    }
    
    // Bonus: Aphoristic quality - short with keywords is extra good
    if (text.length <= 120 && keywordCount >= 2) {
      totalScore += 8
    }
    
    math.min(100, totalScore.toInt)
  }
  
  private def scoreStructure(text: String): Int = {
    var score = 10 
    
    posTagger match {
      case Some(tagger) =>
        tokenizer match {
          case Some(tok) =>
            val tokens = tok.tokenize(text)
            val tags = tagger.tag(tokens)
            
            if (tags.exists(t => t.startsWith("VB"))) score += 5
            
            if (tags.exists(t => t.startsWith("NN"))) score += 3
            
            if (tokens.length > 8 && tokens.length < 30) score += 2
            
          case None => score += heuristicStructureScore(text)
        }
      case None => score += heuristicStructureScore(text)
    }
    
    math.min(15, score)
  }
  
  private def heuristicStructureScore(text: String): Int = {
    var score = 0
    val words = text.split("\\s+")
    
    if (words.length >= 5 && words.length <= 40) score += 3
    
    val verbs = Set("is", "are", "was", "were", "be", "being", "been",
                    "have", "has", "had", "do", "does", "did", "can", "could",
                    "will", "would", "shall", "should", "may", "might", "must")
    if (words.map(_.toLowerCase).exists(verbs.contains)) score += 4
    
    if (words.map(_.toLowerCase).exists(w => w == "the" || w == "a" || w == "an")) score += 3
    
    score
  }
  
  def extractAndScore(text: String): Seq[ScoredQuote] = {
    val sentences = sentenceDetector match {
      case Some(detector) => detector.sentDetect(text).toSeq
      case None => fallbackSentenceSplit(text)
    }
    
    sentences
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(s => ScoredQuote(s, score(s)))
      .filter(_.score > 0)
      .sortBy(-_.score)
  }
  
  private def fallbackSentenceSplit(text: String): Seq[String] = {
    // Split on sentence-ending punctuation followed by space and capital
    text.split("(?<=[.!?])\\s+(?=[A-Z])").toSeq
  }
}

case class ScoredQuote(text: String, score: Int, source: String = "", posted: Boolean = false)

object QuoteScorer {
  def apply(): QuoteScorer = new QuoteScorer()
}
