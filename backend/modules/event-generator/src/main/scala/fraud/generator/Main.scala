package fraud.generator

import java.time.Instant

import scala.concurrent.duration._

import cats.effect.{IO, IOApp}
import fs2.io.file.{Files, Path}

object Main extends IOApp.Simple {

  val run: IO[Unit] =
    Config.parse(sys.env) match {
      case Left(error)   => IO.raiseError(new IllegalArgumentException(error))
      case Right(config) => generate(config)
    }

  private def generate(config: Config): IO[Unit] = {
    val (cards, afterCards) = TransactionGen.cards(config.cards).run(Seed(config.seed.toLong))
    val lines = TransactionGen
      .stream(cards, afterCards, config.fraudPercent)
      .metered[IO](1.second / config.rate.toLong)
      .evalMap(g => IO.realTimeInstant.map(at => show(g, at)))

    config.output match {
      case "stdout" => lines.evalMap(IO.println).compile.drain
      case path     => lines.through(Files[IO].writeUtf8Lines(Path(path))).compile.drain
    }
  }

  private def show(g: Generated, at: Instant): String =
    s"${g.fraud.fold("normal")(_.toString)} ${TransactionGen.toTransaction(g.card, at, g.tx)}"

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
