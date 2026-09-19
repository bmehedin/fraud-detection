package fraud.generator

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {

  private val hi: IO[Unit] = IO(println("hi"))
  val run: IO[Unit] = hi

  val pair: Rand[(Long, Long)] =
    for {
      a <- Rand.nextLong
      b <- Rand.nextLong
    } yield (a, b)

  println(pair.run(Seed(42))._1)

  /*
  * why does println(pair.run(Seed(42))._1) return (Long, Long)

let's unpack this entirely
* for comprehension threads the seed from a to b
*  to generate two Long numbers. but why is the type of pair Rand[(Long, Long)]
  *
  *
  *resume last conversation
  *
  * */
}
