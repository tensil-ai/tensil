package tf2rtl.tools.web

import upickle.default.{ReadWriter, macroRW}
import upickle.implicits.key

import tf2rtl.{Architecture, ArchitectureDataType}
import tf2rtl.tools.{Compiler, CompilerOptions}

import awscala._, sqs._, dynamodbv2._, s3._
import java.io._
import java.time._
import scala.collection._

case class CompilerTask(
    @key("id") id: String,
    @key("owner_user_id") ownerUserId: String,
    @key("job_id") jobId: String,
    @key("arch") arch: Architecture,
    @key("model_name") modelName: String,
    @key("model_obj_name") modelObjName: String,
    @key("output_names") outputNames: Seq[String],
) {}

object CompilerTask {
  implicit val sqs = SQS.at(Region.US_WEST_1)
  implicit val db  = DynamoDB.at(Region.US_WEST_1)
  implicit val s3  = S3.at(Region.US_WEST_1)

  implicit val rw: ReadWriter[CompilerTask] = macroRW

  // TODO: these queue, table and bucket names to be configured from container environment
  val queue          = sqs.queue("tf2rtl-web-compiler-7af9ea2").get
  val jobTable       = db.table("tf2rtl-web-job-c9cb5e4").get
  val taskTable      = db.table("tf2rtl-web-task-731b0cb").get
  val compilerBucket = s3.bucket("tf2rtl-web-compiler-8681a04").get
  val uploadsBucket  = s3.bucket("tf2rtl-web-upload-7a8bd18").get

  def process(count: Int = 1) = {
    val messages = sqs.receiveMessage(queue, count, 5)

    for (message <- messages) {
      val tasks: List[CompilerTask] =
        upickle.default.read[List[CompilerTask]](message.body)

      for (task <- tasks) {
        val options = CompilerOptions(
          arch = task.arch,
          collectBackendStats = true
        )

        taskTable.put(
          task.jobId,
          task.id,
          "arch_data_type"            -> task.arch.dataType.toString(),
          "arch_array_size"           -> task.arch.arraySize,
          "arch_dram0_depth"          -> task.arch.dram0Depth,
          "arch_dram1_depth"          -> task.arch.dram1Depth,
          "arch_local_depth"          -> task.arch.localDepth,
          "arch_accumulator_depth"    -> task.arch.accumulatorDepth,
          "arch_simd_registers_depth" -> task.arch.simdRegistersDepth,
          "arch_stride0_depth"        -> task.arch.stride0Depth,
          "arch_stride1_depth"        -> task.arch.stride1Depth,
        )

        try {
          val modelObj        = uploadsBucket.getObject(task.modelObjName)
          val modelSourceType = Compiler.getModelSourceType(task.modelObjName)

          val result = Compiler.compileStreamToFiles(
            task.modelName,
            modelSourceType,
            modelObj.get.content,
            task.outputNames,
            options
          )

          val constFile    = new File(s"${task.modelName}.tdata")
          val programFile  = new File(s"${task.modelName}.tprog")
          val manifestFile = new File(s"${task.modelName}.tmodel")

          jobTable.putAttributes(
            task.ownerUserId,
            task.jobId,
            Seq(
              "model_weights" -> result.stats.constsScalarSize,
              "model_macs"    -> result.stats.macs,
              "model_layers"  -> result.stats.layersNumber
            )
          )

          val constObjectName =
            s"${task.jobId}/${task.id}/${task.modelName}.tdata"
          val programObjectName =
            s"${task.jobId}/${task.id}/${task.modelName}.tprog"
          val manifestObjectName =
            s"${task.jobId}/${task.id}/${task.modelName}.tmodel"

          compilerBucket.put(constObjectName, constFile)
          compilerBucket.put(programObjectName, programFile)
          compilerBucket.put(manifestObjectName, manifestFile)

          taskTable.putAttributes(
            task.jobId,
            task.id,
            Seq(
              "artifacts_consts"         -> constObjectName,
              "artifacts_program"        -> programObjectName,
              "artifacts_manifest"       -> manifestObjectName,
              "stats_program_size_bytes" -> result.stats.programSizeBytes,
              "stats_cycles"             -> result.stats.cycles,
              "stats_energy"             -> result.stats.energy,
              "stats_consts_utilization" -> result.stats.constsUtilization,
              "stats_mac_efficiency"     -> result.stats.macEfficiency
            )
          )
        } catch {
          case e: Throwable =>
            taskTable.putAttributes(
              task.jobId,
              task.id,
              Seq("error_message" -> e.getMessage())
            )
        } finally {
          queue.remove(message)
        }
      }
    }
  }

