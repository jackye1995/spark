/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.adaptive.{DisableAdaptiveExecutionSuite, EnableAdaptiveExecutionSuite}
import org.apache.spark.sql.execution.datasources.SaveIntoDataSourceCommand
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.TestOptionsSource
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}

trait ExplainSuiteHelper extends QueryTest with SharedSparkSession {

  protected def getNormalizedExplain(df: DataFrame, mode: ExplainMode): String = {
    val output = new java.io.ByteArrayOutputStream()
    Console.withOut(output) {
      df.explain(mode.name)
    }
    output.toString.replaceAll("#\\d+", "#x")
  }

  /**
   * Get the explain from a DataFrame and run the specified action on it.
   */
  protected def withNormalizedExplain(df: DataFrame, mode: ExplainMode)(f: String => Unit) = {
    f(getNormalizedExplain(df, mode))
  }

  /**
   * Get the explain by running the sql. The explain mode should be part of the
   * sql text itself.
   */
  protected def withNormalizedExplain(queryText: String)(f: String => Unit) = {
    val output = new java.io.ByteArrayOutputStream()
    Console.withOut(output) {
      sql(queryText).show(false)
    }
    val normalizedOutput = output.toString.replaceAll("#\\d+", "#x")
    f(normalizedOutput)
  }

  /**
   * Runs the plan and makes sure the plans contains all of the keywords.
   */
  protected def checkKeywordsExistsInExplain(
      df: DataFrame, mode: ExplainMode, keywords: String*): Unit = {
    withNormalizedExplain(df, mode) { normalizedOutput =>
      for (key <- keywords) {
        assert(normalizedOutput.contains(key))
      }
    }
  }

  protected def checkKeywordsExistsInExplain(df: DataFrame, keywords: String*): Unit = {
    checkKeywordsExistsInExplain(df, ExtendedMode, keywords: _*)
  }

  /**
   * Runs the plan and makes sure the plans does not contain any of the keywords.
   */
  protected def checkKeywordsNotExistsInExplain(
      df: DataFrame, mode: ExplainMode, keywords: String*): Unit = {
    withNormalizedExplain(df, mode) { normalizedOutput =>
      for (key <- keywords) {
        assert(!normalizedOutput.contains(key))
      }
    }
  }
}

class ExplainSuite extends ExplainSuiteHelper with DisableAdaptiveExecutionSuite {
  import testImplicits._

  test("SPARK-23034 show rdd names in RDD scan nodes (Dataset)") {
    val rddWithName = spark.sparkContext.parallelize(Row(1, "abc") :: Nil).setName("testRdd")
    val df = spark.createDataFrame(rddWithName, StructType.fromDDL("c0 int, c1 string"))
    checkKeywordsExistsInExplain(df, keywords = "Scan ExistingRDD testRdd")
  }

  test("SPARK-23034 show rdd names in RDD scan nodes (DataFrame)") {
    val rddWithName = spark.sparkContext.parallelize(ExplainSingleData(1) :: Nil).setName("testRdd")
    val df = spark.createDataFrame(rddWithName)
    checkKeywordsExistsInExplain(df, keywords = "Scan testRdd")
  }

  test("SPARK-24850 InMemoryRelation string representation does not include cached plan") {
    val df = Seq(1).toDF("a").cache()
    checkKeywordsExistsInExplain(df,
      keywords = "InMemoryRelation", "StorageLevel(disk, memory, deserialized, 1 replicas)")
  }

  test("optimized plan should show the rewritten aggregate expression") {
    withTempView("test_agg") {
      sql(
        """
          |CREATE TEMPORARY VIEW test_agg AS SELECT * FROM VALUES
          |  (1, true), (1, false),
          |  (2, true),
          |  (3, false), (3, null),
          |  (4, null), (4, null),
          |  (5, null), (5, true), (5, false) AS test_agg(k, v)
        """.stripMargin)

      // simple explain of queries having every/some/any aggregates. Optimized
      // plan should show the rewritten aggregate expression.
      val df = sql("SELECT k, every(v), some(v), any(v) FROM test_agg GROUP BY k")
      checkKeywordsExistsInExplain(df,
        "Aggregate [k#x], [k#x, every(v#x) AS every(v)#x, some(v#x) AS some(v)#x, " +
          "any(v#x) AS any(v)#x]")
    }
  }

