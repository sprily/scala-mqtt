package uk.co.sprily
package mqtt

final case class Topic(path: String) extends AnyVal {
  def pattern = TopicPattern(path)
}

final case class TopicPattern(path: String) extends AnyVal {

  def matches(t: Topic): Boolean = {
    TopicPattern.matchPaths(this.path.split("/").toList, t.path.split("/").toList)
  }

}

object TopicPattern {

  @annotation.tailrec
  def matchPaths(pattern: List[String], topic: List[String]): Boolean = (pattern, topic) match {
    case (Nil, Nil)                       => true
    case (("+" :: ps), (t :: ts))         => matchPaths(ps, ts)
    case (("#" :: _), _)                  => true
    case ((p :: ps), (t :: ts)) if p == t => matchPaths(ps, ts)
    case _                                => false
  }

}

