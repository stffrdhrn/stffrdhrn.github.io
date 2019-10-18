---
title: OR1K Marocchino - Out of Order Execution
layout: post
date: 2019-10-14 15:37
categories: [ hardware, embedded, openrisc ]
---

*This is an ongoing series of posts on the Marocchino CPU, an open source out-of-order
[OpenRISC](https://openrisc.io) cpu.  In this series we are reviewing the
Marocchino and it's architecture.  If you haven't already I suggest you start of
by reading the intro in [Marocchino in Action]({% post_url 2019-06-11-or1k_marocchino %}).*

In the last article, *Marocchino Instruction Pipeline* we discussed the 
architecture of the CPU.  In this article let's look at how Marocchino achieves
out-of-order execution using the [Tomasulo algorithm](https://en.wikipedia.org/wiki/Tomasulo_algorithm).

## Achieving Out-of-Order Execution

In a traditional pipelined CPU the goal is retire one instruction
per clock cycle.  Any pipseline stalls mean an execution clock cycle will be lost.
One method for reducing pipeline stalls is instruction parallelization.  In 1993
the Intel [Pentium](https://en.wikipedia.org/wiki/P5_(microarchitecture))
processor was one of the first consumer CPU to achieve this with it's [dual U
and V integer pipelines](https://arstechnica.com/features/2004/07/pentium-1/).
The pentium U and V pipelines require [certain coding
techniques](http://oldhome.schmorp.de/doc/opt-pairing.html) to take full
advantage.  Achieving more parallelism requires more sophisticated data hazard
detection and instruction scheduling.  Introduced with the IBM System/360 in the
60's by Robert Tomasulo the *Tomosulo Algorithm* provides the building blocks to
solve these problems.  Generally speaking no special programming is needed to
take advantage of a Tomasulo Algorithm processor.

!DIAGRAM Tomasulo's algorithm'

Though the technique of out-of-order CPU execution with Tomasulo's algorithm had
been designed in the 60's it did not make its way into popular consumer hardware
until the [Pentium Pro](https://en.wikipedia.org/wiki/Pentium_Pro) in the 1995.
Further Pentium revisions such as the Pentium III, Pentium 4 and Core
architectures are based on this same architecture.  Understanding
this architecture is a key to understanding modern CPUs.

!DIAGRAM pentium pro!

The Marocchino implements the Tomasulo algorithm in a CPU that can be synthesized
and run on an FPGA.  Let's dive into the implementation by breaking down the
building blocks used in Tomasulo's algorithm and how they have been implemented in
Marocchino.

## Tomasulo Building blocks

Besides the basic CPU modules like Instruction Fetch, Decode, Register File and
other things.
The basic building blocks that are used in the Tomasulo algorithm are as follows:

![marocchino pipeline diagram](/content/2019/marocchino-pipeline.png)

 - [Reservation Station](https://en.wikipedia.org/wiki/Reservation_station) - A
   queue where decoded instructions are placed before they can be
   executed.  Instructions are placed in the queue with their decoded operation
   and available arguments.  If any arguments are not available they reservation
   station will wait until the arguments are available before executing.
 - Execution Units - The execution units include the Arithmetic
   Logic Unit (ALU), Memory Load/Store Unit or FPU is responsible for performing
   the instruction operation.
 - [Re-order Buffer](https://en.wikipedia.org/wiki/Re-order_buffer) (ROB) - A ring
   buffer which manages the order in which instructions are retired.  In Marocchino
   the implementation is slightly simplified and called the Order Control Buffer (OCB).
 - Instruction Ids - As an instruction is queued into the ROB, or OCB in Marocchino
   it is assigned an Instruction Id which is used to track the instruction in different
   components in Marocchino code this is called the `extaddr`.
 - Register Allocation Table (RAT) - A table used for data hazard resolution.  The RAT
   table has one cell per OpenRISC general purpose register, 32 entries.
   Each RAT cell is addressed by register index and indicates if a register is
   busy being produced by a queued instruction and which instruction will produce it.
 - Common Data Bus - Execution units present their result to all reservation stations
   to provide quick access to pending arguments this is referred to as the common data bus.

### Data Hazards

As mentioned above the goal of a pipelined architecture is to retire 1
instruction per clock cycle.
[Pipelining](https://en.wikipedia.org/wiki/Instruction_pipelining) helps achieve
this by splitting an instruction into pipeline stages i.e. Fetch, Decode,
Execute, Load/Store and Register Write Back.  If one instruction depends on the
results produced by the previous instruction it will be a problem as Register
Write Back of the previous instruction may not complete before registers are
read during the Decode or Execute phase.  This and other types of dependencies
between instructions are called
[hazards](https://en.wikipedia.org/wiki/Hazard_(computer_architecture)).

The Tomasulo algorithm with its Reservation Stations, Register Allocation Tables
and other building blocks try to avoid hazards causing pipeline stalls.  Let's look
at a simple example to see how this is done.

 - instruction 1 - `b = a * 2`
 - instruction 2 - `x = a + b`
 - instruction 3 - `y = x / y`

Here we can see that `instruction 2` depends on `instruction 1` as the addition
of `a + b` cannot be performed until `b` is produced by `instruction 1`.  

Let's assume that `instruction 1` is currently executing on the `MULTIPLY` unit.
The CPU decodes `instruction 2`, Instead of detecting a data hazard and stalling
the pipeline `instruction 2` will be placed in the reservation station of the
`ADD` execution unit.  The RAT indicates that `b` is busy and being produced by
`insruction 1`.  This means `instruction 2` cannot execute right away.  Next, we
can look at `instruction 3` and place it onto the reservation station of the
`DIVIDE` execution unit.  As `instruction 3` has no hazards for `x` and `y` it
can proceed directly to execution, even before `instruction 2` is ready for
execution.

Note, if a required reservation station is full the pipeline will stall.

### Register Renaming

As mentioned above, execution units will present their output onto the common
data bus `wrbk_result` and the data will be written into reservation stations.
Writing the register to the reservation station which may occur before writing
back to the register file.  This what register renaming is.  Because, the
register input does not come directly from the register file.

### Instruction Id

When an instruction is issued it may be registered in the RAT, OCB and Reservation
Station.  It is assigned an Instruction Id for tracking purposes.  In Marocchino
this is called the `extadr` and is `3` bits wide. It is generated by the order manager (OMAN).

Every instruction that is queued by the order manager is designated
an `extadr`.  This allows components like the reservation station and RAT tables
to track when an instruction starts and completes executing.

In traditional Tomasulo documentation this may be referred to as the instruction
id.

 - the OMAN generates the `extaddr` by incrementing a counter used as the OCB ring
   buffer pointer.
 - the OCB broadcasts an `extaddr` to indicate which instruction is to be retired
 - the RAT received the `extaddr` from the OCB output to clear allocation flags
 - the RAT outputs the `extaddr` indicating which queued instruction will produce a
   register
 - the Reservation Station receives the `extaddr` with hazards to track when
   instructions have finished and results are available.
 
### RAT

The register allocation table (RAT), sometimes call register alias table, keeps
track of which registers are currently in progress of being generated by executing
instructions.  This is used to derive and resolve hazards.

Hazards are wait conditions which prevent instructions from executing until
the hazard is resolved.

The RAT table is made of many `rat_cell` components.  The diagram below is slightly
simplified compared to the Marocchino to make understanding easier.

![marocchino RAT diagram](/content/2019/marocchino-rat.png)

There are 32 RAT cells allocated;  1 cell per register.  The register which the cell
is allocated to is stored within `GPR_ADR` in the rat cell.

![marocchino RAT Cell diagram](/content/2019/marocchino-ratcell.png)

The outputs of the RAT cell are:

 - `rat_rd_extadr_o` - indicates which `extadr` instruction has been allocated to
                       generate this register.
                       This will be updated with `decod_extadr_i` when `padv_exec_i` goes high.
 - `rat_rd_alloc_o` - indicates that this register is currently allocated to an
                      instruction which is not yet complete.
                      This will be **set** when `padv_exec_i` goes high, `decod_rfd_we_i` is high,
                      and `dcod_rfd_adr_i` is equal to `GPR_ADR`.


### Reservation Stations

![marocchino reservation station diagram](/content/2019/marocchino-rsrvs.png)

The reservation station receives an instruction from the decode stage and queues
it until all hazards are resolved and the execution unit is free (takes the instruction).

Each reservation station has one busy slot and one execution slot.  In the
Pentium Pro there were 20 reservation station slots, the Marocchino has 5 or 10
depending if you count the execution slots.

Busy registers are populated when the pipeline advance `padv_rsrvs_i` signal comes.
 - `busy_extadr_dxa_r` - is populated with data from `omn2dec_hazards_addrs_i`.  The `busy_extadr_dxa_r`
   register represents the `extadr` to look for which will resolve the A register hazard.
 - `busy_extadr_dxb_r` - same as 'A' but indicates which `extadr` will produce the B register.

 - `busy_hazard_dxa_r` - is populated with data from `omn2dec_hazards_flags_i`.  The `busy_hazard_dxa_r`
   register represents that there is an instruction executing that will produce register A
   which has not yet completed.
 - `busy_hazard_dxb_r` - same as 'A' but indicates that 'B' is not available yet.

 - `busy_op_any_r` - populated with `1` when `padv_rsrvs_i` goes high indicates that
   there is an operation queued.
 - `busy_op_r` - populated with `dcod_op_i`. Represents the operation pending in the queue.
 - `busy_rda_r` - populated with data from `dcod_rfxx_i`. Represents the address of instruction operand A pending in the queue.
 - `busy_rdb_r` - populated with data from `dcod_rfxx_i`. Represents the address of instruction operand B pending in the queue.

The reservation station resolves hazards by watching and comparing `wrbk_extadr_i`
with the `busy_extadr_dxa_r` and `busy_extadr_dxb_r` registers.  If the two match
it means that the instruction producing register A or B has finished writing back
its results and the hazard can be cleared.

When all hazard flags are cleared the contents of `busy_op_r` , `busy_rda_r` and
`busy_rdb_r` will be transferred to `exec_op_any_r`, `exec_op_r`, etc.  When they
are presented on the outputs the execution unit can take them and start processing.

### Execution Units

In Marocchino the execution units (also referred to as execution units)
execute instructions which it receives from the reservation stations.

The execution units in Marocchino are:

 - `or1k_marocchino_int_1clk` - handles integer instructions which can complete
   in 1 clock cycle. This includes `SHIFT`, `ADD`, `AND`, `OR` etc.
 - `or1k_marocchino_int_div` - handles integer `DIVIDE` operations.
 - `or1k_marocchino_int_mul` - handles integer `MULTIPLY` operations.
 - `or1k_marocchino_lsu` - handles memory load store operations.  It interfaces
   with the data cache, MMU and memory bus.
 - `pfpu_marocchino_top` - handles floating point operations.  These include
   `ADD`, `MULTIPLY`, `I2F` etc.

Handshake signals between the reservation station and execution units are used
to issue operations to execution units.

![marocchino execution unit handshake diagram](/content/2019/marocchino-handshake.png)

The `taking_op_i` is the signal from the execution unit signalling it has
received the op and the reservation station will clear all `exec_*_o` output
signals.

The `unit_free_o` output signals the control unit that the reservation station
is free and can be issued another instruction.  It goes high when all hazards
are cleared.  ??


### Order Control Buffer

```
DIAGRAM
```

Similar to reorder buffer?  7 entries
Pentium Pro 40 entries

--------------
TIMING DIAGRAM
--------------

### Conclusion

Tomasulo's algorithm is still relevant today.  Marocchino provides an accessible
implementation.

## Further Reading
 - Intel architecture manuals
   - [Intel Architecture Software Developers Manual](/content/2019/volref2419002.pdf) see
      - section 2.1 brief history of the intel architecture
      - section 2.4 introduction to the P6 microarchitecture
   - [Pentium Pro Datasheet](/content/2019/Intel_PentiumPro.pdf) drr
      - section 2.2 The Pentium Pro Processor Pipeline
 - [University of Washington, Computer Architecture](https://courses.cs.washington.edu/courses/csep548/06au/lectures.html)
   - [Re-order buffer](https://courses.cs.washington.edu/courses/cse548/06wi/slides/reorderBuf.pdf)
 - [UCSD, Graduate Computer Architecture](https://cseweb.ucsd.edu/classes/wi13/cse240a/)
 - [Intel Core 2](https://en.wikipedia.org/wiki/Intel_Core_(microarchitecture))
 - [Intel Pentium Pro](https://en.wikipedia.org/wiki/Pentium_Pro)
