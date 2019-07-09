---
title: OR1K Marocchino Data Flow
layout: post
date: 2019-03-03 11:00
categories: [ hardware, embedded, openrisc ]
---

In the last article, [Marocchino OpenRISC in action]({% post_url 2019-06-11-or1k_marocchino %})
cpu came about and one could setup a development environment for it.  In this
article let's look a bit deeper on how the Marocchino works.

In this series of posts I would like to take the reader over some key parts
of the Marocchino and it's architecture.

  - Marocchino in Action - A light intro and a guide to getting started with Marocchino
  - Data Flows - (this article) An deep dive into how the Marocchino pipeline is structured
  - A Tomasulo Implementation - How the Marocchino achieves Out-of-Order execution

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

Fetch - 3 stages, immu

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

@@@ Notes From BandVig (Andrey) @@@

 - Reservation station stages (1 entry) Pentium Pro had
 - One per execution unit
 - Stall if busy
 - Optimizations
   * More hazard resolving stages per reservation station (entries)
   * Replace padv with ready/take, but makes DU, stalls difficult
   * Implement ORB

@@@