  test("explain inline tables cross-joins") {
    val df = sql(
      """
        |SELECT * FROM VALUES ('one', 1), ('three', null)
        |  CROSS JOIN VALUES ('one', 1), ('three', null)
      """.stripMargin)
    checkKeywordsExistsInExplain(df,
      "Join Cross",
      ":- LocalRelation [col1#x, col2#x]",
      "+- LocalRelation [col1#x, col2#x]")
  }

  test("explain table valued functions") {
    checkKeywordsExistsInExplain(sql("select * from RaNgE(2)"), "Range (0, 2, step=1, splits=None)")
    checkKeywordsExistsInExplain(sql("SELECT * FROM range(3) CROSS JOIN range(3)"),
      "Join Cross",
      ":- Range (0, 3, step=1, splits=None)",
      "+- Range (0, 3, step=1, splits=None)")
  }

  test("explain lateral joins") {
    checkKeywordsExistsInExplain(
      sql("SELECT * FROM VALUES (0, 1) AS (a, b), LATERAL (SELECT a)"),
      "LateralJoin lateral-subquery#x [a#x], Inner",
      "Project [outer(a#x) AS a#x]"
    )
  }

  test("explain string functions") {
    // Check if catalyst combine nested `Concat`s
    val df1 = sql(
      """
        |SELECT (col1 || col2 || col3 || col4) col
        |  FROM (SELECT id col1, id col2, id col3, id col4 FROM range(10))
      """.stripMargin)
    checkKeywordsExistsInExplain(df1,
      "Project [concat(cast(id#xL as string), cast(id#xL as string), cast(id#xL as string)" +
        ", cast(id#xL as string)) AS col#x]")

    // Check if catalyst combine nested `Concat`s if concatBinaryAsString=false
    withSQLConf(SQLConf.CONCAT_BINARY_AS_STRING.key -> "false") {
      val df2 = sql(
        """
          |SELECT ((col1 || col2) || (col3 || col4)) col
          |FROM (
          |  SELECT
          |    string(id) col1,
          |    string(id + 1) col2,
          |    encode(string(id + 2), 'utf-8') col3,
          |    encode(string(id + 3), 'utf-8') col4
          |  FROM range(10)
          |)
        """.stripMargin)
      checkKeywordsExistsInExplain(df2,
        "Project [concat(cast(id#xL as string), cast((id#xL + 1) as string), " +
          "cast(encode(cast((id#xL + 2) as string), utf-8) as string), " +
          "cast(encode(cast((id#xL + 3) as string), utf-8) as string)) AS col#x]")

      val df3 = sql(
        """
          |SELECT (col1 || (col3 || col4)) col
          |FROM (
          |  SELECT
          |    string(id) col1,
          |    encode(string(id + 2), 'utf-8') col3,
          |    encode(string(id + 3), 'utf-8') col4
          |  FROM range(10)
          |)
        """.stripMargin)
      checkKeywordsExistsInExplain(df3,
        "Project [concat(cast(id#xL as string), " +
          "cast(encode(cast((id#xL + 2) as string), utf-8) as string), " +
          "cast(encode(cast((id#xL + 3) as string), utf-8) as string)) AS col#x]")
    }
  }

