package fraud.redis

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Resource}
import io.prometheus.metrics.core.metrics.{Counter, Histogram}
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics

import fraud.domain.RuleId

final class Metrics private (events: Counter, alerts: Counter, checkDuration: Histogram) {

  def event(result: String): IO[Unit] = IO.delay(events.labelValues(result).inc())

  def alert(rule: RuleId): IO[Unit] = IO.delay(alerts.labelValues(rule.toString).inc())

  def checked(duration: FiniteDuration): IO[Unit] = IO.delay(checkDuration.observe(duration.toNanos / 1e9))
}

object Metrics {

  def resource(port: Int): Resource[IO, Metrics] =
    for {
      metrics <- Resource.eval(IO.delay {
        JvmMetrics.builder().register()
        new Metrics(
          Counter.builder().name("redis_jobs_events").help("Transactions consumed").labelNames("result").register(),
          Counter.builder().name("redis_jobs_alerts").help("Alerts produced").labelNames("rule").register(),
          Histogram
            .builder()
            .name("redis_jobs_check_duration_seconds")
            .help("Time to run the fast-path rules for one transaction")
            .classicOnly()
            .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1)
            .register()
        )
      })
      _ <- Resource.fromAutoCloseable(IO.blocking(HTTPServer.builder().port(port).buildAndStart()))
    } yield metrics
}
