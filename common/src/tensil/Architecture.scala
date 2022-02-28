package tensil

import upickle.default.{ReadWriter, macroRW}
import upickle.implicits.key
import java.io.{DataOutputStream, DataInputStream, FileOutputStream}

case class Architecture(
    @key("data_type") dataType: ArchitectureDataType,
    @key("array_size") arraySize: Int,
    @key("dram0_depth") dram0Depth: Long,
    @key("dram1_depth") dram1Depth: Long,
    @key("local_depth") localDepth: Long,
    @key("accumulator_depth") accumulatorDepth: Long,
    @key("simd_registers_depth") simdRegistersDepth: Int,
    @key("stride0_depth") stride0Depth: Int,
    @key("stride1_depth") stride1Depth: Int,
    @key("sample_block_size") sampleBlockSize: Int,
    @key("decoder_timeout") decoderTimeout: Int,
    @key("validate_instructions") validateInstructions: Boolean,
) {
  override def toString() =
    s"Architecture($dataType, ${arraySize}x${arraySize}, acc=$accumulatorDepth, loc=$localDepth, drams=[$dram0Depth,$dram1Depth], strides=[$stride0Depth,$stride1Depth], simdRegs=$simdRegistersDepth)"

  def varsDepth   = dram0Depth
  def constsDepth = dram1Depth

  def writeDriverArchitecureParams(): Unit = {
    val stream = new FileOutputStream("architecture_params.h")
    writeDriverArchitecureParams(new DataOutputStream(stream));
    stream.close()
  }

  def writeDriverArchitecureParams(stream: DataOutputStream): Unit = {
    stream.writeBytes("#pragma once\n\n");
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_DATA_TYPE             TENSIL_DATA_TYPE_${dataType}\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_ARRAY_SIZE            $arraySize\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_DRAM0_DEPTH           $dram0Depth\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_DRAM1_DEPTH           $dram1Depth\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_LOCAL_DEPTH           $localDepth\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_ACCUMULATOR_DEPTH     $accumulatorDepth\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_SIMD_REGISTERS_DEPTH  $simdRegistersDepth\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_STRIDE0_DEPTH         $stride0Depth\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_STRIDE1_DEPTH         $stride1Depth\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_SAMPLE_BLOCK_SIZE     $sampleBlockSize\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_DECODER_TIMEOUT       $decoderTimeout\n"
    );
    stream.writeBytes(
      s"#define TENSIL_ARCHITECTURE_VALIDATE_INSTRUCTIONS $validateInstructions\n"
    );
  }
}

object Architecture {
  def mkWithDefaults(
      arraySize: Int = 8,
      localDepth: Long = 2048,
      accumulatorDepth: Long = 512,
      dataType: ArchitectureDataType = ArchitectureDataType.FLOAT32,
      dram0Depth: Long = 1048576L,
      dram1Depth: Long = 1048576L,
      simdRegistersDepth: Int = 1,
      stride0Depth: Int = 1,
      stride1Depth: Int = 1,
      sampleBlockSize: Int = 0,
      decoderTimeout: Int = 100,
      validateInstructions: Boolean = false,
  ): Architecture =
    Architecture(
      dataType = dataType,
      arraySize = arraySize,
      dram0Depth = dram0Depth,
      dram1Depth = dram1Depth,
      localDepth = localDepth,
      accumulatorDepth = accumulatorDepth,
      simdRegistersDepth = simdRegistersDepth,
      stride0Depth = stride0Depth,
      stride1Depth = stride1Depth,
      sampleBlockSize = sampleBlockSize,
      decoderTimeout = decoderTimeout,
      validateInstructions = validateInstructions
    )

  implicit val rw: ReadWriter[Architecture] = macroRW

  val tiny = Architecture(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 8,
    localDepth = 8192,
    dram0Depth = 1048576,
    dram1Depth = 1048576,
    accumulatorDepth = 2048,
    simdRegistersDepth = 1,
    stride0Depth = 8,
    stride1Depth = 8,
    sampleBlockSize = 0,
    decoderTimeout = 100,
    validateInstructions = true,
  )

  val formal = Architecture(
    dataType = ArchitectureDataType.FP16BP8,
    arraySize = 2,
    localDepth = 4,
    dram0Depth = 4,
    dram1Depth = 4,
    accumulatorDepth = 4,
    simdRegistersDepth = 1,
    stride0Depth = 2,
    stride1Depth = 2,
    sampleBlockSize = 0,
    decoderTimeout = 0,
    validateInstructions = false,
  )
}