  test("check operator precedence") {
    // We follow Oracle operator precedence in the table below that lists the levels
    // of precedence among SQL operators from high to low:
    // ---------------------------------------------------------------------------------------
    // Operator                                          Operation
    // ---------------------------------------------------------------------------------------
    // +, -                                              identity, negation
    // *, /                                              multiplication, division
    // +, -, ||                                          addition, subtraction, concatenation
    // =, !=, <, >, <=, >=, IS NULL, LIKE, BETWEEN, IN   comparison
    // NOT                                               exponentiation, logical negation
    // AND                                               conjunction
    // OR                                                disjunction
    // ---------------------------------------------------------------------------------------
    checkKeywordsExistsInExplain(sql("select 'a' || 1 + 2"),
      "Project [null AS (concat(a, 1) + 2)#x]")
    checkKeywordsExistsInExplain(sql("select 1 - 2 || 'b'"),
      "Project [-1b AS concat((1 - 2), b)#x]")
    checkKeywordsExistsInExplain(sql("select 2 * 4  + 3 || 'b'"),
      "Project [11b AS concat(((2 * 4) + 3), b)#x]")
    checkKeywordsExistsInExplain(sql("select 3 + 1 || 'a' || 4 / 2"),
      "Project [4a2.0 AS concat(concat((3 + 1), a), (4 / 2))#x]")
    checkKeywordsExistsInExplain(sql("select 1 == 1 OR 'a' || 'b' ==  'ab'"),
      "Project [true AS ((1 = 1) OR (concat(a, b) = ab))#x]")
    checkKeywordsExistsInExplain(sql("select 'a' || 'c' == 'ac' AND 2 == 3"),
      "Project [false AS ((concat(a, c) = ac) AND (2 = 3))#x]")
  }

  test("explain for these functions; use range to avoid constant folding") {
    val df = sql("select ifnull(id, 'x'), nullif(id, 'x'), nvl(id, 'x'), nvl2(id, 'x', 'y') " +
      "from range(2)")
    checkKeywordsExistsInExplain(df,
      "Project [coalesce(cast(id#xL as string), x) AS ifnull(id, x)#x, " +
        "id#xL AS nullif(id, x)#xL, coalesce(cast(id#xL as string), x) AS nvl(id, x)#x, " +
        "x AS nvl2(id, x, y)#x]")
  }

  test("SPARK-26659: explain of DataWritingCommandExec should not contain duplicate cmd.nodeName") {
    withTable("temptable") {
      val df = sql("create table temptable using parquet as select * from range(2)")
      withNormalizedExplain(df, SimpleMode) { normalizedOutput =>
        assert("Create\\w*?TableAsSelectCommand".r.findAllMatchIn(normalizedOutput).length == 1)
      }
    }
  }

