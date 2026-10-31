package fraud.generator

import scala.concurrent.duration._

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {

  val run: IO[Unit] = {
    val (cards, afterCards) = TransactionGen.cards(1000).run(Seed(42))
    TransactionGen
      .stream(cards, afterCards)
      .metered[IO](10.millis)
      .evalMap { case (card, raw) =>
        IO.realTimeInstant.map(at => TransactionGen.toTransaction(card, at, raw))
      }
      .evalMap(IO.println)
      .compile
      .drain
  }

//  val pair: Rand[(Long, Long, Long)] =
//    for {
//      a <- Rand.nextLong
//      b <- Rand.nextLong
//      c <- Rand.nextLong
//    } yield (a, b, c)
//
//  println(pair.run(Seed(42)))
//
//  var seed = Seed(23);
//  while (true) {
//    val (n, next) = Rand.between(0, 4).run(seed)
//    println(n)
//    Thread.sleep(2000)
//    seed = next
//  }
//  for the same seed you will of course get the same randomly generated numbers
//  each generated long value is threaded through as a Seed to generate the next long
//  naturally, using the same seed generated the same number
//  pair has as type (Long, Long, Long) because that is the structure of the yield statement. for more chained operations, scala can infer the type - no need to write it
}
