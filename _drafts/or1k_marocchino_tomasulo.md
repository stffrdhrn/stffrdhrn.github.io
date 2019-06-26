---
title: OR1K Marocchino
layout: post
date: 2019-06-11 01:37
categories: [ hardware, embedded, openrisc ]
---

In the beginning of 2019 I had finished the [OpenRISC GCC port](/software/embedded/openrisc/2018/02/03/openrisc_gcc_rewrite.html) and was
working on building up toolchain test and verification support using the [mor1kx](https://github.com/openrisc/mor1kx)
soft core.  Part of the mor1kx's feature set is the ability to swap out
different pipeline arrangements to configure the CPU for performance or resource
usage. Each pipeline is named after an Italian coffee, we have Cappuccino,
Espresso and Pronto-Espresso.  One of these pipelines which has been under
development but never integrated into the main branch was the Marocchino.  I had
never paid much attention to the Marocchino pipeline.

Around the same time author or Marocchino sent a mail mentioning he could not
used the new GCC port as is was missing 64-bit FPU support.  Therefore, I set
out to start working on adding single and double precision floating point
support to the OpenRISC gcc port.  My verification target would be the
Marocchino pipeline.

After some initial investigation I found this CPU was much more than a new pipeline
for the mor1kx with a special FPU.  The marocchino has morphed into a complete re-implementation
of the [OpenRISC 1000 spec](https://openrisc.io/architecture).  Seeing this we split
the marocchino out to it's [own repository](https://github.com/openrisc/or1k_marocchino)
where it could grow on its own.  Of course maintaining the Italian coffee name.

With features like out-of-order execution using [Tomasulo's algorithm](https://en.wikipedia.org/wiki/Tomasulo_algorithm), 64-bit FPU
operations using register pairs, MMU, Instruction caches, Data caches and a
clean verilog code base the Marocchino is a advanced to say the least.  In this
article I would like to take you through the architecture.

I would claim this is one of the most advanced implementations of a superscalar
open source CPU core.

In this series of posts I would like to take the reader over some key parts
of the Marocchino architecture.

  - An Intro to the Architecture
  - Implementing Out-of-Order superscalar
  - Marocchino in action

## Marocchino Architecture

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

 - Between the decode and functional units there are reservation stations.
 - Along with the control unit, there is an order manager module.
 - The load/store functional unit is done as part of the execution stage.

But then we start to look at these files:

 - `or1k_marocchino_ocb.v` - order control buffer
 - `or1k_marocchino_oman.v` - order manager
 - `or1k_marocchino_rat_cell.v` - registery allocation table cell
 - `or1k_marocchino_rsrvs.v` - reservation station

```
                 ICACHE
		     |
		     V
       padv>[     FETCH                  ]
	      (or1k_marocchino_fetch.v)
[ Pipeline ]         V
       padv>[     DECODE                 ]  -->             [ OMAN  ]
[ Control  ]  (or1k_marocchino_decode.v)                    [ [RAT] ]
                     V
       padv>[   RESERVATION STATIONs     ]  <-------------- [       ]
	      (or1k_marocchino_rsrvs.v)
                     V                                        [OCB]
		   EXECUTE
       padv>[ (or1k_marocchino_int_1clk.v) ALU              [       ]
	      (or1k_marocchino_int_div.v)  DIVISION <-----  [       ]
	      (or1k_marocchino_int_mul.v)  MULTIPLICATION
	       (pfpu_marocchino/*)         FPU
    DCACHE---->(or1k_marocchino_lsu.v)     LOAD/STORE
   padv_wrbk---------|------------------------------------>[       ]
                   {result}
	    [      WRITE BACK            ] <---(we)-------- [       ]
	      (or1k_marocchino_rf.v)

```

What this is is a 6 stage out-of-order pipeline with in order multiple dispatch
cpu using many of the algorithms from Tomosulo. 

## Pipeline Controls

The marocchino has two modules for coordinating pipeline stage transfer.  The
control unit and the order manager.

### Control Unit Signals

The Control unit of the CPU is in charge of watching over the pipeline stages
and signalling when operations can transfer from one stage to the next.  The
marocchino does this with a series of pipeline advance (`padv_*`) signals.  In generals for the
best efficiency all `padv_*` wires should be high at all time allowing
instructions to progress on every clock cycle.  The `padv_*` signals include:

*Fetch*
The `padv_fetch_o` signal instructs the instruction fetch unit to progress.
Internally the fetch unit has 3 stages.

*Decode*
The `padv_dcod_o` signal instructs the instruction decode unit to output decodeded ops


*Dynamic Scheduling*
`padv_exec_o` – to order manager, enqueues the decode ops into the Order Control
Buffer `padv_*_rsrvs_o` – to one of the reservation stations based on decode ops
enables registering of an instruction into a reservation station

Both `padv_exec_o` and `padv_*_rsrvs_o` are dependent on the execution units being
ready and both signals will go active at the same time.

*Writeback*
`padv_wrbk_o` – to the execution units will go active when `exec_valid_i` is active
and will finalize writing back the execution results.  The `padv_wrbk_o` signal to
the order manager will retire the oldest instruction from the OCB.

### Order Manager Signals

The output port of the Order Control Buffer (OCB) represents the oldest non
retired instruction.  The `exec_valid_o` signal will go active when the
`*_valid_i`
signal for the execution unit and the OCB output instruction matches.  For
example if the output of the OCB represents a multiply operation and
`1clk_valid_i` is active the `exec_valid_o` will be low, because `mul_valid_i` is not
yet active.

The OCB ensures that instructions are retired in the same order that they are
decoded.

The `grant_wrbk_*_o` signal will go active along with the `exec_valid_o` signal when
the `*_valid_i` signal execution unit and the OCB output instruction matches. The
`grant_wrbk_*_o` signal will signal the execution unit that it can write back its
result to the register file / RAT / reservation station.

### Register Renaming

As mentioned above, functional units will present their output onto the common
data bus `wrbk_result` and the data will be written into reservation stations.
Writing the register to the resevation station which may occur before writing
back to the resgister file.  This what register renaming is.  Because, the
register input does not come directly from the register file.

@@@ Notes From BandVig (Andrey) @@@

 - Reservation station stages (1 entry) Pentium Pro had
 - One per execution unit
 - Stall if busy
 - Optimizations
   * More hazard resolving stages per reservation station (entries)
   * Replace padv with ready/take, but makes DU, stalls difficult
   * Implement ORB

@@@

## Achieving out of order execution

In a traditional pipelined CPU the goal is to execute or retire one instruction
per clock cycle.  To acheive more we require instruction parallelization.  Introduced
in the IBM s390 in the 60's by Robert Tomosulo the [Tomosulo Algorithm](https://en.wikipedia.org/wiki/Tomasulo_algorithm)
provides a method to acheive this.  The marochino uses many of these technique's
except for the Re-order buffer (ROB) which allows for out-of-order instruction
retierments.  Instead marochino implements a more simple Order control buffer
(OCB) that allows acheiving parallel execution, but in order instruction retirement.

Though the technique of out-of-order CPU execution has been designed in the 60's
it did not make its way into popular consumer hardware until the [Pentium Pro](https://en.wikipedia.org/wiki/Pentium_Pro)
in the 1990's.  Further Pentium revisions such as the Pentium III, Pentium 4 and
Core architectures are said to be based on this same architecture.  Understanding
this architecture is a key to understanding modern CPUs.

## Tomosulo Building blocks

Some things I believe are different from the Tomasulo algorithm are:

   - Instead of multiple reservation station entries per execution unit there is only
     one per functional unit in marocchino (it should be possible to add more)

   - Marocchino does instruction retirement using the Order Control Buffer (OCB)
     with seems to be equivalent to the traditional Re-order buffer.


### Primer for Marocchino

Before we get into the building blocks we shall discuss

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
 

### Reservation Stations

```
DIAGRAM
```
Notes:
 - busy_hazards_dxa1 - should be named busy_flag_dxa1
 - omn2dec - should be omn2rsrvs_ (order manager to reservation station)
 - busy_dxa1_mux_wrbk - should be `busy_flag_dxa1_clear` as it goes high to
   clear the busy flag.

Hazards are expressed as `d2b1`.  You can think about

  d = a + b

The reservation station receives a instruction from the decode stage and queues
it until all hazards are resolved and the functional unit is free (takes the instruction).

Internally we have registers for resolving hazards and storing the pending operations.

Busy registers are populated when the pipeline advance `padv_rsrvs_i` signal comes.
 - busy_extadr_dxa_r - is populated with data from `omn2dec_hazards_addrs_i`.  The `busy_extadr_dxa_r`
   register represents the extadr to look for which will resolve the A register hazard.
 - busy_extadr_dxb_r - 

 - busy_hazard_dxa_r - is populated with data from `omn2dec_hazards_flags_i`.  The `busy_hazard_dxa_r`
   register represents that there is an instruction executing that will produce register A
   which has not yet completed.
 - busy_hazard_dxb_r - 

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

Handshake signals.

The `taking_op_i` is the signal from the exeuction unit signalling it
has received the op and will clear all `exec_*_o` output signals.

The `unit_free_o` output signals the control unit that the reservation station
is free and can be issued another instruction.  It goes high when all hazards
are cleared.

The RAT and OCB and Reservation also has a concept called `extadr` this is...hr745.

The allocation ID, the ID of the instruction in the order control
buffer.

This is used to know when an instruction is completed.

These are all generated by the order manager (OMAN).

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
                      instruction which is pending allocation.
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

## Marocchino in Action

LUT count vs mor1kx

Timing diagram
 - reservation station used
 - branch

## Full featured FPU

ORFPX64A32

## Further Reading
 - Tomasulo's Algorithm
 - University of Washington, Computer Architecture - https://courses.cs.washington.edu/courses/csep548/06au/lectures.html
 - UCSD, Graduate Computer Architecture - https://cseweb.ucsd.edu/classes/wi13/cse240a/
 - Intel Core 2 - https://en.wikipedia.org/wiki/Intel_Core_(microarchitecture)
 - Intel Pentium Pro - https://en.wikipedia.org/wiki/Pentium_Pro
