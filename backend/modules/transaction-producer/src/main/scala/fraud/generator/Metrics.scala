package fraud.generator

import cats.effect.{IO, Resource}
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics

final class Metrics private (events: Counter) {

  val published: IO[Unit] = IO.delay(events.inc())
}

object Metrics {

  def resource(port: Int): Resource[IO, Metrics] =
    for {
      metrics <- Resource.eval(IO.delay {
        JvmMetrics.builder().register()
        new Metrics(Counter.builder().name("producer_events").help("Transactions acknowledged by Kafka").register())
      })
      _ <- Resource.fromAutoCloseable(IO.blocking(HTTPServer.builder().port(port).buildAndStart()))
    } yield metrics
}
