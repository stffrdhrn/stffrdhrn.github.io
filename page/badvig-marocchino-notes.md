
### One reservation station per execution unit

There are four reservation station:
 - one for LSU
 - one for 1-clock instructions (shifter, adder, FFL, logic, movhi, l.cmov and computation target address for l.jr/l.jalr)
 - one is shared for multiplier and divider (multi-clocks integer instructions)
 - and one for FPU (multi-clocks floating point instructions)

### Reservation unit stages

Each reservation station consists of two stages: RSRVS-BUSY and RSRVS-EXECUTE.
RSRVS-EXECUTE stage is just registers to provide operands to appropriate
execution module as all hazards resolved.

#### case: No Hazards, Decode to Execution Bypass busy

If:
 - no hazards detected in PIPE-DECODE stage (by RAT analysis)
 - and RSRVS-BUSY stage is empty
 - and (RSRVS-EXECUTE isn’t occupied by a previous instruction or execution unit takes the instruction for processing)
   the next instruction goes from PIPE-DECODE directly to RSRVS-EXECUTE registers.

#### case: Hazards, Decode to Busy Enqueue

If:
 - a hazard detected in PIPE-DECODE stage
 - and RSRVS-BUSY stage is empty
 - or (RSRVS-EXECUTE stage is occupied by a previous instruction and execution unit doesn’t take the instruction for processing)
   the next instruction goes from PIPE-DECODE to RSRVS-BUSY registers

#### case: Resolve Hazard, Busy to Execute

If:
 - RSRVS-BUSY is occupied by an instruction (with or without hazards)
 - All hazards (if present) are resolved
 - and (RSRVS-EXECUTE stage is empty or execution unit takes previous instruction which occupies RSRVS-EXECUTE for processing)
   the RSRVS-BUSY instruction goes to RSRVS-EXECUTE registers.

#### case: Stall

If RSRVS-BUSY is occupied by an instruction (with or without hazards), unit is busy.
If PIPE-DECODE instruction requires to be pushed into such reservation station,
PIPE-DECODE and PIPE-FETCH become stalled till the RSRVS-BUSY become empty.

Only RSRVS-BUSY stage performs hazard resolving. I tried to implement hazard
resolving in RSRVS-EXECUTE stage too to increase effective RSRVS deep. But it
increases LUT consumption noticeably without performance improvement.
 
The RSRVS-BUSY stages also play role of decupling buffers to prevent long
propagation paths for `padv_*` signals. You can find similar technique at output
of PIPE-IFETCH and all execution units. Moreover, there are several of such
decupling buffers inside of FPU pipes.

### Optimizations

**remove padv signals**

I had idea to completely remove `padv_*` signals from design and replace them by
inter-stages ready/takes. But I haven’t found yet how to handle requests from DU
and process l.mfspr / l.mtspr for the case.

Here several notes should be done about l.mfspr / l.mtspr processing. They are
processed in special way. If l.mfspr/l.mtspr is in PIPE-DECODE stage,
PIPE-IFETCH and PIPE-DECODE become stalled. There is no reservation station for
l.mfspr/l.mtspr. These instructions are processed by dedicated logic in
CTRL-module only after OCB become empty that means all hazards are resolved.
While CTRL processes l.mfspr / l.mtspr pipe is stalled.

**OCB and writeback**

As you correctly discovered OCB restores instruction order by granting access to
“common write-back bus” exactly in the order of instructions were issued into
execution units. “Common write-back bus” is distributed among units: each of
them have got `wrbk_*` registers as output. If `padv_wrbk` is high and write back
access is granted by a unit it puts its result in its `wrbk_*` registers. At the
same time all other units put zero in their `wrbk_*` registers. `The wrbk_result1`
is just ORed `wrbk_*` registers of all execution units. The `wrbk_result2` is just
less significant word of double precision FPU’s result (single precision / most
significant word of double precision output goes to `wrbk_result1`). And `padv_wrbk`
is raised if granted access execution unit is ready.
 
`padv_exec` could be treated as ORed `padv_*_rsrvs` plus implicit `padv_op_mXspr` (for
pushing l.mfspr/l.mtspr from PIPE-DECODE to CTRL). If need to push a RSRVS or
l.mfspr/l.mtspr than push OCB with `padv_exec`. For my money my implementation is
more elegance than just OR Улыбка [:-)].

**Other ideas**

I had a lot of ideas:
- to implement real order restore buffer (ORB)
- using ORB to implement real OOO (by pushing hazards-less instructions into execution units ahead others)
- using ORB to implement speculative execution (no such technique in MAROCCHINO)

However, after preliminary analysis I concluded that on the one hand all of
these techniques are very costly in terms of implementation time and LUT
consumption and on the other hand MAROCCHINO is huge already while potential
performance improvement is not obviously high. That’s why I postponed them.



