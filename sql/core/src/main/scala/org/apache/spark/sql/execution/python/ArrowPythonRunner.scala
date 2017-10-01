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

package org.apache.spark.sql.execution.python

import java.io._
import java.net._
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConverters._

import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.stream.{ArrowStreamReader, ArrowStreamWriter}

import org.apache.spark._
import org.apache.spark.api.python._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.arrow.{ArrowUtils, ArrowWriter}
import org.apache.spark.sql.execution.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

/**
 * Similar to `PythonUDFRunner`, but exchange data with Python worker via Arrow stream.
 */
class ArrowPythonRunner(
    funcs: Seq[ChainedPythonFunctions],
    batchSize: Int,
    bufferSize: Int,
    reuseWorker: Boolean,
    evalType: Int,
    argOffsets: Array[Array[Int]],
    schema: StructType)
  extends BasePythonRunner[InternalRow, ColumnarBatch](
    funcs, bufferSize, reuseWorker, evalType, argOffsets) {

  protected override def newWriterThread(
      env: SparkEnv,
      worker: Socket,
      inputIterator: Iterator[InternalRow],
      partitionIndex: Int,
      context: TaskContext): WriterThread = {
    new WriterThread(env, worker, inputIterator, partitionIndex, context) {

      protected override def writeCommand(dataOut: DataOutputStream): Unit = {
        PythonUDFRunner.writeUDFs(dataOut, funcs, argOffsets)
      }

      protected override def writeIteratorToStream(dataOut: DataOutputStream): Unit = {
        val arrowSchema = ArrowUtils.toArrowSchema(schema)
        val allocator = ArrowUtils.rootAllocator.newChildAllocator(
          s"stdout writer for $pythonExec", 0, Long.MaxValue)

        val root = VectorSchemaRoot.create(arrowSchema, allocator)
        val arrowWriter = ArrowWriter.create(root)

        var closed = false

        context.addTaskCompletionListener { _ =>
          if (!closed) {
            root.close()
            allocator.close()
          }
        }

        val writer = new ArrowStreamWriter(root, null, dataOut)
        writer.start()

        Utils.tryWithSafeFinally {
          while (inputIterator.hasNext) {
            var rowCount = 0
            while (inputIterator.hasNext && (batchSize <= 0 || rowCount < batchSize)) {
              val row = inputIterator.next()
              arrowWriter.write(row)
              rowCount += 1
            }
            arrowWriter.finish()
            writer.writeBatch()
            arrowWriter.reset()
          }
        } {
          writer.end()
          root.close()
          allocator.close()
          closed = true
        }
      }
    }
  }

  protected override def newReaderIterator(
      stream: DataInputStream,
      writerThread: WriterThread,
      startTime: Long,
      env: SparkEnv,
      worker: Socket,
      released: AtomicBoolean,
      context: TaskContext): Iterator[ColumnarBatch] = {
    new ReaderIterator(stream, writerThread, startTime, env, worker, released, context) {

      private val allocator = ArrowUtils.rootAllocator.newChildAllocator(
        s"stdin reader for $pythonExec", 0, Long.MaxValue)

      private var reader: ArrowStreamReader = _
      private var root: VectorSchemaRoot = _
      private var schema: StructType = _
      private var vectors: Array[ColumnVector] = _

      private var closed = false

      context.addTaskCompletionListener { _ =>
        // todo: we need something like `reader.end()`, which release all the resources, but leave
        // the input stream open. `reader.close()` will close the socket and we can't reuse worker.
        // So here we simply not close the reader, which is problematic.
        if (!closed) {
          if (root != null) {
            root.close()
          }
          allocator.close()
        }
      }

      private var batchLoaded = true

      protected override def read(): ColumnarBatch = {
        if (writerThread.exception.isDefined) {
          throw writerThread.exception.get
        }
        try {
          if (reader != null && batchLoaded) {
            batchLoaded = reader.loadNextBatch()
            if (batchLoaded) {
              val batch = new ColumnarBatch(schema, vectors, root.getRowCount)
              batch.setNumRows(root.getRowCount)
              batch
            } else {
              root.close()
              allocator.close()
              closed = true
              // Reach end of stream. Call `read()` again to read control data.
              read()
            }
          } else {
            stream.readInt() match {
              case SpecialLengths.START_ARROW_STREAM =>
                reader = new ArrowStreamReader(stream, allocator)
                root = reader.getVectorSchemaRoot()
                schema = ArrowUtils.fromArrowSchema(root.getSchema())
                vectors = root.getFieldVectors().asScala.map { vector =>
                  new ArrowColumnVector(vector)
                }.toArray[ColumnVector]
                read()
              case SpecialLengths.TIMING_DATA =>
                handleTimingData()
                read()
              case SpecialLengths.PYTHON_EXCEPTION_THROWN =>
                throw handlePythonException()
              case SpecialLengths.END_OF_DATA_SECTION =>
                handleEndOfDataSection()
                null
            }
          }
        } catch handleException
      }
    }
  }
}
