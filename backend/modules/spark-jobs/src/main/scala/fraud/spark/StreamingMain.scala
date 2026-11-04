package fraud.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, from_json, struct}
import org.apache.spark.sql.streaming.{OutputMode, TimeMode}
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

    travelHits.writeStream
      .format("console")
      .option("truncate", "false")
      .option("checkpointLocation", config.checkpointDir)
      .start()
      .awaitTermination()
  }
}
