import sys

ila_verilog = {
    "Decoder": """decoder_ila decoder_ila_inst (
	.clk(clock),
	.probe0(io_timeout),  // 1
	// instruction
	.probe1(instruction_io_deq_ready), // 1
	.probe2(instruction_io_deq_valid), // 1
	.probe3(instruction_io_deq_bits_opcode), // 4
	.probe4(instruction_io_deq_bits_flags), // 4
	.probe5(instruction_io_deq_bits_arguments), // 72
	// memPortA
	.probe6(io_memPortA_ready), // 1
	.probe7(io_memPortA_valid), // 1
	.probe8(io_memPortA_bits_write), // 1
	.probe9(io_memPortA_bits_address), // 11
	// memPortB
	.probe10(io_memPortB_ready), // 1
	.probe11(io_memPortB_valid), // 1
	.probe12(io_memPortB_bits_write), // 1
	.probe13(io_memPortB_bits_address), // 11
	// dram0
	.probe14(io_dram0_ready), // 1
	.probe15(io_dram0_valid), // 1
	.probe16(io_dram0_bits_write), // 1
	.probe17(io_dram0_bits_address), // 32
	// dram1
	.probe18(io_dram1_ready), // 1
	.probe19(io_dram1_valid), // 1
	.probe20(io_dram1_bits_write), // 1
	.probe21(io_dram1_bits_address), // 32
	// acc
	.probe22(io_acc_ready), // 1
	.probe23(io_acc_valid), // 1
	.probe24(io_acc_bits_instruction_op), // 5
	.probe25(io_acc_bits_instruction_sourceLeft), // 1
	.probe26(io_acc_bits_instruction_sourceRight), // 1
	.probe27(io_acc_bits_instruction_dest), // 1
	.probe28(io_acc_bits_readAddress), // 9
	.probe29(io_acc_bits_writeAddress), // 9
	.probe30(io_acc_bits_accumulate), // 1
	.probe31(io_acc_bits_write), // 1
	.probe32(io_acc_bits_read),  // 1
	// array
	.probe33(io_array_ready), // 1
	.probe34(io_array_valid), // 1
	.probe35(io_array_bits_load), // 1
	.probe36(io_array_bits_zeroes), // 1
	// dataflow
	.probe37(io_dataflow_ready), // 1
	.probe38(io_dataflow_valid), // 1
	.probe39(io_dataflow_bits_kind), // 4
	.probe40(io_error), // 1
	.probe41(io_tracepoint), // 1
	.probe42(io_programCounter) // 32
);
""",
    "Router": """router_ila router_ila_inst (
    .clk(clock),
    // control
    .probe0(control_io_deq_ready), // 1
    .probe1(control_io_deq_valid), // 1
    .probe2(control_io_deq_bits_kind), // 4
    // mem
    .probe3(io_mem_output_ready), // 1
    .probe4(io_mem_output_valid), // 1
    .probe5(io_mem_input_ready), // 1
    .probe6(io_mem_input_valid), // 1
    // host
    .probe7(io_host_dataIn_ready), // 1
    .probe8(io_host_dataIn_valid), // 1
    .probe9(io_host_dataOut_ready), // 1
    .probe10(io_host_dataOut_valid), // 1
    // array
    .probe11(io_array_input_ready), // 1
    .probe12(io_array_input_valid), // 1
    .probe13(io_array_output_ready), // 1
    .probe14(io_array_output_valid), // 1
    // acc
    .probe15(io_acc_input_ready), // 1
    .probe16(io_acc_input_valid), // 1
    .probe17(io_acc_output_ready), // 1
    .probe18(io_acc_output_valid), // 1
    // timeout
    .probe19(io_timeout), // 1
    // tracepoint
    .probe20(io_tracepoint), // 1
    .probe21(io_programCounter), // 32
    // control (additional)
    .probe22(control_io_deq_bits_size) // 13
    );
    """,
    # "Demux": """demux_ila demux_ila_inst (
    #     .clk(clock),
    #     // sel
    #     .probe0(sel_io_deq_ready), // 1
    #     .probe1(sel_io_deq_valid), // 1
    #     .probe2(sel_io_deq_bits), // 1
    #     // in
    #     .probe3(in_io_deq_ready), // 1
    #     .probe4(in_io_deq_valid), // 1
    #     // out 0
    #     .probe5(io_out_0_ready), // 1
    #     .probe6(io_out_0_valid), // 1
    #     // out 1
    #     .probe7(io_out_1_ready), // 1
    #     .probe8(io_out_1_valid) // 1
    # );
    # """,
    # "Demux_2": """demux_2_ila demux_2_ila_inst (
    #     .clk(clock),
    #     // sel
    #     .probe0(sel_io_deq_ready), // 1
    #     .probe1(sel_io_deq_valid), // 1
    #     .probe2(sel_io_deq_bits), // 1
    #     // in
    #     .probe3(in_io_deq_ready), // 1
    #     .probe4(in_io_deq_valid), // 1
    #     // out 0
    #     .probe5(io_out_0_ready), // 1
    #     .probe6(io_out_0_valid), // 1
    #     // out 1
    #     .probe7(io_out_1_ready), // 1
    #     .probe8(io_out_1_valid) // 1
    # );
    # """,
    # "Demux_4": """demux_4_ila demux_4_ila_inst (
    #     .clk(clock),
    #     // sel
    #     .probe0(sel_io_deq_ready), // 1
    #     .probe1(sel_io_deq_valid), // 1
    #     .probe2(sel_io_deq_bits), // 2
    #     // in
    #     .probe3(in_io_deq_ready), // 1
    #     .probe4(in_io_deq_valid), // 1
    #     // out 0
    #     .probe5(io_out_0_ready), // 1
    #     .probe6(io_out_0_valid), // 1
    #     // out 1
    #     .probe7(io_out_1_ready), // 1
    #     .probe8(io_out_1_valid), // 1
    #     // out 2
    #     .probe9(io_out_2_ready), // 1
    #     .probe10(io_out_2_valid) // 1
    # );
    # """,
    # "Mux": """mux_ila mux_ila_inst (
    #     .clk(clock),
    #     // sel
    #     .probe0(sel_io_deq_ready), // 1
    #     .probe1(sel_io_deq_valid), // 1
    #     .probe2(sel_io_deq_bits), // 1
    #     // in 0
    #     .probe3(Queue_io_deq_ready), // 1
    #     .probe4(Queue_io_deq_valid), // 1
    #     // in 1
    #     .probe5(Queue_1_io_deq_ready), // 1
    #     .probe6(Queue_1_io_deq_valid), // 1
    #     // out
    #     .probe7(io_out_ready), // 1
    #     .probe8(io_out_valid) // 1
    # );
    # """,
    # "Mux_1": """mux_1_ila mux_1_ila_inst (
    #     .clk(clock),
    #     // sel
    #     .probe0(sel_io_deq_ready), // 1
    #     .probe1(sel_io_deq_valid), // 1
    #     .probe2(sel_io_deq_bits), // 1
    #     // in 0
    #     .probe3(Queue_io_deq_ready), // 1
    #     .probe4(Queue_io_deq_valid), // 1
    #     // in 1
    #     .probe5(Queue_1_io_deq_ready), // 1
    #     .probe6(Queue_1_io_deq_valid), // 1
    #     // out
    #     .probe7(io_out_ready), // 1
    #     .probe8(io_out_valid) // 1
    # );
    # """,
    #     "Converter": """converter_ila converter_ila_inst (
    # 	.clk(clock),
    # 	// control
    # 	.probe0(control_io_deq_ready), // 1
    # 	.probe1(control_io_deq_valid), // 1
    # 	.probe2(control_io_deq_bits_write), // 1
    # 	.probe3(control_io_deq_bits_address), // 20
    # 	// data in
    # 	.probe4(io_mem_dataIn_ready), // 1
    # 	.probe5(io_mem_dataIn_valid), // 1
    # 	// data out
    # 	.probe6(dataOut_io_enq_ready), // 1
    # 	.probe7(dataOut_io_enq_valid), // 1
    # 	// axi write
    # 	.probe8(io_axi_writeAddress_ready), // 1
    # 	.probe9(io_axi_writeAddress_valid), // 1
    # 	.probe10(io_axi_writeData_ready), // 1
    # 	.probe11(io_axi_writeData_valid), // 1
    # 	.probe12(writeResponse_io_enq_ready), // 1
    # 	.probe13(writeResponse_io_enq_valid), // 1
    # 	// axi read
    # 	.probe14(io_axi_readAddress_ready), // 1
    # 	.probe15(io_axi_readAddress_valid), // 1
    # 	.probe16(readData_io_enq_ready), // 1
    # 	.probe17(readData_io_enq_valid), // 1
    # 	// counters
    # 	.probe18(writeResponseCount), // 8
    # 	.probe19(readResponseCount), // 8
    # 	// timeout
    # 	.probe20(io_timeout), // 1
    # 	// tracepoint
    # 	.probe21(io_tracepoint), // 1
    # 	.probe22(io_programCounter) // 32
    # );
    # """,
    #     "Accumulator": """accumulator_ila accumulator_ila_inst (
    # 	.clk(clock),
    # 	.probe0(io_tracepoint), // 1
    # 	// control
    # 	.probe1(control_io_deq_ready), // 1
    # 	.probe2(control_io_deq_valid), // 1
    # 	.probe3(control_io_deq_bits_address), // 9
    # 	.probe4(control_io_deq_bits_write), // 1
    # 	.probe5(control_io_deq_bits_accumulate), // 1
    # 	// input
    # 	.probe6(io_input_bits_0), // 16
    # 	.probe7(io_input_bits_1), // 16
    # 	.probe8(io_input_bits_2), // 16
    # 	.probe9(io_input_bits_3), // 16
    # 	.probe10(io_input_bits_4), // 16
    # 	.probe11(io_input_bits_5), // 16
    # 	.probe12(io_input_bits_6), // 16
    # 	.probe13(io_input_bits_7), // 16
    # 	.probe14(io_input_ready), // 1
    # 	.probe15(io_input_valid), // 1
    # 	.probe16(io_programCounter) // 32
    # );""",
    #     "DualPortMem": """mem_ila mem_ila_inst (
    # 	.clk(clock),
    # 	.probe0(io_tracepoint), // 1
    # 	// output
    # 	.probe1(Queue_io_enq_ready), // 1
    # 	.probe2(Queue_io_enq_valid), // 1
    # 	.probe3(Queue_io_enq_bits_0), // 16
    # 	.probe4(Queue_io_enq_bits_1), // 16
    # 	.probe5(Queue_io_enq_bits_2), // 16
    # 	.probe6(Queue_io_enq_bits_3), // 16
    # 	.probe7(Queue_io_enq_bits_4), // 16
    # 	.probe8(Queue_io_enq_bits_5), // 16
    # 	.probe9(Queue_io_enq_bits_6), // 16
    # 	.probe10(Queue_io_enq_bits_7), // 16
    # 	// input
    # 	.probe11(q_1_io_deq_ready), // 1
    # 	.probe12(q_1_io_deq_valid), // 1
    # 	.probe13(q_1_io_deq_bits_0), // 16
    # 	.probe14(q_1_io_deq_bits_1), // 16
    # 	.probe15(q_1_io_deq_bits_2), // 16
    # 	.probe16(q_1_io_deq_bits_3), // 16
    # 	.probe17(q_1_io_deq_bits_4), // 16
    # 	.probe18(q_1_io_deq_bits_5), // 16
    # 	.probe19(q_1_io_deq_bits_6), // 16
    # 	.probe20(q_1_io_deq_bits_7), // 16
    # 	// control
    # 	.probe21(q_io_deq_ready), // 1
    # 	.probe22(q_io_deq_valid), // 1
    # 	.probe23(q_io_deq_bits_write), // 1
    # 	.probe24(q_io_deq_bits_address), // 11
    # 	// program counter
    # 	.probe25(io_programCounter) // 32
    # );""",
    # "AccumulatorWithALUArray": """acc_array_ila acc_array_ila_inst (
    # 	.clk(clock),
    # 	.probe0(io_tracepoint), // 1
    # 	// control
    # 	.probe1(control_io_deq_ready), // 1
    # 	.probe2(control_io_deq_valid), // 1
    # 	.probe3(control_io_deq_bits_instruction_op), // 4
    # 	.probe4(control_io_deq_bits_instruction_sourceLeft), // 1
    # 	.probe5(control_io_deq_bits_instruction_sourceRight), // 1
    # 	.probe6(control_io_deq_bits_instruction_dest), // 1
    # 	.probe7(control_io_deq_bits_readAddress), // 9
    # 	.probe8(control_io_deq_bits_writeAddress), // 9
    # 	.probe9(control_io_deq_bits_accumulate), // 1
    # 	.probe10(control_io_deq_bits_write), // 1
    # 	.probe11(control_io_deq_bits_read), // 1
    # 	// program counter
    # 	.probe12(io_programCounter), // 32
    #     // in
    #     .probe13(input__io_deq_ready), // 1
    #     .probe14(input__io_deq_valid), // 1
    #     // out
    #     .probe15(io_output_ready), // 1
    #     .probe16(io_output_valid) // 1
    # );""",
}
modules = list(ila_verilog.keys())
found = list()

# input_file and output_file are readable/writeable interfaces
input_file = sys.stdin
output_file = sys.stdout


def get_module_name(line):
    i = line.find("(")
    return line[7:i]


in_module = False
name = ""

for line in input_file:
    l = line.strip()
    if l[:6] == "module":
        in_module = True
        name = get_module_name(line)
        output_file.write(line)
    elif l[:9] == "endmodule":
        if in_module and name in modules:
            found.append(name)
            output_file.write(ila_verilog[name])
        in_module = False
        output_file.write(line)
    else:
        output_file.write(line)

if set(found) != set(modules):
    raise Exception("some modules not found. expected {} got {}".format(modules, found))
