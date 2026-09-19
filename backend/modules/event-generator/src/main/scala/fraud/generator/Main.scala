package fraud.generator

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {

  private val hi: IO[Unit] = IO(println("hi"))
  val run: IO[Unit] = hi

  val pair: Rand[(Long, Long, Long)] =
    for {
      a <- Rand.nextLong
      b <- Rand.nextLong
      c <- Rand.nextLong
    } yield (a, b, c)

  println(pair.run(Seed(42)))
//  for the same seed you will of course get the same randomly generated numbers
//  each generated long value is threaded through as a Seed to generate the next long
//  naturally, using the same seed generated the same number
//  pair has as type (Long, Long, Long) because that is the structure of the yield statement. for more chained operations, scala can infer the type - no need to write it
}
