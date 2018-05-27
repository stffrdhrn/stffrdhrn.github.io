---
title: GCC Important Passes
layout: post
date: 2018-05-21 22:37
categories: [ software, embedded, openrisc ]
---

While working on the OpenRISC gcc port I found if difficult to understand at
first what all of the compile passes were.  There are so many.

## Quick Tips

 - When debugging a compiler problems use the `-fdump-rtl-all` and
  `-fdump-tree-all` flags to debug where things go wrong.
 - The numbers in the dump output files indicate the order in which a pass was run. For
   example between `test.c.235r.vregs` and `test.c.234r.expand` the expand pass is run
   before vregs, and there were not passes run inbetween.
 - We may see `cfg` thoughout the gcc source, this is not configuration, but
   [control flow graph](https://en.wikipedia.org/wiki/Control_flow_graph).

First off how do we see the passes, there are basically two types:

 IPA - Interprocedural analysis passes look at the call graph created during `pass_build_cgraph_edges`, a Tree pass.
 Tree - GIMPLE
 RTL - Register Transfer Language, converts GIMPLE to Assembly

Here I will concentrate on RTL as that is what most of the backend influences.

You can find a list of all passes in `gcc/passes.def`.

The passes interesting:

 - expand
 - vregs
 - split
 - combine
 - ira
 - reload (now lra)

## The Expand Pass

```
  6631 gcc/cfgexpand.c
    28 gcc/cfgexpand.h
  6659 total
```

## The IRA Pass

```
  3514 gcc/ira-build.c
  5661 gcc/ira.c
  4956 gcc/ira-color.c
   824 gcc/ira-conflicts.c
  2399 gcc/ira-costs.c
  1323 gcc/ira-emit.c
   224 gcc/ira.h
  1511 gcc/ira-int.h
  1595 gcc/ira-lives.c
 22007 total
```

## The LRA Pass (Reload)

```
  1816 gcc/lra-assigns.c
  2608 gcc/lra.c
   362 gcc/lra-coalesce.c
  7072 gcc/lra-constraints.c
  1465 gcc/lra-eliminations.c
    44 gcc/lra.h
   534 gcc/lra-int.h
  1450 gcc/lra-lives.c
  1347 gcc/lra-remat.c
   822 gcc/lra-spills.c
 17520 total
```

During reload constraints are used, i.e. `"r"` or `"m"` or target speciic ones
like `"O"`.

Before reload the predicates are used, i.e `general_operand` or target specific
ones like `reg_or_s16_operand`.

If we look at a `test.c.278r.reload` dump file we will a few sections.

 - Local
 - Pseudo live ranges
 - Inheritance
 - Assignment
 - **Repeat**

```
********** Local #1: **********

	   Spilling non-eliminable hard regs: 1
            1 Non-pseudo reload: reject+=2
            1 Non input pseudo reload: reject++
            Cycle danger: overall += LRA_MAX_REJECT
          alt=0,overall=609,losers=1,rld_nregs=1
            alt=1: Bad operand -- refuse
            alt=2: Bad operand -- refuse
            alt=3: Bad operand -- refuse
            0 Non-pseudo reload: reject+=2
            0 Spill pseudo into memory: reject+=3
            Using memory insn operand 0: reject+=3
            0 Non input pseudo reload: reject++
            1 Non-pseudo reload: reject+=2
            1 Non input pseudo reload: reject++
          alt=4,overall=24,losers=2,rld_nregs=1
            1 Spill Non-pseudo into memory: reject+=3
            Using memory insn operand 1: reject+=3
            1 Non input pseudo reload: reject++
          alt=5,overall=13,losers=1,rld_nregs=0
	 Choosing alt 5 in insn 10:  (0) r  (1) m {*movsi_internal}
      Creating newreg=44, assigning class GENERAL_REGS to addr r44
   10: r11:SI=[r44:SI]
      REG_EQUAL 0x12345678
    Inserting insn reload before:
   15: r44:SI=high(`*.LC0')
   16: r44:SI=r44:SI+low(`*.LC0')

            0 Non pseudo reload: reject++
          alt=0,overall=1,losers=0,rld_nregs=0
	 Choosing alt 0 in insn 15:  (0) =r  (1) i {movsi_high}
            0 Non pseudo reload: reject++
            1 Non pseudo reload: reject++
          alt=0,overall=2,losers=0,rld_nregs=0
	 Choosing alt 0 in insn 16:  (0) =r  (1) r  (2) i {movsi_lo_sum}
	   Spilling non-eliminable hard regs: 1

```


RTL from .md file of our `movsi_internal` instruction.

```

```

To understand what is going on we should look at the previous path RTL.

### End of IRA

```
(insn 10 6 11 2 (set (reg/i:SI 11 r11)
        (const_int 305419896 [0x12345678])) "../gcc/test8.c":3 16 {*movsi_internal})
(insn 11 10 13 2 (use (reg/i:SI 11 r11)) "../gcc/test8.c":3 -1)
```

### End of Reload (LRA)
```
(insn 15 6 16 2 (set (reg:SI 16 r17 [44])
        (high:SI (symbol_ref/u:SI ("*.LC0") [flags 0x2]))) "../gcc/test8.c":3 17 {movsi_high})
(insn 16 15 10 2 (set (reg:SI 16 r17 [44])
        (lo_sum:SI (reg:SI 16 r17 [44])
            (symbol_ref/u:SI ("*.LC0") [flags 0x2]))) "../gcc/test8.c":3 18 {movsi_lo_sum})
(insn 10 16 11 2 (set (reg/i:SI 11 r11)
        (mem/u/c:SI (reg:SI 16 r17 [44]) [0  S4 A32])) "../gcc/test8.c":3 16 {*movsi_internal})
(insn 11 10 13 2 (use (reg/i:SI 11 r11)) "../gcc/test8.c":3 -1)
```

Further Reading
 - http://gcc-python-plugin.readthedocs.io/en/latest/tables-of-passes.html
 - https://www.airs.com/dnovillo/200711-GCC-Internals/200711-GCC-Internals-7-passes.pdf
 - http://www.drdobbs.com/tools/value-range-propagation/229300211 - Variable Range Propogatoin
