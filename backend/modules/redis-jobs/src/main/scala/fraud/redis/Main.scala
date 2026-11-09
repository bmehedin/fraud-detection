package fraud.redis

import scala.concurrent.duration._

import cats.effect.{IO, IOApp}
import cats.syntax.all._
import dev.profunktor.redis4cats.{Redis, RedisCommands}
import dev.profunktor.redis4cats.effect.Log.Stdout._
import dev.profunktor.redis4cats.effects.{SetArg, SetArgs}
import fs2.Stream
import fs2.kafka.{
  Acks,
  AutoOffsetReset,
  ConsumerRecord,
  ConsumerSettings,
  Header,
  Headers,
  KafkaConsumer,
  KafkaProducer,
  ProducerRecord,
  ProducerRecords,
  ProducerSettings,
  commitBatchWithin
}
import io.circe.parser.decode
import io.circe.syntax._

import fraud.domain.Transaction

object Main extends IOApp.Simple {

  val run: IO[Unit] =
    IO.fromEither(Config.parse(sys.env).left.map(new IllegalArgumentException(_))).flatMap(program)

  private def program(config: Config): IO[Unit] = {
    val settings = ConsumerSettings[IO, String, String]
      .withBootstrapServers(config.bootstrapServers)
      .withGroupId(config.groupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withEnableAutoCommit(false)
    val producerSettings = ProducerSettings[IO, String, String]
      .withBootstrapServers(config.bootstrapServers)
      .withAcks(Acks.All)
      .withEnableIdempotence(true)

    val stream = for {
      redis    <- Stream.resource(Redis[IO].utf8(config.redisUri))
      pong     <- Stream.eval(redis.ping)
      _        <- Stream.eval(IO.println(s"redis: $pong"))
      _        <- Stream.eval(Blocklist.seed(redis, config))
      sha      <- Stream.eval(Velocity.load(redis))
      producer <- KafkaProducer.stream(producerSettings)
      offset <- KafkaConsumer
        .stream(settings)
        .subscribeTo(config.topic)
        .records
        .evalMap(committable => handle(redis, producer, config, sha, committable.record).as(committable.offset))
    } yield offset

    stream
      .through(commitBatchWithin[IO](100, 5.seconds))
      .compile
      .drain
  }

  private def handle(
      redis: RedisCommands[IO, String, String],
      producer: KafkaProducer[IO, String, String],
      config: Config,
      velocitySha: String,
      record: ConsumerRecord[String, String]
  ): IO[Unit] = {
    val where = s"p${record.partition} o${record.offset} key=${record.key}"
    decode[Transaction](record.value) match {
      case Right(tx) =>
        List(Blocklist.check(redis, tx), Velocity.check(redis, config, velocitySha, tx)).flatTraverse(_.map(_.toList)).flatMap {
          _.traverse_ { alert =>
            val dedup = s"alert:${alert.dedupKey.value}"
            val out = ProducerRecord(config.alertsTopic, alert.cardId.value, alert.asJson.noSpaces)
            val markSent = SetArgs(SetArg.Existence.Nx, SetArg.Ttl.Ex(config.alertDedupTtl))
            redis.exists(dedup).ifM(
              IO.unit,
              producer.produce(ProducerRecords.one(out)).flatten >>
                redis.set(dedup, alert.triggeredBy.value, markSent) >>
                IO.println(s"$where ${alert.asJson.noSpaces}")
            )
          }
        }
      case Left(error) =>
        val dead = ProducerRecord(config.dlqTopic, record.key, record.value)
          .withHeaders(Headers(Header("error", error.getMessage)))
        producer.produce(ProducerRecords.one(dead)).flatten >>
          IO.consoleForIO.errorln(s"$where sent to ${config.dlqTopic}: ${error.getMessage}")
    }
  }
}