  test("SPARK-33853: explain codegen - check presence of subquery") {
    withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
      withTempView("df") {
        val df1 = spark.range(1, 100)
        df1.createTempView("df")

        val sqlText = "EXPLAIN CODEGEN SELECT (SELECT min(id) FROM df)"
        val expectedText = "Found 3 WholeStageCodegen subtrees."

        withNormalizedExplain(sqlText) { normalizedOutput =>
          assert(normalizedOutput.contains(expectedText))
        }
      }
    }
  }

  test("explain formatted - check presence of subquery in case of DPP") {
    withTable("df1", "df2") {
      withSQLConf(SQLConf.DYNAMIC_PARTITION_PRUNING_ENABLED.key -> "true",
        SQLConf.DYNAMIC_PARTITION_PRUNING_REUSE_BROADCAST_ONLY.key -> "false",
        SQLConf.EXCHANGE_REUSE_ENABLED.key -> "false") {
          spark.range(1000).select(col("id"), col("id").as("k"))
            .write
            .partitionBy("k")
            .format("parquet")
            .mode("overwrite")
            .saveAsTable("df1")

          spark.range(100)
            .select(col("id"), col("id").as("k"))
            .write
            .partitionBy("k")
            .format("parquet")
            .mode("overwrite")
            .saveAsTable("df2")

          val sqlText =
            """
              |EXPLAIN FORMATTED SELECT df1.id, df2.k
              |FROM df1 JOIN df2 ON df1.k = df2.k AND df2.id < 2
              |""".stripMargin

          val expected_pattern1 =
            "Subquery:1 Hosting operator id = 1 Hosting Expression = k#xL IN subquery#x"
          val expected_pattern2 =
            "PartitionFilters: \\[isnotnull\\(k#xL\\), dynamicpruningexpression\\(k#xL " +
              "IN subquery#x\\)\\]"
          val expected_pattern3 =
            "Location: InMemoryFileIndex \\[\\S*org.apache.spark.sql.ExplainSuite" +
              "/df2/\\S*, ... 99 entries\\]"
          val expected_pattern4 =
            "Location: InMemoryFileIndex \\[\\S*org.apache.spark.sql.ExplainSuite" +
              "/df1/\\S*, ... 999 entries\\]"
          withNormalizedExplain(sqlText) { normalizedOutput =>
            assert(expected_pattern1.r.findAllMatchIn(normalizedOutput).length == 1)
            assert(expected_pattern2.r.findAllMatchIn(normalizedOutput).length == 1)
            assert(expected_pattern3.r.findAllMatchIn(normalizedOutput).length == 2)
            assert(expected_pattern4.r.findAllMatchIn(normalizedOutput).length == 1)
          }
        }
    }
  }

  test("SPARK-33850: explain formatted - check presence of subquery in case of AQE") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      withTempView("df") {
        val df = spark.range(1, 100)
        df.createTempView("df")

        val sqlText = "EXPLAIN FORMATTED SELECT (SELECT min(id) FROM df) as v"
        val expected_pattern =
          "Subquery:1 Hosting operator id = 2 Hosting Expression = Subquery subquery#x"

        withNormalizedExplain(sqlText) { normalizedOutput =>
          assert(expected_pattern.r.findAllMatchIn(normalizedOutput).length == 1)
        }
      }
    }
  }

  test("Support ExplainMode in Dataset.explain") {
    val df1 = Seq((1, 2), (2, 3)).toDF("k", "v1")
    val df2 = Seq((2, 3), (1, 1)).toDF("k", "v2")
    val testDf = df1.join(df2, "k").groupBy("k").agg(count("v1"), sum("v1"), avg("v2"))

    val simpleExplainOutput = getNormalizedExplain(testDf, SimpleMode)
    assert(simpleExplainOutput.startsWith("== Physical Plan =="))
    Seq("== Parsed Logical Plan ==",
        "== Analyzed Logical Plan ==",
        "== Optimized Logical Plan ==").foreach { planType =>
      assert(!simpleExplainOutput.contains(planType))
    }
    checkKeywordsExistsInExplain(
      testDf,
      ExtendedMode,
      "== Parsed Logical Plan ==" ::
        "== Analyzed Logical Plan ==" ::
        "== Optimized Logical Plan ==" ::
        "== Physical Plan ==" ::
        Nil: _*)
    checkKeywordsExistsInExplain(
      testDf,
      CostMode,
      "Statistics(sizeInBytes=" ::
        Nil: _*)
    checkKeywordsExistsInExplain(
      testDf,
      CodegenMode,
      "WholeStageCodegen subtrees" ::
        "Generated code:" ::
        Nil: _*)
    checkKeywordsExistsInExplain(
      testDf,
      FormattedMode,
      "* LocalTableScan (1)" ::
        "(1) LocalTableScan [codegen id :" ::
        Nil: _*)
  }

  test("SPARK-34970: Redact Map type options in explain output") {
    val password = "MyPassWord"
    val token = "MyToken"
    val value = "value"
    val options = Map("password" -> password, "token" -> token, "key" -> value)
    val cmd = SaveIntoDataSourceCommand(spark.range(10).logicalPlan, new TestOptionsSource,
      options, SaveMode.Overwrite)

    Seq(SimpleMode, ExtendedMode, FormattedMode).foreach { mode =>
      checkKeywordsExistsInExplain(cmd, mode, value)
    }
    Seq(SimpleMode, ExtendedMode, CodegenMode, CostMode, FormattedMode).foreach { mode =>
      checkKeywordsNotExistsInExplain(cmd, mode, password)
      checkKeywordsNotExistsInExplain(cmd, mode, token)
    }
  }

  test("SPARK-34970: Redact CaseInsensitiveMap type options in explain output") {
    val password = "MyPassWord"
    val token = "MyToken"
    val value = "value"
    val tableName = "t"
    withTable(tableName) {
      val df1 = spark.range(10).toDF()
      df1.write.format("json").saveAsTable(tableName)
      val df2 = spark.read
        .option("key", value)
        .option("password", password)
        .option("token", token)
        .table(tableName)

      checkKeywordsExistsInExplain(df2, ExtendedMode, value)
      Seq(SimpleMode, ExtendedMode, CodegenMode, CostMode, FormattedMode).foreach { mode =>
        checkKeywordsNotExistsInExplain(df2, mode, password)
        checkKeywordsNotExistsInExplain(df2, mode, token)
      }
    }
  }

  test("Dataset.toExplainString has mode as string") {
    val df = spark.range(10).toDF
    def assertExplainOutput(mode: ExplainMode): Unit = {
      assert(df.queryExecution.explainString(mode).replaceAll("#\\d+", "#x").trim ===
        getNormalizedExplain(df, mode).trim)
    }
    assertExplainOutput(SimpleMode)
    assertExplainOutput(ExtendedMode)
    assertExplainOutput(CodegenMode)
    assertExplainOutput(CostMode)
    assertExplainOutput(FormattedMode)

    val errMsg = intercept[IllegalArgumentException] {
      ExplainMode.fromString("unknown")
    }.getMessage
    assert(errMsg.contains("Unknown explain mode: unknown"))
  }

  test("SPARK-31504: Output fields in formatted Explain should have determined order") {
    withTempPath { path =>
      spark.range(10).selectExpr("id as a", "id as b", "id as c", "id as d", "id as e")
        .write.mode("overwrite").parquet(path.getAbsolutePath)
      val df1 = spark.read.parquet(path.getAbsolutePath)
      val df2 = spark.read.parquet(path.getAbsolutePath)
      assert(getNormalizedExplain(df1, FormattedMode) === getNormalizedExplain(df2, FormattedMode))
    }
  }

  test("Coalesced bucket info should be a part of explain string") {
    withTable("t1", "t2") {
      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "0",
        SQLConf.COALESCE_BUCKETS_IN_JOIN_ENABLED.key -> "true") {
        Seq(1, 2).toDF("i").write.bucketBy(8, "i").saveAsTable("t1")
        Seq(2, 3).toDF("i").write.bucketBy(4, "i").saveAsTable("t2")
        val df1 = spark.table("t1")
        val df2 = spark.table("t2")
        val joined = df1.join(df2, df1("i") === df2("i"))
        checkKeywordsExistsInExplain(
          joined,
          SimpleMode,
          "SelectedBucketsCount: 8 out of 8 (Coalesced to 4)" :: Nil: _*)
      }
    }
  }

  test("Explain formatted output for scan operator for datasource V2") {
    withTempDir { dir =>
      Seq("parquet", "orc", "csv", "json").foreach { fmt =>
        val basePath = dir.getCanonicalPath + "/" + fmt
        val pushFilterMaps = Map (
          "parquet" ->
            "|PushedFilters: \\[IsNotNull\\(value\\), GreaterThan\\(value,2\\)\\]",
          "orc" ->
            "|PushedFilters: \\[IsNotNull\\(value\\), GreaterThan\\(value,2\\)\\]",
          "csv" ->
            "|PushedFilters: \\[IsNotNull\\(value\\), GreaterThan\\(value,2\\)\\]",
          "json" ->
            "|remove_marker"
        )
        val expected_plan_fragment1 =
          s"""
             |\\(1\\) BatchScan
             |Output \\[2\\]: \\[value#x, id#x\\]
             |DataFilters: \\[isnotnull\\(value#x\\), \\(value#x > 2\\)\\]
             |Format: $fmt
             |Location: InMemoryFileIndex\\([0-9]+ paths\\)\\[.*\\]
             |PartitionFilters: \\[isnotnull\\(id#x\\), \\(id#x > 1\\)\\]
             ${pushFilterMaps.get(fmt).get}
             |ReadSchema: struct\\<value:int\\>
             |""".stripMargin.replaceAll("\nremove_marker", "").trim

        spark.range(10)
          .select(col("id"), col("id").as("value"))
          .write.option("header", true)
          .partitionBy("id")
          .format(fmt)
          .save(basePath)
        val readSchema =
          StructType(Seq(StructField("id", IntegerType), StructField("value", IntegerType)))
        withSQLConf(SQLConf.USE_V1_SOURCE_LIST.key -> "") {
          val df = spark
            .read
            .schema(readSchema)
            .option("header", true)
            .format(fmt)
            .load(basePath).where($"id" > 1 && $"value" > 2)
          val normalizedOutput = getNormalizedExplain(df, FormattedMode)
          assert(expected_plan_fragment1.r.findAllMatchIn(normalizedOutput).length == 1)
        }
      }
    }
  }

  test("Explain UnresolvedRelation with CaseInsensitiveStringMap options") {
    val tableName = "test"
    withTable(tableName) {
      val df1 = Seq((1L, "a"), (2L, "b"), (3L, "c")).toDF("id", "data")
      df1.write.saveAsTable(tableName)
      val df2 = spark.read
        .option("key1", "value1")
        .option("KEY2", "VALUE2")
        .table(tableName)
      // == Parsed Logical Plan ==
      // 'UnresolvedRelation [test], [key1=value1, KEY2=VALUE2]
      checkKeywordsExistsInExplain(df2, keywords = "[key1=value1, KEY2=VALUE2]")
    }
  }

  test("SPARK-35225: Handle empty output for analyzed plan") {
    withTempView("test") {
      checkKeywordsExistsInExplain(
        sql("CREATE TEMPORARY VIEW test AS SELECT 1"),
        "== Analyzed Logical Plan ==\nCreateViewCommand")
    }
  }
}

