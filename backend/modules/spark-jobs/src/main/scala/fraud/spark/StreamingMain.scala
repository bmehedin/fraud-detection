package fraud.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, concat_ws, floor, from_json, lit, struct, to_date, to_json, unix_millis}
import org.apache.spark.sql.streaming.{OutputMode, TimeMode, Trigger}
import org.apache.spark.sql.types._

object StreamingMain {

  val transactionSchema: StructType = StructType(
    Seq(
      StructField("transactionId", StringType),
      StructField("cardId", StringType),
      StructField("merchantId", StringType),
      StructField("merchantCategory", StringType),
      StructField("amountMinor", LongType),
      StructField(
        "origin",
        StructType(
          Seq(
            StructField("type", StringType),
            StructField(
              "point",
              StructType(
                Seq(
                  StructField("lat", DoubleType),
                  StructField("lon", DoubleType),
                  StructField("country", StringType)
                )
              )
            ),
            StructField("ip", StringType)
          )
        )
      ),
      StructField("eventTime", TimestampType)
    )
  )

  def main(args: Array[String]): Unit = {
    val config = Config.parse(sys.env)

    val spark = SparkSession
      .builder()
      .appName("fraud-streaming")
      .config(
        "spark.sql.streaming.stateStore.providerClass",
        "org.apache.spark.sql.execution.streaming.state.RocksDBStateStoreProvider"
      )
      .config("spark.sql.session.timeZone", "UTC")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    val transactions = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.bootstrapServers)
      .option("subscribe", config.topic)
      .option("startingOffsets", config.startingOffsets)
      .load()
      .select(col("partition"), col("offset"), from_json(col("value").cast("string"), transactionSchema).as("tx"))
      .select("partition", "offset", "tx.*")
      .withWatermark("eventTime", config.watermarkDelay)

    import spark.implicits._

    val travelHits = transactions
      .filter(col("origin.type") === "Physical")
      .select(
        col("cardId"),
        col("transactionId"),
        col("eventTime"),
        struct(col("origin.point.lat"), col("origin.point.lon"), col("origin.point.country")).as("point")
      )
      .as[Visit]
      .groupByKey(_.cardId)
      .transformWithState(new ImpossibleTravelProcessor(config.maxKmh), TimeMode.EventTime(), OutputMode.Append())

    val alerts = travelHits.select(
      col("cardId").as("key"),
      to_json(
        struct(
          concat_ws(":", col("cardId"), lit("S2"), floor(unix_millis(col("detectedAt")) / 300000)).as("dedupKey"),
          col("cardId"),
          col("triggeredBy"),
          col("detectedAt"),
          struct(lit("ImpossibleTravel").as("type"), col("from"), col("to"), col("kmh")).as("evidence")
        )
      ).as("value")
    )

    alerts.writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.bootstrapServers)
      .option("topic", config.alertsTopic)
      .option("checkpointLocation", config.checkpointDir)
      .start()

    transactions
      .withColumn("date", to_date(col("eventTime")))
      .writeStream
      .format("parquet")
      .partitionBy("date")
      .option("path", config.parquetDir)
      .option("checkpointLocation", config.parquetCheckpointDir)
      .trigger(Trigger.ProcessingTime(config.parquetTrigger))
      .start()

    spark.streams.awaitAnyTermination()
  }
}
