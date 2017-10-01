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

package org.apache.spark.sql.hive

import java.io.File
import java.nio.file.Files

import org.apache.spark.TestUtils
import org.apache.spark.sql.{QueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.CatalogTableType
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.util.Utils

/**
 * Test HiveExternalCatalog backward compatibility.
 *
 * Note that, this test suite will automatically download spark binary packages of different
 * versions to a local directory `/tmp/spark-test`. If there is already a spark folder with
 * expected version under this local directory, e.g. `/tmp/spark-test/spark-2.0.3`, we will skip the
 * downloading for this spark version.
 */
class HiveExternalCatalogVersionsSuite extends SparkSubmitTestUtils {
  private val wareHousePath = Utils.createTempDir(namePrefix = "warehouse")
  private val tmpDataDir = Utils.createTempDir(namePrefix = "test-data")
  // For local test, you can set `sparkTestingDir` to a static value like `/tmp/test-spark`, to
  // avoid downloading Spark of different versions in each run.
  private val sparkTestingDir = Utils.createTempDir(namePrefix = "test-spark")
  private val unusedJar = TestUtils.createJarWithClasses(Seq.empty)

  override def afterAll(): Unit = {
    Utils.deleteRecursively(wareHousePath)
    Utils.deleteRecursively(tmpDataDir)
    Utils.deleteRecursively(sparkTestingDir)
    super.afterAll()
  }

  private def downloadSpark(version: String): Unit = {
    import scala.sys.process._

    val url = s"https://d3kbcqa49mib13.cloudfront.net/spark-$version-bin-hadoop2.7.tgz"

    Seq("wget", url, "-q", "-P", sparkTestingDir.getCanonicalPath).!

    val downloaded = new File(sparkTestingDir, s"spark-$version-bin-hadoop2.7.tgz").getCanonicalPath
    val targetDir = new File(sparkTestingDir, s"spark-$version").getCanonicalPath

    Seq("mkdir", targetDir).!

    Seq("tar", "-xzf", downloaded, "-C", targetDir, "--strip-components=1").!

    Seq("rm", downloaded).!
  }

  private def genDataDir(name: String): String = {
    new File(tmpDataDir, name).getCanonicalPath
  }

  override def beforeAll(): Unit = {
    super.beforeAll()

    val tempPyFile = File.createTempFile("test", ".py")
    Files.write(tempPyFile.toPath,
      s"""
        |from pyspark.sql import SparkSession
        |
        |spark = SparkSession.builder.enableHiveSupport().getOrCreate()
        |version_index = spark.conf.get("spark.sql.test.version.index", None)
        |
        |spark.sql("create table data_source_tbl_{} using json as select 1 i".format(version_index))
        |
        |spark.sql("create table hive_compatible_data_source_tbl_" + version_index + \\
        |          " using parquet as select 1 i")
        |
        |json_file = "${genDataDir("json_")}" + str(version_index)
        |spark.range(1, 2).selectExpr("cast(id as int) as i").write.json(json_file)
        |spark.sql("create table external_data_source_tbl_" + version_index + \\
        |          "(i int) using json options (path '{}')".format(json_file))
        |
        |parquet_file = "${genDataDir("parquet_")}" + str(version_index)
        |spark.range(1, 2).selectExpr("cast(id as int) as i").write.parquet(parquet_file)
        |spark.sql("create table hive_compatible_external_data_source_tbl_" + version_index + \\
        |          "(i int) using parquet options (path '{}')".format(parquet_file))
        |
        |json_file2 = "${genDataDir("json2_")}" + str(version_index)
        |spark.range(1, 2).selectExpr("cast(id as int) as i").write.json(json_file2)
        |spark.sql("create table external_table_without_schema_" + version_index + \\
        |          " using json options (path '{}')".format(json_file2))
        |
        |spark.sql("create view v_{} as select 1 i".format(version_index))
      """.stripMargin.getBytes("utf8"))

    PROCESS_TABLES.testingVersions.zipWithIndex.foreach { case (version, index) =>
      val sparkHome = new File(sparkTestingDir, s"spark-$version")
      if (!sparkHome.exists()) {
        downloadSpark(version)
      }

      val args = Seq(
        "--name", "prepare testing tables",
        "--master", "local[2]",
        "--conf", "spark.ui.enabled=false",
        "--conf", "spark.master.rest.enabled=false",
        "--conf", s"spark.sql.warehouse.dir=${wareHousePath.getCanonicalPath}",
        "--conf", s"spark.sql.test.version.index=$index",
        "--driver-java-options", s"-Dderby.system.home=${wareHousePath.getCanonicalPath}",
        tempPyFile.getCanonicalPath)
      runSparkSubmit(args, Some(sparkHome.getCanonicalPath))
    }

    tempPyFile.delete()
  }

  test("backward compatibility") {
    val args = Seq(
      "--class", PROCESS_TABLES.getClass.getName.stripSuffix("$"),
      "--name", "HiveExternalCatalog backward compatibility test",
      "--master", "local[2]",
      "--conf", "spark.ui.enabled=false",
      "--conf", "spark.master.rest.enabled=false",
      "--conf", s"spark.sql.warehouse.dir=${wareHousePath.getCanonicalPath}",
      "--driver-java-options", s"-Dderby.system.home=${wareHousePath.getCanonicalPath}",
      unusedJar.toString)
    runSparkSubmit(args)
  }
}

object PROCESS_TABLES extends QueryTest with SQLTestUtils {
  // Tests the latest version of every release line.
  val testingVersions = Seq("2.0.2", "2.1.1", "2.2.0")

  protected var spark: SparkSession = _

  def main(args: Array[String]): Unit = {
    val session = SparkSession.builder()
      .enableHiveSupport()
      .getOrCreate()
    spark = session

    testingVersions.indices.foreach { index =>
      Seq(
        s"data_source_tbl_$index",
        s"hive_compatible_data_source_tbl_$index",
        s"external_data_source_tbl_$index",
        s"hive_compatible_external_data_source_tbl_$index",
        s"external_table_without_schema_$index").foreach { tbl =>
        val tableMeta = spark.sharedState.externalCatalog.getTable("default", tbl)

        // make sure we can insert and query these tables.
        session.sql(s"insert into $tbl select 2")
        checkAnswer(session.sql(s"select * from $tbl"), Row(1) :: Row(2) :: Nil)
        checkAnswer(session.sql(s"select i from $tbl where i > 1"), Row(2))

        // make sure we can rename table.
        val newName = tbl + "_renamed"
        sql(s"ALTER TABLE $tbl RENAME TO $newName")
        val readBack = spark.sharedState.externalCatalog.getTable("default", newName)

        val actualTableLocation = readBack.storage.locationUri.get.getPath
        val expectedLocation = if (tableMeta.tableType == CatalogTableType.EXTERNAL) {
          tableMeta.storage.locationUri.get.getPath
        } else {
          spark.sessionState.catalog.defaultTablePath(TableIdentifier(newName, None)).getPath
        }
        assert(actualTableLocation == expectedLocation)

        // make sure we can alter table location.
        withTempDir { dir =>
          val path = dir.toURI.toString.stripSuffix("/")
          sql(s"ALTER TABLE ${tbl}_renamed SET LOCATION '$path'")
          val readBack = spark.sharedState.externalCatalog.getTable("default", tbl + "_renamed")
          val actualTableLocation = readBack.storage.locationUri.get.getPath
          val expected = dir.toURI.getPath.stripSuffix("/")
          assert(actualTableLocation == expected)
        }
      }

      // test permanent view
      checkAnswer(sql(s"select i from v_$index"), Row(1))
    }
  }
}
