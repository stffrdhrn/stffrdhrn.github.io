---
title: OR1K Marocchino
layout: post
date: 2019-10-14 15:37
categories: [ hardware, embedded, openrisc ]
---

*This is an ongoing series of posts on the Marocchino CPU, an open source super-scalar
[OpenRISC](https://openrisc.io) cpu.  In this series we will review the
Marocchino and it's architecture.  If you haven't already I suggest you start of
by reading the intro in [Marocchino in Action]({% post_url 2019-06-11-or1k_marocchino %}).*

In the last article, *Marocchino Instruction Pipeline* we discussed the 
architecture of the CPU.  In this article let's look at how Marocchino acheives
super-scalar execution using the [Tomasulo algorithm](https://en.wikipedia.org/wiki/Tomasulo_algorithm).

## Achieving Super-scalar Execution

In a traditional pipelined CPU the goal is to execute or retire one instruction
per clock cycle.  To acheive more we require instruction parallelization.  In
1993 the Intel [Pentium](https://en.wikipedia.org/wiki/P5_(microarchitecture))
processor was the first consumer CPU to achieve this with it's dual U and V data
pipelines.  Acheiving more parallelism requires more sophisticated data hazard
detection and instruction scheduling.  Introduced with the IBM System/360 in the
60's by Robert Tomasulo the *Tomosulo Algorithm* provides the building blocks to
solve these problems.

!DIAGRAM tomasulo's algorithm'

Though the technique of out-of-order CPU execution with Tomasulo's algotithm had
been designed in the 60's it did not make its way into popular consumer hardware
until the [Pentium Pro](https://en.wikipedia.org/wiki/Pentium_Pro) in the 1995.
Further Pentium revisions such as the Pentium III, Pentium 4 and Core
architectures are said to be based on this same architecture.  Understanding
this architecture is a key to understanding modern CPUs.

!DIAGRAM pendium pro!

The Marochino implements the Tomasulo algorithm in a CPU that can be synthesized
and run on an FPGA.  Let's dive into the implementation by breaking down the
building blocks used in Tomasulo's algorithm and they have been implemented in
Marocchino.

## Tomosulo Building blocks

The basic building blocks that are used in the Tomasulo algorithm are as follows:

!DIAGRAM overall pipeline!

 - [Reservation Station](https://en.wikipedia.org/wiki/Reservation_station) - A fifo
   queue where decoded instructions are placed before they can be
   executed.  Instructions are placed in the queue with their decoded operation
   and available arguments.  If any arguments are not available they reservation
   station will wait until the arguments are available before executing.
 - Execution Units - The execution unit of functional unit, such as an Arithmatic
   Logic Unit (ALU), Memory Load/Store Unit or FPU is resposible for performing
   the instruction operation.
 - [Re-order Buffer](https://en.wikipedia.org/wiki/Re-order_buffer) (ROB) - A ring
   buffer which manages the order in which instructions are retired.  In Marocchino
   the implementation is slightly simplified and called the Order Control Buffer (OCB).
 - Instruction Ids - As an instruction is queued into the ROB, or OCB in Marocchino
   it is assigned an Instruction Id which is used to track the instruction in different
   compontents in Marocchino code this is called the `extaddr`.
 - Register Allocation Table (RAT) - A table used for data hazard resolution.  The RAT
   table has one cell per OpenRISC general purpose register, 32 entries.
   Each RAT cell is addressed by register index and indicates if a register is
   busy being produced by a queued instruction and which instruction will produce it.
 - Common Data Bus - Execution units present their result to all reservation stations
   to provide quick access to pending arguments this is referred to as the common data bus.

### Data Hazards

As mentioned above the goal of a pipelined architecture is to retire 1
instruction per clock cycle.
[Pipelining](https://en.wikipedia.org/wiki/Instruction_pipelining) helps acheive
this by splitting an instruction into pipeline stages i.e. Fetch, Decode,
Execute, Load/Store and Register Write Back.  If one instruction depends on the
results produced by the previous instruction it will be a problem as Register
Write Back of the previous instruction may not have completed.  This and other
types of dependencies between instructions are called
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
`ADD` exection unit.  The RAT indicates that `b` is busy and being produced by
`insruction 1`.  This means `instruction 2` cannot execute right away.  Next, we
can look at `instruction 3` and place it onto the reservation station of the
`DIVIDE` execution unit.  As `instruction 3` has no hazards for `x` and `y` it
can proceed directly to execution, even before `instruction 2` is ready for
execution.

Note, if a required reservation station is full the pipeline will stall.

### Register Renaming

As mentioned above, functional units will present their output onto the common
data bus `wrbk_result` and the data will be written into reservation stations.
Writing the register to the resevation station which may occur before writing
back to the resgister file.  This what register renaming is.  Because, the
register input does not come directly from the register file.

### Reservation Stations

```
DIAGRAM
```
Notes:
 - busy_hazards_dxa1 - should be named busy_flag_dxa1
 - omn2dec - should be omn2rsrvs_ (order manager to reservation station)
 - busy_dxa1_mux_wrbk - should be `busy_flag_dxa1_clear` as it goes high to
   clear the busy flag.

The reservation station receives a instruction from the decode stage and queues
it until all hazards are resolved and the functional unit is free (takes the instruction).

Internally we have registers for resolving hazards and storing the pending operations.

Busy registers are populated when the pipeline advance `padv_rsrvs_i` signal comes.
 - busy_extadr_dxa_r - is populated with data from `omn2dec_hazards_addrs_i`.  The `busy_extadr_dxa_r`
   register represents the `extadr` to look for which will resolve the A register hazard.
 - busy_extadr_dxb_r - same as 'A' but inticates which `extadr` will produce the B register.

 - busy_hazard_dxa_r - is populated with data from `omn2dec_hazards_flags_i`.  The `busy_hazard_dxa_r`
   register represents that there is an instruction executing that will produce register A
   which has not yet completed.
 - busy_hazard_dxb_r - same as 'A' but indicates that 'B' is not available yet.

 - busy_op_any_r - populated with `1` when padv_rsrvs_i goes high indicates that
   there is an operation queued.
 - busy_op_r - pupulated with `dcod_op_i`. Represents the operation pending in the queue.
 - busy_rda_r - pupulated with data from `dcod_rfxx_i`. Represents the address of instruction operand A pending in the queue.
 - busy_rdb_r - pupulated with data from `dcod_rfxx_i`. Represents the address of instruction operand B pending in the queue.

The reservation station resolves hazards by watching and comparing `wrbk_extadr_i`
with the `busy_extadr_dxa_r` and `busy_extadr_dxb_r` registers.  If the two match
it means that the instruction producing register A or B has finished writing back
its results and the hazard can be cleared.

When all hazard flags are cleared the contents of `busy_op_r` , `busy_rda_r` and
`busy_rdb_r` will be transfered to `exec_op_any_r`, `exec_op_r`, etc.  When they
are presented on the outputs the execution unit can take them and start processing.

Handshake signals between the reservation station and execution units.

The `taking_op_i` is the signal from the exeuction unit signalling it
has received the op and will clear all `exec_*_o` output signals.

The `unit_free_o` output signals the control unit that the reservation station
is free and can be issued another instruction.  It goes high when all hazards
are cleared.

The RAT and OCB and Reservation also has a concept called `extadr` this is.

The allocation ID, the ID of the instruction in the order control
buffer.

This is used to know when an instruction is completed.

These are all generated by the order manager (OMAN).

### Instruction Id

The extadr.  Every instruction that is queued by the order manager is designated
an `extadr`.  This allows components like the reservation station and RAT tables
to track when an instruction starts and completes executing.

In traditional tomasulo documentation this may be referred to as the insruction
id.

 - the OMAN generates the extaddr by incrementing a counter used as the OCB ring
   buffer pointer.
 - the OCB outputs to extaddr to indicate which instruction is to be retired
 - the RAT received the extaddr from the OCB output to clear allocation flags
 - the RAT outputs the extaddr indicating which queued instruction will produce a
   register
 - the Reservation Station receives the extaddr with hazards to track when
   insructions have finished and results are available.
 
### RAT

```
DIAGRAM
```

The register allocation table (RAT) keeps track of which registers are currently
in progress of being generated by executing instructions.  This is used to derive
and resolve hazards.

Hazards are wait conditions which prevend block instructions from executing until
the hazard is resolved.

The RAT table is made of many `rat_cell` components.  The diagram below is slightly
simplified compared to the or1k_marocchino to make understanding easier.

There are 32 rat_cells allocated.  1 per register.  The register which the cell
is allocated to is stored withing `GPR_ADR` in the rat cell.

The outputs of the rat_cell are:

 - `rat_rd_extadr_o` - indicates which `extadr` instruction has been allocated to
                       generate this register.
                       This will be updated with `decod_extadr_i` when `padv_exec_i` goes high.
 - `rat_rd_alloc_o` - indicates that this register is currently allocated to an
                      instruction which is not yet complete.
                      This will be **set** when `padv_exec_i` goes high, `decod_rfd_we_i` is high,
                      and `dcod_rfd_adr_i` is equal to `GPR_ADR`.


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

Tomasulo's algorith is stil relavent today.  Marocchino provides and accessable
implementation.

@@@ Notes From BandVig (Andrey) @@@

 - Reservation station stages (1 entry) Pentium Pro had
 - One per execution unit
 - Stall if busy
 - Optimizations
   * More hazard resolving stages per reservation station (entries)
   * Replace padv with ready/take, but makes DU, stalls difficult
   * Implement ORB

@@@


## Marocchino in Action

LUT count vs mor1kx

Timing diagram
 - reservation station used
 - branch

## Full featured FPU

Some things I believe are different from the Tomasulo algorithm are:

   - Instead of multiple reservation station entries per execution unit there is only
     one per functional unit in marocchino (it should be possible to add more)

   - Marocchino does instruction retirement using the Order Control Buffer (OCB)
     with seems to be equivalent to the traditional Re-order buffer.


ORFPX64A32

## Further Reading
 - Tomasulo's Algorithm
 - University of Washington, Computer Architecture - https://courses.cs.washington.edu/courses/csep548/06au/lectures.html
 - UCSD, Graduate Computer Architecture - https://cseweb.ucsd.edu/classes/wi13/cse240a/
 - Intel Core 2 - https://en.wikipedia.org/wiki/Intel_Core_(microarchitecture)
 - Intel Pentium Pro - https://en.wikipedia.org/wiki/Pentium_Pro
