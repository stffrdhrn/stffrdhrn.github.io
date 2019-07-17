---
title: OR1K Marocchino Data Flow
layout: post
date: 2019-03-03 11:00
categories: [ hardware, embedded, openrisc ]
---

This is an ongoing series of posts on the Marocchino CPU, an open source out-of-order
cpu.  In this series we will review the Marocchino and it's architecture.

  - Marocchino in Action - A light intro and a guide to getting started with Marocchino
  - Data Flows - (this article) An deep dive into how the Marocchino pipeline is structured
  - A Tomasulo Implementation - How the Marocchino achieves Out-of-Order execution

In the last article, [Marocchino OpenRISC in action]({% post_url 2019-06-11-or1k_marocchino %})
we discussed the history of the CPU and how to setup setup a development environment for it.  In this
article let's look a bit deeper into how the Marocchino CPU works.

Let's look at how the data flows through the Marocchino [pipeline](https://en.wikipedia.org/wiki/Instruction_pipelining).

## Marocchino Architecture

The Marocchino source code is available on
[github](https://github.com/openrisc/or1k_marocchino/tree/master/rtl/verilog)
and is pretty easy to browse.

```
 ::GITHUB SCREENSHOT::
```

At first glance of the code the Marocchino may look like a traditional 5 stage
RISC pipeline.  It has fetch, decode, execution, load/store and register write
back modules which you might picture in your head as follows:

```
         |
         V
[     FETCH                  ]
  (or1k_marocchino_fetch.v)

[     DECODE                 ]
  (or1k_marocchino_decode.v)

[      PIPELINE CRTL         ]
  (or1k_marocchino_ctrl.v)
       EXECUTE
  (or1k_marocchino_int_1clk.v) ALU
  (or1k_marocchino_int_div.v)  DIVISION
  (or1k_marocchino_int_mul.v)  MULTIPLICATION

       LOAD STORE
[  (or1k_marocchino_lsu.v)   ]

[      WRITE BACK            ]
  (or1k_marocchino_rf.v)
          |
          V

```

However, once you look a bit closer you notice somethings that are different.
The top-level module
[or1k_marocchino_cpu](https://github.com/openrisc/or1k_marocchino/blob/master/rtl/verilog/or1k_marocchino_cpu.v)
connects the modules and shots:

 - Between the decode and functional units there are reservation stations.
 - Along with the control unit, there is an order manager module.
 - The load/store functional unit is done as part of the execution stage.

What this is is an out-of-order pipeline with in order multiple dispatch
cpu using many of the algorithms from Tomosulo. 

The architecture is as per the below diagram.

![marocchino pipeline diagram](/content/2019/marocchino-pipeline.png)

## Pipeline Controls

The marocchino has two modules for coordinating pipeline stage data propagation.  The
control unit and the order manager.

### Control Unit

The Control unit of the CPU is in charge of watching over the pipeline stages
and signalling when operations can transfer from one stage to the next.  The
marocchino does this with a series of pipeline advance (`padv_*`) signals.  In general for the
best efficiency all `padv_*` wires should be high at all times allowing
instructions to progress on every clock cycle.  But as we will see in reality, this
is difficult to achieve due to pipeline stall scenarios like cache misses and branch predition misses.
The `padv_*` signals include:

#### padv_fetch_o

The `padv_fetch_o` signal instructs the instruction fetch unit to progress.
Internally the fetch unit has 3 stages.  The instruction fetch unit interacts
with the instruction cache and instruction [memory management unit](https://en.wikipedia.org/wiki/Memory_management_unit) (MMU).
The `padv_fetch_o` signal goes low when the decode module is busy (`dcod_emtpy_i` is low).
The signal `dcod_empty_i` comes from the Decode module and indicates that an
instruction can be accepted by the decode stage.

#### padv_dcod_o

The `padv_dcod_o` signal instructs the instruction decode stage to output decoded
operands.
The decode unit is one stage, if `padv_dcod_o` is high, it will decode the instruction
input every cycle.
The `padv_dcod_o` signal goes low if the destination reservation station for the
operands cannot accept an instruction.

#### padv_exec_o and padv_*_rsrvs_o

The `padv_exec_o` signal to order manager enqueues decoded ops into the Order Control
Buffer (OCB).  The OCB is a [FIFO](https://en.wikipedia.org/wiki/FIFO_(computing_and_electronics))
queue which keeps track of the order instructions have been decoded.

The `padv_*_rsrvs_o` signal to one of the reservation stations
enables registering of an instruction into a reservation station.  There is one
`padv_*_rsrvs_o` signal and reservation station per functional unit. They are:

 - `padv_1clk_rsrvs_o` - to the reservation station for single clock ALU operations
 - `padv_muldiv_rsrvs_o` - to the reservation station for multiply and divide
   operations.  Divide operations take 32 clock cycles.  Multiply operations
   execute with 2 clock cycles.
 - `padv_fpxx_rsrvs_o` - to the reservation station for the [floating point unit](https://en.wikipedia.org/wiki/Floating-point_arithmetic)
   (FPU).  There are multiple FPU operations including multiply, divide, add,
   subtract, comparison and conversion between integer and floating point. 
 - `padv_lsu_rsrvs_o` - to the reservation station for the load store unit.  The
   load store unit will load data from [memory](https://en.wikipedia.org/wiki/Random-access_memory)
   to register or store data from registers to memory.  It interacts with the
   data cache and data MMU.

Both `padv_exec_o` and `padv_*_rsrvs_o` are dependent on the execution units being
ready and both signals will go high or low at the same time.

#### padv_wrbk_o

The `padv_wrbk_o` signal to the execution units will go active when `exec_valid_i` is active
and will finalize writing back the execution results.  The `padv_wrbk_o` signal to
the order manager will retire the oldest instruction from the OCB.

An astute reader would notice that there are no pipeline advance (`padv_*`)
signals to each of the functional units.  This is where the order manager comes
in.

### Order Manager Signals

As the OCB is a FIFO queue the output port of the represents the oldest non
retired instruction.  The `exec_valid_o` signal to the control unit will go
active when the `*_valid_i` signal from the execution unit and the OCB output
instruction matches.

This is represented by this `assign` in [or1k_marocchino_oman.v](https://github.com/openrisc/or1k_marocchino/blob/master/rtl/verilog/or1k_marocchino_oman.v#L505):

```
  assign exec_valid_o =
    (op_1clk_valid_l          & ~ocbo[OCBTC_JUMP_OR_BRANCH_POS]) | // EXEC VALID: but wait attributes for l.jal/ljalr
    (exec_jb_attr_valid       &  ocbo[OCBTC_JUMP_OR_BRANCH_POS]) | // EXEC VALID
    (div_valid_i              &  ocbo[OCBTC_OP_DIV_POS])         | // EXEC VALID
    (mul_valid_i              &  ocbo[OCBTC_OP_MUL_POS])         | // EXEC VALID
    (fpxx_arith_valid_i       &  ocbo[OCBTC_OP_FPXX_ARITH_POS])  | // EXEC VALID
    (fpxx_cmp_valid_i         &  ocbo[OCBTC_OP_FPXX_CMP_POS])    | // EXEC VALID
    (lsu_valid_i              &  ocbo[OCBTC_OP_LS_POS])          | // EXEC VALID
                                 ocbo[OCBTC_OP_PUSH_WRBK_POS];     // EXEC VALID
```

The OCB ensures that instructions are retired in the same order that they are
decoded.

The `grant_wrbk_*_o` signal to the functional units will go active depending on
the OCB output port instruction.

This is represented by this `assign` in
[or1k_marocchino_oman.v](https://github.com/openrisc/or1k_marocchino/blob/master/rtl/verilog/or1k_marocchino_oman.v#L402):

```
  // Grant Write-Back-access to units
  assign grant_wrbk_to_1clk_o        = ocbo[OCBTC_OP_1CLK_POS];
  assign grant_wrbk_to_div_o         = ocbo[OCBTC_OP_DIV_POS];
  assign grant_wrbk_to_mul_o         = ocbo[OCBTC_OP_MUL_POS];
  assign grant_wrbk_to_fpxx_arith_o  = ocbo[OCBTC_OP_FPXX_ARITH_POS];
  assign grant_wrbk_to_lsu_o         = ocbo[OCBTC_OP_LS_POS];
  assign grant_wrbk_to_fpxx_cmp_o    = ocbo[OCBTC_OP_FPXX_CMP_POS];
```

The `grant_wrbk_*_o` signal along with the `padb_wrbk_o` signal signal an
execution unit that it can write back its result to the register file / RAT /
reservation station.

The `padv_wrbk_i` signal from the control unit to the order manager also takes
care of dequeuing the last instruction from the OCB.  With that the instruction
is retired.

## Conclusion

The Marocchino instruction pipeline is not very complicated while still being
full featured including Caches, MMU and FPU.  We have mentioned
a few structures such as Reservation Station and RAT which we haven't gone into
much details on.  These help implement out-of-order superscalar execution using
Tomasulo's algorithm.  In the next article we will go into more details on these
components and how Tomasulo works.
