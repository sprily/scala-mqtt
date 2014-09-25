package uk.co.sprily
package mqtt

import org.scalacheck._
import org.scalacheck.Gen._
import org.scalacheck.Prop.forAll
import org.scalacheck.Arbitrary.arbitrary

object TopicChecks extends Properties("Topic") {


  property("symetric") = forAll { t: Topic =>
    t.pattern matches t
  }

  property("expand '+' wildcard") = forAll(topicWithPlusWildcardsGen) { ts: (TopicPattern, Topic) =>
    ts._1 matches ts._2
  }

  property("expand '#' wildcard") = forAll(topicWithHashWildcardGen) { ts: (TopicPattern, Topic) =>
    ts._1 matches ts._2
  }

  implicit private[this] def arbTopic = Arbitrary(topicGen)

  /** Simple Topics without any wildcards */
  private[this] lazy val topicGen = for {
    parts <- Gen.containerOf1[List, String](validTopicPart)
  } yield (Topic(parts.mkString("/")))

  private[this] lazy val validTopicPart = for {
    s <- Gen.alphaStr
    if s != ""
  } yield s

  private[this] lazy val validTopicPartWithPlusWildcard: Gen[String] = {
    Gen.oneOf(validTopicPart, Gen.oneOf('+', '+').map(_.toString))
  }

  private[this] lazy val topicWithPlusWildcardsGen = for {
    parts <- Gen.containerOf1[List, String](validTopicPartWithPlusWildcard)
    pattern = TopicPattern(parts.mkString("/"))
    topic = Topic(parts.map {
        case "+" => "foobar"
        case s   => s
      }.mkString("/"))
  } yield (pattern, topic)

  private[this] lazy val topicWithHashWildcardGen = for {
    parts <- Gen.containerOf1[List, String](validTopicPart)
    i <- Gen.choose(0, parts.length)
    patternParts = parts.slice(0, i) ++ List("#")
    pattern = TopicPattern(patternParts.mkString("/"))
    topic = Topic(parts.mkString("/"))
  } yield (pattern, topic)

}
