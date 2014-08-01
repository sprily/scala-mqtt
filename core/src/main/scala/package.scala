package uk.co.sprily

package object mqtt {

  /** default `Seq` is mutable **/
  type Seq[+A] = scala.collection.immutable.Seq[A]
  val Seq = scala.collection.immutable.Seq

}
