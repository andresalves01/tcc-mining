package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Sanity checks that the local dev environment is wired up correctly: Spark starts with the
 * configured toolchain/dependencies, and the Mongo Spark connector can read/write against the
 * same MongoDB instance App.java targets.
 */
class AppSanityTest {

  private static final String DB_NAME = "smartshark_2_2";
  /** Scratch writes go to a throwaway database -- never into the 481 GB source corpus. */
  private static final String WRITE_DB_NAME = "smartshark_tcc_test";
  private static final String MONGO_URI = "mongodb://127.0.0.1:27017";
  private static final String TEST_COLLECTION = "app_sanity_test_" + System.nanoTime();

  private static SparkSession spark;

  @BeforeAll
  static void startSpark() {
    spark = SparkSession.builder().appName("AppSanityTest").master("local[*]")
        .config("spark.mongodb.read.connection.uri", MONGO_URI)
        .config("spark.mongodb.read.database", DB_NAME)
        .config("spark.mongodb.write.connection.uri", MONGO_URI)
        .config("spark.mongodb.write.database", WRITE_DB_NAME)
        .getOrCreate();
  }

  @AfterAll
  static void stopSpark() {
    try (final MongoClient client = MongoClients.create(MONGO_URI)) {
      client.getDatabase(WRITE_DB_NAME).getCollection(TEST_COLLECTION).drop();
    } finally {
      if (spark != null) {
        spark.close();
      }
    }
  }

  @Test
  void sparkSessionStartsAndRunsLocalJob() {
    assertNotNull(spark);
    final Dataset<Row> df = spark.range(5).toDF("n");
    assertEquals(5, df.count());
  }

  @Test
  void mongoConnectorRoundTripsThroughSpark() {
    final StructType schema = DataTypes.createStructType(new StructField[] {
        DataTypes.createStructField("id", DataTypes.IntegerType, false),
        DataTypes.createStructField("label", DataTypes.StringType, false)
    });
    final List<Row> rows = Arrays.asList(
        RowFactory.create(1, "alpha"),
        RowFactory.create(2, "beta"));
    final Dataset<Row> input = spark.createDataFrame(rows, schema);

    input.write().format("mongodb")
        .option("database", WRITE_DB_NAME)
        .option("collection", TEST_COLLECTION)
        .mode("overwrite")
        .save();

    final Dataset<Row> output = spark.read().format("mongodb")
        .option("database", WRITE_DB_NAME)
        .option("collection", TEST_COLLECTION)
        .load();

    assertEquals(2, output.count());
  }
}
