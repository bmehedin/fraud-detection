package fraud.consumer

import scala.concurrent.duration._

import cats.effect.{IO, IOApp}
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

import fraud.domain.Transaction

object Main extends IOApp.Simple {

  val run: IO[Unit] = {
    val config = Config.parse(sys.env)
    val settings = ConsumerSettings[IO, String, String]
      .withBootstrapServers(config.bootstrapServers)
      .withGroupId(config.groupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withEnableAutoCommit(false)
    val dlqSettings = ProducerSettings[IO, String, String]
      .withBootstrapServers(config.bootstrapServers)
      .withAcks(Acks.All)
      .withEnableIdempotence(true)

    KafkaProducer
      .stream(dlqSettings)
      .flatMap { dlq =>
        KafkaConsumer
          .stream(settings)
          .subscribeTo(config.topic)
          .records
          .evalMap(committable => handle(dlq, config.dlqTopic, committable.record).as(committable.offset))
          .through(commitBatchWithin[IO](100, 5.seconds))
      }
      .compile
      .drain
  }

  private def handle(
      dlq: KafkaProducer[IO, String, String],
      dlqTopic: String,
      record: ConsumerRecord[String, String]
  ): IO[Unit] = {
    val where = s"p${record.partition} o${record.offset} key=${record.key}"
    decode[Transaction](record.value) match {
      case Right(tx) => IO.println(s"$where $tx")
      case Left(error) =>
        val dead = ProducerRecord(dlqTopic, record.key, record.value)
          .withHeaders(Headers(Header("error", error.getMessage)))
        dlq.produce(ProducerRecords.one(dead)).flatten >>
          IO.consoleForIO.errorln(s"$where sent to $dlqTopic: ${error.getMessage}")
    }
  }
}