class ExplainSuiteAE extends ExplainSuiteHelper with EnableAdaptiveExecutionSuite {
  import testImplicits._

  test("SPARK-35884: Explain Formatted") {
    val df1 = Seq((1, 2), (2, 3)).toDF("k", "v1")
    val df2 = Seq((2, 3), (1, 1)).toDF("k", "v2")
    val testDf = df1.join(df2, "k").groupBy("k").agg(count("v1"), sum("v1"), avg("v2"))
    // trigger the final plan for AQE
    testDf.collect()
    // AdaptiveSparkPlan (21)
    // +- == Final Plan ==
    //    * HashAggregate (12)
    //    +- AQEShuffleRead (11)
    //       +- ShuffleQueryStage (10)
    //          +- Exchange (9)
    //             +- * HashAggregate (8)
    //                +- * Project (7)
    //                   +- * BroadcastHashJoin Inner BuildRight (6)
    //                      :- * LocalTableScan (1)
    //                      +- BroadcastQueryStage (5)
    //                         +- BroadcastExchange (4)
    //                            +- * Project (3)
    //                               +- * LocalTableScan (2)
    // +- == Initial Plan ==
    //    HashAggregate (20)
    //    +- Exchange (19)
    //       +- HashAggregate (18)
    //          +- Project (17)
    //             +- BroadcastHashJoin Inner BuildRight (16)
    //                :- Project (14)
    //                :  +- LocalTableScan (13)
    //                +- BroadcastExchange (15)
    //                   +- Project (3)
    //                      +- LocalTableScan (2)
    checkKeywordsExistsInExplain(
      testDf,
      FormattedMode,
      """
        |(5) BroadcastQueryStage
        |Output [2]: [k#x, v2#x]
        |Arguments: 0""".stripMargin,
      """
        |(10) ShuffleQueryStage
        |Output [5]: [k#x, count#xL, sum#xL, sum#x, count#xL]
        |Arguments: 1""".stripMargin,
      """
        |(11) AQEShuffleRead
        |Input [5]: [k#x, count#xL, sum#xL, sum#x, count#xL]
        |Arguments: coalesced
        |""".stripMargin,
      """
        |(16) BroadcastHashJoin
        |Left keys [1]: [k#x]
        |Right keys [1]: [k#x]
        |Join condition: None
        |""".stripMargin,
      """
        |(19) Exchange
        |Input [5]: [k#x, count#xL, sum#xL, sum#x, count#xL]
        |""".stripMargin,
      """
        |(21) AdaptiveSparkPlan
        |Output [4]: [k#x, count(v1)#xL, sum(v1)#xL, avg(v2)#x]
        |Arguments: isFinalPlan=true
        |""".stripMargin
    )
    checkKeywordsNotExistsInExplain(testDf, FormattedMode, "unknown")
  }