  def submit(tasks: List[CompilerTask]) = {
    queue.add(upickle.default.write(tasks))
  }

  val Kibi = 1024
  val Mebi = Kibi * Kibi

  def submitPublicGrids(): List[String] = {
    val models = List(
      "resnet20v2_cifar_pb" -> ("public/resnet20v2_cifar.pb", Seq(
        "Identity"
      ), (
        0.25,
        0.75
      )),
      "resnet50v2_imagenet_pb" -> ("public/resnet50v2_imagenet.pb", Seq(
        "Identity"
      ), (
        0.25,
        0.75
      )),
      "yolov4_tiny_192_pb" -> ("public/yolov4_tiny_192.pb", Seq(
        "model/conv2d_17/BiasAdd",
        "model/conv2d_20/BiasAdd"
      ), (0.125, 0.875))
    )

    val timestamp = ZonedDateTime
      .now()
      .format(format.DateTimeFormatter.ofPattern("uuuu_MM_dd_HH_mm_ss"))

    for (
      (
        modelName,
        (modelObjName, outputNames, (accumulatorRatio, localRatio))
      ) <- models
    )
      yield submitGrid(
        "public",
        s"${modelName}_${timestamp}",
        modelName,
        0,
        modelObjName,
        outputNames,
        accumulatorRatio,
        localRatio
      )
  }

  def submitGrid(
      ownerUserId: String,
      jobId: String,
      modelName: String,
      modelVersion: Int,
      modelObjName: String,
      outputNames: Seq[String] = Seq("Identity"),
      accumulatorRatio: Double = 0.25,
      localRatio: Double = 0.75
  ): String = {
    require(accumulatorRatio + localRatio == 1)
    require(!outputNames.isEmpty)

    val arraySizes  = List(4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128)
    val totalDepths = (1 to 10).map(_ * 8 * Kibi)

    jobTable.put(
      ownerUserId,
      jobId,
      "task_count"    -> arraySizes.size * totalDepths.size,
      "model_name"    -> modelName,
      "model_version" -> modelVersion
    )

    for (
      arraySize  <- arraySizes;
      totalDepth <- totalDepths
    ) {
      val accumulatorDepth = (totalDepth * accumulatorRatio).toLong
      val localDepth       = (totalDepth * localRatio).toLong
      val id =
        s"${arraySize}x${arraySize}_acc${accumulatorDepth}_loc${localDepth}"

      val arch = Architecture.mkWithDefaults(
        dataType = ArchitectureDataType.FP16BP8,
        arraySize = arraySize,
        dram0Depth = Mebi * 10,
        dram1Depth = Mebi * 10,
        accumulatorDepth = accumulatorDepth,
        localDepth = localDepth,
        stride0Depth = 8,
        stride1Depth = 8
      )

      submit(
        List(
          CompilerTask(
            id = id,
            ownerUserId = ownerUserId,
            jobId = jobId,
            arch = arch,
            modelName = modelName,
            modelObjName = modelObjName,
            outputNames = outputNames
          )
        )
      )
    }

    jobId
  }
}

object CompilerApp extends App {
  // Number of tasks to be configured from container environment
  CompilerTask.process(1)
}
