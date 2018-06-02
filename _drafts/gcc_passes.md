---
title: GCC Important Passes
layout: post
date: 2018-05-21 22:37
categories: [ software, embedded, openrisc ]
---

When starting the OpenRISC gcc port I had a good idea of how the compiler worked
and what would be involved in the port.  Those main things being
  1. define a new [machine description](https://gcc.gnu.org/onlinedocs/gccint/#toc-Machine-Descriptions) file in gcc's RTL
  2. define a bunch of description [macros and helper functions](https://gcc.gnu.org/onlinedocs/gccint/#toc-Target-Description-Macros-and-Functions) in a =.c= and =.h= file.

Also I realized early on the trouble shooting issues would be to figure our what
happens during certain compiler passes.  I found it difficult to understand what
all of the compile passes were.  There are so many.  This should help explain.

## Quick Tips

 - When debugging a compiler problems use the `-fdump-rtl-all` and
  `-fdump-tree-all` flags to debug where things go wrong.
 - To understand which passes are run for different -On optimization levels
   you can use ` -fdump-passes`.
 - The numbers in the dump output files indicate the order in which a pass was run. For
   example between `test.c.235r.vregs` and `test.c.234r.expand` the expand pass is run
   before vregs, and there were not passes run inbetween.

## Glossary Terms
 - We may see `cfg` thoughout the gcc source, this is not configuration, but
   [control flow graph](https://en.wikipedia.org/wiki/Control_flow_graph).
 - `Spilling` when there are not enough registers available during register
   allocation to store all scope variables, one variable in a register is chosen
   and `spilled` by saving it to memory.
 - `IL` a GCC intermediate language i.e. GIMPLE or RTL.  During porting we are
   mainly concerned with RTL.
 - `Lowering` are operations done by passes to take higher level language
   and graph representations and make them more simple/lower level in preparation
   for machine assembly conversion.
 - `Predicates` part of the `RTL` these are used to facilitate instruction
   matching before the reload pass.  Having these more specific reduces the work
   that reload needs to do.
 - `Constraints` part of the `RTL` and used during reload, these are associated
   with assembly instructions used to realize and instruction.

First off how do we see the passes, there are basically two types:

 - IPA - [Interprocedural analysis passes](https://gcc.gnu.org/onlinedocs/gccint/IPA.html)
       look at the call graph created during `pass_build_cgraph_edges`, a Tree pass.
 - Tree - GIMPLE
 - RTL - Register Transfer Language, converts GIMPLE to Assembly

Here I will concentrate on RTL as that is what most of the backend influences.

You can find a list of all passes in `gcc/passes.def`.

The passes interesting for our port are the RTL passes:

 - expand
 - vregs
 - split
 - combine
 - ira
 - reload (now lra)

## An Example

```
int func (int a, int b) {
  return 2 * a + b;
}
```

When compiled with `or1k-elf-gcc -O2 -c ../func.c` the output is:

```
00000000 <func>:
   0:   b9 63 00 01     l.slli r11,r3,0x1
   4:   44 00 48 00     l.jr r9
   8:   e1 6b 20 00     l.add r11,r11,r4
```

Lets walk though some of the RTL passes to understand what is happening.

## The Expand Pass

```
  6631 gcc/cfgexpand.c
    28 gcc/cfgexpand.h
  6659 total
```

### Expand Input

The before RTL generation we have GIMPLE.  Below is the content of `func.c.232t.optimized` the last
of the tree passes before RTL conversion.
An important tree passes is [Static Single Assignment](https://en.wikipedia.org/wiki/Static_single_assignment_form)
(SSA) I don't go into it here, but it is what makes us have so many variables, note that
each variable will be assigned only once, this helps simplify the tree for analysis
and later RTL steps like register allocation.

```
func (intD.1 aD.1448, intD.1 bD.1449)
{
  intD.1 a_2(D) = aD.1448;
  intD.1 b_3(D) = bD.1449;
  intD.1 _1;
  intD.1 _4;

  _1 = a_2(D) * 2;
  _4 = _1 + b_3(D);
  return _4;
}
```

### Expand Output

After `expand` we can first see the RTL.  Each statement of the gimple above will
be represented by 1 or more RTL expressions.  I have simplified the RTL a bit and
included the GIMPLE inline for clarity.

This is the contents of `func.c.234r.expand`.

*Tip* Reading RTL.  RTL is a list dialect. Each statement has the form `(type id prev next n (statement))`.

`(insn 2 5 3 2 (set (reg/v:SI 44) (reg:SI 3 r3)) (nil))`

For the instruction:

 - `insn` is the expression type
 - `2` is the instruction unique id
 - `5` is the instruction before it
 - `3` is the next instruction
 - `2` I am not sure what this is
 - `(set (reg/v:SI 44) (reg:SI 3 r3)) (nil)` - is the expression

Back to our example, this is with `-O0` to allow the virtual-stack-vars to not
be elimated for verbosity:

```
;; func (intD.1 aD.1448, intD.1 bD.1449)
;; {
;;   Note: First we save the arguments
;;   intD.1 a_2(D) = aD.1448;
(insn 2 5 3 2 (set (mem/c:SI (reg/f:SI 36 virtual-stack-vars) [1 a+0 S4 A32])
        (reg:SI 3 r3 [ a ])) "../func.c":1 -1
     (nil))

;;   intD.1 b_3(D) = bD.1449;
(insn 3 2 4 2 (set (mem/c:SI (plus:SI (reg/f:SI 36 virtual-stack-vars)
                (const_int 4 [0x4])) [1 b+0 S4 A32])
        (reg:SI 4 r4 [ b ])) "../func.c":1 -1
     (nil))

;;   Note: this was optimized from x 2 to n + n.
;;   _1 = a_2(D) * 2;
;;    This is expanded to:
;;     1. Load a_2(D)
;;     2. Add a_2(D) + a_2(D) store result to temporary
;;     3. Store results to _1
(insn 7 4 8 2 (set (reg:SI 45)
        (mem/c:SI (reg/f:SI 36 virtual-stack-vars) [1 a+0 S4 A32])) "../func.c":2 -1
     (nil))
(insn 8 7 9 2 (set (reg:SI 46)
        (plus:SI (reg:SI 45)
            (reg:SI 45))) "../func.c":2 -1
     (nil))
(insn 9 8 10 2 (set (reg:SI 42 [ _1 ])
        (reg:SI 46)) "../func.c":2 -1
     (nil))a

;;  _4 = _1 + b_3(D);
;;   This is expanded to:
;;    1. Load b_3(D)
;;    2. Do the Add and store to _4
(insn 10 9 11 2 (set (reg:SI 47)
        (mem/c:SI (plus:SI (reg/f:SI 36 virtual-stack-vars)
                (const_int 4 [0x4])) [1 b+0 S4 A32])) "../func.c":2 -1
     (nil))
(insn 11 10 14 2 (set (reg:SI 43 [ _4 ])
        (plus:SI (reg:SI 42 [ _1 ])
            (reg:SI 47))) "../func.c":2 -1
     (nil))

;; return _4;
;;  We put _4 into r11 the openrisc return value register
(insn 14 11 18 2 (set (reg:SI 44 [ <retval> ])
        (reg:SI 43 [ _4 ])) "../func.c":2 -1
     (nil))
(insn 18 14 19 2 (set (reg/i:SI 11 r11)
        (reg:SI 44 [ <retval> ])) "../func.c":3 -1
     (nil))
(insn 19 18 0 2 (use (reg/i:SI 11 r11)) "../func.c":3 -1
     (nil))
```

## The Virtual Register Pass

The virtual register pass is part of the function.c file which has a few different
passes in it.

```
$ grep -n 'pass_data ' gcc/function*

gcc/function.c:1995:const pass_data pass_data_instantiate_virtual_regs =
gcc/function.c:6486:const pass_data pass_data_leaf_regs =
gcc/function.c:6553:const pass_data pass_data_thread_prologue_and_epilogue =
gcc/function.c:6747:const pass_data pass_data_match_asm_constraints =
```

### Vregs Output

Here we can see that the previously seen variables stored to the frame as
`virtual-stack-vars`.  After the Virtual Registers pass these `virtual-`
pointers are replaced with architecture specific registers.

For OpenRISC we see `?fp`, a fake register which we defined with macro
`FRAME_POINTER_REGNUM`.  We use this as a placeholder as OpenRISC's frame
pointer does not point to stack variables (it points to the function incoming
arguments).  The placeholder is needed by GCC but it will be eliminated later.
One some arechitecture this will be a real register at this point.

```
;; Here we see virtual-stack-vars replaced with ?fp.
(insn 2 5 3 2 (set (mem/c:SI (reg/f:SI 33 ?fp) [1 a+0 S4 A32])
        (reg:SI 3 r3 [ a ])) "../func.c":1 16 {*movsi_internal}
     (nil))
(insn 3 2 4 2 (set (mem/c:SI (plus:SI (reg/f:SI 33 ?fp)
                (const_int 4 [0x4])) [1 b+0 S4 A32])
        (reg:SI 4 r4 [ b ])) "../func.c":1 16 {*movsi_internal}
     (nil))
(insn 7 4 8 2 (set (reg:SI 45)
        (mem/c:SI (reg/f:SI 33 ?fp) [1 a+0 S4 A32])) "../func.c":2 16 {*movsi_internal}
     (nil))
(insn 8 7 9 2 (set (reg:SI 46)
        (plus:SI (reg:SI 45)
            (reg:SI 45))) "../func.c":2 2 {addsi3}
     (nil))
(insn 9 8 10 2 (set (reg:SI 42 [ _1 ])
        (reg:SI 46)) "../func.c":2 16 {*movsi_internal}
     (nil))
(insn 10 9 11 2 (set (reg:SI 47)
        (mem/c:SI (plus:SI (reg/f:SI 33 ?fp)
                (const_int 4 [0x4])) [1 b+0 S4 A32])) "../func.c":2 16 {*movsi_internal}
     (nil))
;; ...
```

## The IRA Pass

The IRA and LRA passes are some of the most complicated passes, they are
responsible to turning the psuedo register allocations which have been used up
to this point and assigning real registers.

The [Register Allocation](https://en.wikipedia.org/wiki/Register_allocation)
problem they solve is
[NP-complete](https://en.wikipedia.org/wiki/NP-completeness).

The LRA pass code is around 22,000 lines of code.

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

### IRA Pass Output

```
(insn 21 5 2 2 (set (reg:SI 41)
        (unspec_volatile:SI [
                (const_int 0 [0])
            ] UNSPECV_SET_GOT)) 46 {set_got_tmp}
     (expr_list:REG_UNUSED (reg:SI 41)
        (nil)))
(insn 2 21 3 2 (set (mem/c:SI (reg/f:SI 33 ?fp) [1 a+0 S4 A32])
        (reg:SI 3 r3 [ a ])) "../func.c":1 16 {*movsi_internal}
     (expr_list:REG_DEAD (reg:SI 3 r3 [ a ])
        (nil)))
(insn 3 2 4 2 (set (mem/c:SI (plus:SI (reg/f:SI 33 ?fp)
                (const_int 4 [0x4])) [1 b+0 S4 A32])
        (reg:SI 4 r4 [ b ])) "../func.c":1 16 {*movsi_internal}
     (expr_list:REG_DEAD (reg:SI 4 r4 [ b ])
        (nil)))

(insn 7 4 8 2 (set (reg:SI 45)
        (mem/c:SI (reg/f:SI 33 ?fp) [1 a+0 S4 A32])) "../func.c":2 16 {*movsi_internal}
     (nil))
(insn 8 7 9 2 (set (reg:SI 46)
        (plus:SI (reg:SI 45)
            (reg:SI 45))) "../func.c":2 2 {addsi3}
     (expr_list:REG_DEAD (reg:SI 45)
        (nil)))
(insn 9 8 10 2 (set (reg:SI 42 [ _1 ])
        (reg:SI 46)) "../func.c":2 16 {*movsi_internal}
     (expr_list:REG_DEAD (reg:SI 46)
        (nil)))
(insn 10 9 11 2 (set (reg:SI 47)
        (mem/c:SI (plus:SI (reg/f:SI 33 ?fp)
                (const_int 4 [0x4])) [1 b+0 S4 A32])) "../func.c":2 16 {*movsi_internal}
     (nil))
(insn 11 10 14 2 (set (reg:SI 43 [ _4 ])
        (plus:SI (reg:SI 42 [ _1 ])
            (reg:SI 47))) "../func.c":2 2 {addsi3}
     (expr_list:REG_DEAD (reg:SI 47)
        (expr_list:REG_DEAD (reg:SI 42 [ _1 ])
            (nil))))
(insn 14 11 18 2 (set (reg:SI 44 [ <retval> ])
        (reg:SI 43 [ _4 ])) "../func.c":2 16 {*movsi_internal}
     (expr_list:REG_DEAD (reg:SI 43 [ _4 ])
        (nil)))
(insn 18 14 19 2 (set (reg/i:SI 11 r11)
        (reg:SI 44 [ <retval> ])) "../func.c":3 16 {*movsi_internal}
     (expr_list:REG_DEAD (reg:SI 44 [ <retval> ])
        (nil)))
(insn 19 18 0 2 (use (reg/i:SI 11 r11)) "../func.c":3 -1
     (nil))

```

e# The LRA Pass (Reload)

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
...
            0 Non-pseudo reload: reject+=2
            0 Non input pseudo reload: reject++
            Cycle danger: overall += LRA_MAX_REJECT
          alt=0,overall=609,losers=1,rld_nregs=1
            0 Non-pseudo reload: reject+=2
            0 Non input pseudo reload: reject++
            alt=1: Bad operand -- refuse
            0 Non-pseudo reload: reject+=2
            0 Non input pseudo reload: reject++
            alt=2: Bad operand -- refuse
            0 Non-pseudo reload: reject+=2
            0 Non input pseudo reload: reject++
            alt=3: Bad operand -- refuse
          alt=4,overall=0,losers=0,rld_nregs=0
         Choosing alt 4 in insn 2:  (0) m  (1) rO {*movsi_internal}
...
```

The above snippet of the Local phase of the LRA reload pass shows the contraints
matching loop.

To understand what is going on we should look at what is `insn 2`, from our
input.  This is a set instruction having a destination of memory and a source
of register type, or `m,r`.

```
(insn 2 21 3 2 (set (mem/c:SI (reg/f:SI 33 ?fp) [1 a+0 S4 A32])
        (reg:SI 3 r3 [ a ])) "../func.c":1 16 {*movsi_internal}
     (expr_list:REG_DEAD (reg:SI 3 r3 [ a ])
        (nil)))
```

RTL from .md file of our `*movsi_internal` instruction.  The alternatives are the
constraints, i.e. `=r,r,r,r, m,r`.

```
(define_insn "*mov<I:mode>_internal"
  [(set (match_operand:I 0 "nonimmediate_operand" "=r,r,r,r, m,r")
        (match_operand:I 1 "input_operand"        " r,M,K,I,rO,m"))]
  "register_operand (operands[0], <I:MODE>mode)
   || reg_or_0_operand (operands[1], <I:MODE>mode)"
  "@
   l.or\t%0, %1, %1
   l.movhi\t%0, hi(%1)
   l.ori\t%0, r0, %1
   l.xori\t%0, r0, %1
   l.s<I:ldst>\t%0, %r1
   l.l<I:ldst>z\t%0, %1"
  [(set_attr "type" "alu,alu,alu,alu,st,ld")])
```
The constraints matching interates over the alternatives.   As we remember forom above we are trying to match `m,r`.  We can see:

 - `alt=0` - this shows 1 loser because alt 0 `r,r` vs `m,r` has one match and
   one mismatch.
 - `alt=1` - is indented and says `Bad operand` meaning there is no match at all with `r,M` vs `m,r`
 - `alt=2` - is indented and says `Bad operand` meaning there is no match at all with `r,K` vs `m,r`
 - `alt=3` - is indented and says `Bad operand` meaning there is no match at all with `r,I` vs `m,r`
 - `alt=4` - is as win as we match `m,rO` vs `m,r`



### End of Reload (LRA)

Finally we can see here at the end of Reload all registers are real.

```
(insn 21 5 2 2 (set (reg:SI 16 r17 [41])
        (unspec_volatile:SI [
                (const_int 0 [0])
            ] UNSPECV_SET_GOT)) 46 {set_got_tmp}
     (nil))
(insn 2 21 3 2 (set (mem/c:SI (plus:SI (reg/f:SI 2 r2)
                (const_int -16 [0xfffffffffffffff0])) [1 a+0 S4 A32])
        (reg:SI 3 r3 [ a ])) "../func.c":1 16 {*movsi_internal}
     (nil))
(insn 3 2 4 2 (set (mem/c:SI (plus:SI (reg/f:SI 2 r2)
                (const_int -12 [0xfffffffffffffff4])) [1 b+0 S4 A32])
        (reg:SI 4 r4 [ b ])) "../func.c":1 16 {*movsi_internal}
     (nil))
(note 4 3 7 2 NOTE_INSN_FUNCTION_BEG)
(insn 7 4 8 2 (set (reg:SI 16 r17 [45])
        (mem/c:SI (plus:SI (reg/f:SI 2 r2)
                (const_int -16 [0xfffffffffffffff0])) [1 a+0 S4 A32])) "../func.c":2 16 {*movsi_internal}
     (nil))
(insn 8 7 9 2 (set (reg:SI 16 r17 [46])
        (plus:SI (reg:SI 16 r17 [45])
            (reg:SI 16 r17 [45]))) "../func.c":2 2 {addsi3}
     (nil))
(insn 9 8 10 2 (set (reg:SI 17 r19 [orig:42 _1 ] [42])
        (reg:SI 16 r17 [46])) "../func.c":2 16 {*movsi_internal}
     (nil))
(insn 10 9 11 2 (set (reg:SI 16 r17 [47])
        (mem/c:SI (plus:SI (reg/f:SI 2 r2)
                (const_int -12 [0xfffffffffffffff4])) [1 b+0 S4 A32])) "../func.c":2 16 {*movsi_internal}
     (nil))
(insn 11 10 18 2 (set (reg:SI 16 r17 [orig:43 _4 ] [43])
        (plus:SI (reg:SI 17 r19 [orig:42 _1 ] [42])
            (reg:SI 16 r17 [47]))) "../func.c":2 2 {addsi3}
     (nil))
(insn 18 11 19 2 (set (reg/i:SI 11 r11)
        (reg:SI 16 r17 [orig:44 <retval> ] [44])) "../func.c":3 16 {*movsi_internal}
     (nil))
(insn 19 18 22 2 (use (reg/i:SI 11 r11)) "../func.c":3 -1
     (nil))
(note 22 19 0 NOTE_INSN_DELETED)
```

Further Reading
 - http://gcc-python-plugin.readthedocs.io/en/latest/tables-of-passes.html
 - https://www.airs.com/dnovillo/200711-GCC-Internals/200711-GCC-Internals-7-passes.pdf
 - http://www.drdobbs.com/tools/value-range-propagation/229300211 - Variable Range Propogatoin
