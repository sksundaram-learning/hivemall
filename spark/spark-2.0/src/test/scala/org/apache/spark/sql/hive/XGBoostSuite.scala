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

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions._
import org.apache.spark.sql.hive.HivemallOps._
import org.apache.spark.sql.hive.HivemallUtils._
import org.apache.spark.sql.types._
import org.apache.spark.test.VectorQueryTest

import hivemall.xgboost._

final class XGBoostSuite extends VectorQueryTest {
  import hiveContext.implicits._

  private val defaultOptions = XGBoostOptions()
    .set("num_round", "10")
    .set("max_depth", "4")

  private val numModles = 3

  private def countModels(dirPath: String): Int = {
    new File(dirPath).listFiles().toSeq.count(_.getName.startsWith("xgbmodel-"))
  }
  test("check XGBoost options") {
    assert(s"$defaultOptions" == "-max_depth 4 -num_round 10")
    val errMsg = intercept[IllegalArgumentException] {
      defaultOptions.set("unknown", "3")
    }
    assert(errMsg.getMessage == "requirement failed: " +
      "non-existing key detected in XGBoost options: unknown")
  }

  test("train_xgboost_regr") {
    withTempModelDir { tempDir =>

      // Save built models in persistent storage
      mllibTrainDf.repartition(numModles)
        .train_xgboost_regr($"features", $"label", s"${defaultOptions}")
        .write.format(xgboost).save(tempDir)

      // Check #models generated by XGBoost
      assert(countModels(tempDir) == numModles)

      // Load the saved models
      val model = hiveContext.sparkSession.read.format(xgboost).load(tempDir)
      val predict = model.join(mllibTestDf)
        .xgboost_predict($"rowid", $"features", $"model_id", $"pred_model")
        .groupBy("rowid").avg()
        .as("rowid", "predicted")

      val result = predict.join(mllibTestDf, predict("rowid") === mllibTestDf("rowid"), "INNER")
        .select(predict("rowid"), $"predicted", $"label")

      result.select(avg(abs($"predicted" - $"label"))).collect.map {
        case Row(diff: Double) => assert(diff > 0.0)
      }
    }
  }

  test("train_xgboost_classifier") {
    withTempModelDir { tempDir =>

      mllibTrainDf.repartition(numModles)
        .train_xgboost_regr($"features", $"label", s"${defaultOptions}")
        .write.format(xgboost).save(tempDir)

      // Check #models generated by XGBoost
      assert(countModels(tempDir) == numModles)

      val model = hiveContext.sparkSession.read.format(xgboost).load(tempDir)
      val predict = model.join(mllibTestDf)
        .xgboost_predict($"rowid", $"features", $"model_id", $"pred_model")
        .groupBy("rowid").avg()
        .as("rowid", "predicted")

      val result = predict.join(mllibTestDf, predict("rowid") === mllibTestDf("rowid"), "INNER")
        .select(
          when($"predicted" >= 0.50, 1).otherwise(0),
          $"label".cast(IntegerType)
        )
        .toDF("predicted", "label")

      assert((result.where($"label" === $"predicted").count + 0.0) / result.count > 0.0)
    }
  }

  test("train_xgboost_multiclass_classifier") {
    withTempModelDir { tempDir =>

      mllibTrainDf.repartition(numModles)
        .train_xgboost_multiclass_classifier(
          $"features", $"label", s"${defaultOptions.set("num_class", "2")}")
        .write.format(xgboost).save(tempDir)

      // Check #models generated by XGBoost
      assert(countModels(tempDir) == numModles)

      val model = hiveContext.sparkSession.read.format(xgboost).load(tempDir)
      val predict = model.join(mllibTestDf)
        .xgboost_multiclass_predict($"rowid", $"features", $"model_id", $"pred_model")
        .groupby("rowid").max_label("probability", "label")
        .toDF("rowid", "predicted")

      val result = predict.join(mllibTestDf, predict("rowid") === mllibTestDf("rowid"), "INNER")
        .select(
          predict("rowid"),
          $"predicted",
          $"label".cast(IntegerType)
        )

      assert((result.where($"label" === $"predicted").count + 0.0) / result.count > 0.0)
    }
  }
}
