---
title: OR1K Marocchino
layout: post
date: 2019-06-11 01:37
categories: [ hardware, embedded, openrisc ]
---

In this series of posts I would like to take the reader over some key parts
of the Marocchino architecture.

  - An Intro to the Architecture
  - Implementing Out-of-Order superscalar
  - Marocchino in action

# Tomosolo

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