  test("SPARK-35884: Explain should only display one plan before AQE takes effect") {
    val df = (0 to 10).toDF("id").where('id > 5)
    val modes = Seq(SimpleMode, ExtendedMode, CostMode, FormattedMode)
    modes.foreach { mode =>
      checkKeywordsExistsInExplain(df, mode, "AdaptiveSparkPlan")
      checkKeywordsNotExistsInExplain(df, mode, "Initial Plan", "Current Plan")
    }
    df.collect()
    modes.foreach { mode =>
      checkKeywordsExistsInExplain(df, mode, "Initial Plan", "Final Plan")
      checkKeywordsNotExistsInExplain(df, mode, "unknown")
    }
  }

  test("SPARK-35884: Explain formatted with subquery") {
    withTempView("t1", "t2") {
      spark.range(100).select('id % 10 as "key", 'id as "value").createOrReplaceTempView("t1")
      spark.range(10).createOrReplaceTempView("t2")
      val query =
        """
          |SELECT key, value FROM t1
          |JOIN t2 ON t1.key = t2.id
          |WHERE value > (SELECT MAX(id) FROM t2)
          |""".stripMargin
      val df = sql(query).toDF()
      df.collect()
      checkKeywordsExistsInExplain(df, FormattedMode,
        """
          |(2) Filter [codegen id : 2]
          |Input [1]: [id#xL]
          |Condition : ((id#xL > Subquery subquery#x, [id=#x]) AND isnotnull((id#xL % 10)))
          |""".stripMargin,
        """
          |(6) BroadcastQueryStage
          |Output [1]: [id#xL]
          |Arguments: 0""".stripMargin,
        """
          |(12) AdaptiveSparkPlan
          |Output [2]: [key#xL, value#xL]
          |Arguments: isFinalPlan=true
          |""".stripMargin,
        """
          |Subquery:1 Hosting operator id = 2 Hosting Expression = Subquery subquery#x, [id=#x]
          |""".stripMargin,
        """
          |(16) ShuffleQueryStage
          |Output [1]: [max#xL]
          |Arguments: 0""".stripMargin,
        """
          |(20) AdaptiveSparkPlan
          |Output [1]: [max(id)#xL]
          |Arguments: isFinalPlan=true
          |""".stripMargin
      )
      checkKeywordsNotExistsInExplain(df, FormattedMode, "unknown")
    }
  }

  test("SPARK-35133: explain codegen should work with AQE") {
    withSQLConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED.key -> "true") {
      withTempView("df") {
        val df = spark.range(5).select(col("id").as("key"), col("id").as("value"))
        df.createTempView("df")

        val sqlText = "EXPLAIN CODEGEN SELECT key, MAX(value) FROM df GROUP BY key"
        val expectedCodegenText = "Found 2 WholeStageCodegen subtrees."
        val expectedNoCodegenText = "Found 0 WholeStageCodegen subtrees."
        withNormalizedExplain(sqlText) { normalizedOutput =>
          assert(normalizedOutput.contains(expectedNoCodegenText))
        }

        val aggDf = df.groupBy('key).agg(max('value))
        withNormalizedExplain(aggDf, CodegenMode) { normalizedOutput =>
          assert(normalizedOutput.contains(expectedNoCodegenText))
        }

        // trigger the final plan for AQE
        aggDf.collect()
        withNormalizedExplain(aggDf, CodegenMode) { normalizedOutput =>
          assert(normalizedOutput.contains(expectedCodegenText))
        }
      }
    }
  }
}

case class ExplainSingleData(id: Int)
